import { defineComponent, h, nextTick, ref, shallowRef, type Component } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CategoryManageView from './CategoryManageView.vue'
import BookmarkManageView from './BookmarkManageView.vue'
import SearchEngineManageView from './SearchEngineManageView.vue'
import CategoryFormDialog from '@/components/admin/CategoryFormDialog.vue'
import BookmarkFormDialog from '@/components/admin/BookmarkFormDialog.vue'
import SearchEngineDialog from '@/components/admin/SearchEngineDialog.vue'
import { deferred, mountComponent } from '@/test/componentHarness'

const api = vi.hoisted(() => ({
  getCategories: vi.fn(), createCategory: vi.fn(), updateCategory: vi.fn(),
  deleteCategory: vi.fn(), setCategoryVisible: vi.fn(), sortCategories: vi.fn(),
  getBookmarks: vi.fn(), createBookmark: vi.fn(), updateBookmark: vi.fn(),
  deleteBookmark: vi.fn(), setBookmarkVisible: vi.fn(), sortBookmarks: vi.fn(), batchMoveBookmarks: vi.fn(),
  getSearchEngines: vi.fn(), createSearchEngine: vi.fn(), updateSearchEngine: vi.fn(),
  deleteSearchEngine: vi.fn(), setSearchEngineVisible: vi.fn(), sortSearchEngines: vi.fn(), setDefaultSearchEngine: vi.fn(),
  success: vi.fn(),
}))
vi.mock('@/api/category.api', () => ({ ...api }))
vi.mock('@/api/bookmark.api', () => ({ ...api }))
vi.mock('@/api/searchEngine.api', () => ({ ...api }))
vi.mock('element-plus', () => ({
  ElMessage: { success: api.success, error: vi.fn(), warning: vi.fn() },
  ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm'), alert: vi.fn() },
}))

type Row = { id: number; name: string; [key: string]: unknown }
const row = (id: number): Row => ({
  id, name: `record-${id}`, icon: '', sortOrder: 0, visible: true,
  categoryId: 1, url: 'https://example.test/', description: '', isRecommend: false, isExternal: true,
  searchUrl: 'https://example.test/?q={keyword}', placeholder: '', isDefault: id === 1,
})
interface PageState {
  dialogVisible: boolean
  editing: Row | null
  submitting: boolean
  openCreate(): void
  openEdit(value: Row): void
  save(payload: Record<string, unknown>): Promise<void>
}
interface DialogState {
  form: Record<string, unknown>
  formRef: { validate(): Promise<boolean>; clearValidate(): void }
  submit(): Promise<void>
}
const pages = [
  { name: '分类', component: CategoryManageView, update: api.updateCategory, create: api.createCategory },
  { name: '书签', component: BookmarkManageView, update: api.updateBookmark, create: api.createBookmark },
  { name: '搜索引擎', component: SearchEngineManageView, update: api.updateSearchEngine, create: api.createSearchEngine },
]
const dialogs = [
  { name: '分类', component: CategoryFormDialog, recordProp: 'category' },
  { name: '书签', component: BookmarkFormDialog, recordProp: 'bookmark' },
  { name: '搜索引擎', component: SearchEngineDialog, recordProp: 'engine' },
]
const cleanups: Array<() => void> = []
async function flush() {
  for (let index = 0; index < 8; index += 1) await Promise.resolve()
  await nextTick()
}

beforeEach(() => {
  Object.values(api).forEach((mock) => mock.mockReset())
  api.getCategories.mockResolvedValue([row(1), row(2)])
  api.getBookmarks.mockResolvedValue([row(1), row(2)])
  api.getSearchEngines.mockResolvedValue([row(1), row(2)])
})
afterEach(() => cleanups.splice(0).forEach((cleanup) => cleanup()))

describe('管理页保存结果只属于原弹窗', () => {
  it.each(pages)('$name：A保存迟到不关B，待A完成后才能继续保存B', async ({ component, update }) => {
    const mounted = mountComponent<PageState>(component)
    cleanups.push(mounted.unmount)
    await flush()
    const state = mounted.state
    const first = deferred<unknown>()
    const second = deferred<unknown>()
    update.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    state.openEdit(row(1))
    const saveA = state.save({ name: 'saved-A' })
    state.dialogVisible = false
    state.openEdit(row(2))
    expect(state.submitting).toBe(true)
    await state.save({ name: 'draft-B' })
    expect(update.mock.calls).toEqual([[1, { name: 'saved-A' }]])

    first.resolve(row(1))
    await saveA
    expect(state.dialogVisible).toBe(true)
    expect(state.editing?.id).toBe(2)
    expect(state.submitting).toBe(false)
    const saveB = state.save({ name: 'draft-B' })
    expect(update.mock.calls).toEqual([[1, { name: 'saved-A' }], [2, { name: 'draft-B' }]])
    second.resolve(row(2))
    await saveB
    expect(state.dialogVisible).toBe(false)
    expect(state.submitting).toBe(false)
  })

  it.each(pages)('$name：创建成功仍使用原操作文案，不关闭后来打开的编辑框', async ({ name, component, create }) => {
    const mounted = mountComponent<PageState>(component)
    cleanups.push(mounted.unmount)
    await flush()
    const pending = deferred<unknown>()
    create.mockReturnValueOnce(pending.promise)
    const state = mounted.state
    state.openCreate()
    const saving = state.save({ name: 'new-A' })
    state.dialogVisible = false
    state.openEdit(row(2))
    pending.resolve(row(3))
    await saving
    expect(api.success).toHaveBeenCalledWith(`${name}已创建`)
    expect(state.dialogVisible).toBe(true)
    expect(state.editing?.id).toBe(2)
  })
})

describe('弹窗异步校验不能跨草稿提交', () => {
  it.each(dialogs)('$name：旧校验完成不会提交重新打开后的表单', async ({ component, recordProp }) => {
    const onSubmit = vi.fn()
    const scriptOnly: Component = { ...component, render: () => null }
    const parent = defineComponent({
      setup: () => ({ visible: ref(true), record: shallowRef(row(1)) }),
      render() {
        return h(scriptOnly, {
          modelValue: this.visible,
          [recordProp]: this.record,
          categories: [row(1)],
          submitting: false,
          onSubmit,
        })
      },
    })
    const mounted = mountComponent<{ visible: boolean; record: Row }>(parent, {}, { render: true })
    cleanups.push(mounted.unmount)
    const state = (mounted.vm.$.subTree.component as unknown as { setupState: DialogState }).setupState
    const validation = deferred<boolean>()
    state.formRef = { validate: () => validation.promise, clearValidate: vi.fn() }
    state.form.name = 'old-draft'
    const oldSubmit = state.submit()
    mounted.state.visible = false
    await nextTick()
    mounted.state.record = row(2)
    mounted.state.visible = true
    await nextTick()
    state.form.name = 'new-draft'
    validation.resolve(true)
    await oldSubmit
    expect(onSubmit).not.toHaveBeenCalled()
    expect(state.form.name).toBe('new-draft')

    state.formRef = { validate: async () => true, clearValidate: vi.fn() }
    await state.submit()
    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit.mock.calls[0]?.[0].name).toBe('new-draft')
  })
})
