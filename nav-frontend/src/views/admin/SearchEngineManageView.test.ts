import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SearchEngineManageView from './SearchEngineManageView.vue'
import { deferred, mountComponent } from '@/test/componentHarness'
import type { AdminSearchEngine, SearchEnginePayload } from '@/types/searchEngine'

const api = vi.hoisted(() => ({
  getSearchEngines: vi.fn(),
  createSearchEngine: vi.fn(),
  updateSearchEngine: vi.fn(),
  deleteSearchEngine: vi.fn(),
  setDefaultSearchEngine: vi.fn(),
  setSearchEngineVisible: vi.fn(),
  sortSearchEngines: vi.fn(),
}))
vi.mock('@/api/searchEngine.api', () => api)
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm') },
}))

interface PageState {
  engines: AdminSearchEngine[]
  sortDraft: Record<string, number>
  sortChanged: boolean
  savingSort: boolean
  load(): Promise<void>
  updateDraft(row: AdminSearchEngine, value: number): void
  saveSort(): Promise<void>
  visibilityUpdating(row: AdminSearchEngine): boolean
  toggleVisible(row: AdminSearchEngine): Promise<void>
  makeDefault(row: AdminSearchEngine): Promise<void>
  openEdit(row: AdminSearchEngine): void
  save(payload: SearchEnginePayload): Promise<void>
  remove(row: AdminSearchEngine): Promise<void>
}

function engines(): AdminSearchEngine[] {
  return [
    { id: 1, name: 'A', icon: 'A', searchUrl: 'https://a.example/?q={keyword}', isDefault: true, sortOrder: 0, visible: true },
    { id: 2, name: 'B', icon: 'B', searchUrl: 'https://b.example/?q={keyword}', isDefault: false, sortOrder: 10, visible: true },
  ]
}

const cleanups: Array<() => void> = []
async function flush() {
  for (let index = 0; index < 8; index += 1) await Promise.resolve()
  await nextTick()
}
async function mountPage() {
  api.getSearchEngines.mockResolvedValue(engines())
  const mounted = mountComponent<PageState>(SearchEngineManageView)
  cleanups.push(mounted.unmount)
  await flush()
  return mounted.state
}

beforeEach(() => {
  Object.values(api).forEach((mock) => mock.mockReset())
})
afterEach(() => {
  cleanups.splice(0).forEach((cleanup) => cleanup())
})

describe('搜索管理显隐互斥', () => {
  it('同一行只发一个请求，并在读回默认引擎副作用后释放pending', async () => {
    const state = await mountPage()
    const mutation = deferred<AdminSearchEngine>()
    const reload = deferred<AdminSearchEngine[]>()
    api.setSearchEngineVisible.mockReturnValueOnce(mutation.promise)
    api.getSearchEngines.mockReturnValueOnce(reload.promise)
    const row = state.engines[0]!
    row.visible = false
    const first = state.toggleVisible(row)
    await state.toggleVisible(row)
    expect(api.setSearchEngineVisible).toHaveBeenCalledTimes(1)
    expect(api.setSearchEngineVisible).toHaveBeenCalledWith(1, false)
    expect(state.visibilityUpdating(row)).toBe(true)

    mutation.resolve({ ...row, isDefault: false })
    await flush()
    expect(state.visibilityUpdating(row)).toBe(true)
    await state.toggleVisible(row)
    expect(api.setSearchEngineVisible).toHaveBeenCalledTimes(1)
    reload.resolve(engines().map((item) => ({ ...item, visible: item.id !== 1, isDefault: item.id === 2 })))
    await first
    expect(state.visibilityUpdating(row)).toBe(false)
    expect(state.engines.map((item) => [item.visible, item.isDefault])).toEqual([[false, false], [true, true]])
  })

  it('失败恢复本次请求前的值，并允许重试', async () => {
    const state = await mountPage()
    const failure = deferred<AdminSearchEngine>()
    api.setSearchEngineVisible.mockReturnValueOnce(failure.promise)
    const row = state.engines[0]!
    row.visible = false
    const pending = state.toggleVisible(row)
    await state.toggleVisible(row)
    failure.reject(new Error('offline'))
    await pending
    expect(row.visible).toBe(true)
    expect(state.visibilityUpdating(row)).toBe(false)
    expect(api.getSearchEngines).toHaveBeenCalledTimes(1)

    row.visible = false
    api.setSearchEngineVisible.mockResolvedValueOnce({ ...row })
    api.getSearchEngines.mockResolvedValueOnce([{ ...row }])
    await state.toggleVisible(row)
    expect(api.setSearchEngineVisible).toHaveBeenCalledTimes(2)
    expect(state.engines[0]?.visible).toBe(false)
  })
})

describe('搜索排序草稿归属', () => {
  it.each(['default', 'edit', 'delete', 'visibility'] as const)(
    '%s 成功刷新仍保留其他行未保存的排序', async (operation) => {
      const state = await mountPage()
      const row = state.engines[0]!
      const other = state.engines[1]!
      state.updateDraft(row, 25)
      const refreshed = engines().map((item) => ({ ...item, name: `${item.name}-refreshed` }))
      api.getSearchEngines.mockResolvedValueOnce(operation === 'delete' ? [refreshed[0]!] : refreshed)
      if (operation === 'default') await state.makeDefault(other)
      if (operation === 'edit') {
        state.openEdit(other)
        await state.save({ ...other, icon: '', placeholder: '' })
      }
      if (operation === 'delete') await state.remove(other)
      if (operation === 'visibility') {
        other.visible = false
        api.setSearchEngineVisible.mockResolvedValueOnce({ ...other })
        await state.toggleVisible(other)
      }
      expect(state.engines[0]?.name).toBe('A-refreshed')
      expect(state.sortDraft['1']).toBe(25)
      expect(state.sortChanged).toBe(true)
      if (operation === 'delete') expect(state.sortDraft).toEqual({ '1': 25 })
    },
  )

  it.each([0, 20])('提交后改为%d，即使回到旧服务器值也保留新输入', async (newValue) => {
    const state = await mountPage()
    const mutation = deferred<AdminSearchEngine[]>()
    api.sortSearchEngines.mockReturnValueOnce(mutation.promise)
    state.updateDraft(state.engines[0]!, 5)
    const saving = state.saveSort()
    expect(api.sortSearchEngines).toHaveBeenCalledWith([{ id: 1, sortOrder: 5 }, { id: 2, sortOrder: 10 }])
    state.updateDraft(state.engines[0]!, newValue)
    // 排序在途时的普通刷新不能把“改回旧值”的输入视作没有编辑。
    api.getSearchEngines.mockResolvedValueOnce(engines())
    await state.load()
    expect(state.sortDraft['1']).toBe(newValue)
    const persisted = engines().map((item) => item.id === 1 ? { ...item, sortOrder: 5 } : item)
    api.getSearchEngines.mockResolvedValueOnce(persisted)
    mutation.resolve(persisted)
    await saving
    expect(state.engines[0]?.sortOrder).toBe(5)
    expect(state.sortDraft['1']).toBe(newValue)
    expect(state.sortChanged).toBe(true)
    expect(state.savingSort).toBe(false)
  })

  it('排序已提交但读回未完成时的新输入也不会被清理', async () => {
    const state = await mountPage()
    const reload = deferred<AdminSearchEngine[]>()
    api.sortSearchEngines.mockResolvedValueOnce([])
    api.getSearchEngines.mockReturnValueOnce(reload.promise)
    state.updateDraft(state.engines[0]!, 5)
    const saving = state.saveSort()
    await flush()
    state.updateDraft(state.engines[0]!, 0)
    reload.resolve(engines().map((item) => item.id === 1 ? { ...item, sortOrder: 5 } : item))
    await saving
    expect(state.sortDraft['1']).toBe(0)
    expect(state.sortChanged).toBe(true)
  })

  it('只清理成功提交且未继续编辑的草稿；失败保留待重试输入', async () => {
    const state = await mountPage()
    state.updateDraft(state.engines[0]!, 5)
    api.sortSearchEngines.mockRejectedValueOnce(new Error('offline'))
    await state.saveSort()
    expect(state.sortDraft['1']).toBe(5)
    expect(state.sortChanged).toBe(true)
    api.sortSearchEngines.mockResolvedValueOnce([])
    api.getSearchEngines.mockResolvedValueOnce(engines().map((item) => item.id === 1 ? { ...item, sortOrder: 5 } : item))
    await state.saveSort()
    expect(state.sortChanged).toBe(false)
    expect(state.sortDraft['1']).toBe(5)
  })

  it('排序已成功但GET失败时，改回旧值的输入仍可继续提交', async () => {
    const state = await mountPage()
    const reload = deferred<AdminSearchEngine[]>()
    const persisted = engines().map((item) => item.id === 1 ? { ...item, sortOrder: 5 } : item)
    api.sortSearchEngines.mockResolvedValueOnce(persisted)
    api.getSearchEngines.mockReturnValueOnce(reload.promise)
    state.updateDraft(state.engines[0]!, 5)
    const saving = state.saveSort()
    await flush()
    state.updateDraft(state.engines[0]!, 0)
    reload.reject(new Error('readback offline'))
    await saving
    expect(state.engines[0]?.sortOrder).toBe(5)
    expect(state.sortDraft['1']).toBe(0)
    expect(state.sortChanged).toBe(true)
    expect(state.savingSort).toBe(false)

    api.sortSearchEngines.mockResolvedValueOnce(engines())
    api.getSearchEngines.mockResolvedValueOnce(engines())
    await state.saveSort()
    expect(api.sortSearchEngines).toHaveBeenLastCalledWith([{ id: 1, sortOrder: 0 }, { id: 2, sortOrder: 10 }])
    expect(state.sortChanged).toBe(false)
  })

  it('较早的列表响应不能覆盖较新列表或复活删除行', async () => {
    const state = await mountPage()
    const older = deferred<AdminSearchEngine[]>()
    const newer = deferred<AdminSearchEngine[]>()
    api.getSearchEngines.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise)
    state.updateDraft(state.engines[0]!, 25)
    const oldLoad = state.load()
    const newLoad = state.load()
    newer.resolve([{ ...engines()[0]!, name: 'current', sortOrder: 12 }])
    await newLoad
    older.resolve(engines())
    await oldLoad
    expect(state.engines.map((item) => item.name)).toEqual(['current'])
    expect(state.sortDraft).toEqual({ '1': 25 })
  })
})
