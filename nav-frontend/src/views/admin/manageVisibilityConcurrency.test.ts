import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CategoryManageView from './CategoryManageView.vue'
import BookmarkManageView from './BookmarkManageView.vue'
import { deferred, mountComponent } from '@/test/componentHarness'
import type { SortOrderItem } from '@/types/common'

const api = vi.hoisted(() => ({
  getCategories: vi.fn(), createCategory: vi.fn(), updateCategory: vi.fn(),
  deleteCategory: vi.fn(), setCategoryVisible: vi.fn(), sortCategories: vi.fn(),
  getBookmarks: vi.fn(), createBookmark: vi.fn(), updateBookmark: vi.fn(),
  deleteBookmark: vi.fn(), setBookmarkVisible: vi.fn(), sortBookmarks: vi.fn(), batchMoveBookmarks: vi.fn(),
  success: vi.fn(), error: vi.fn(),
}))
vi.mock('@/api/category.api', () => ({ ...api }))
vi.mock('@/api/bookmark.api', () => ({ ...api }))
vi.mock('element-plus', () => ({
  ElMessage: { success: api.success, error: api.error, warning: vi.fn() },
  ElMessageBox: { confirm: vi.fn(), alert: vi.fn() },
}))

type Row = { id: number; name: string; visible: boolean; [key: string]: unknown }
const row = (id: number, visible = true): Row => ({
  id, name: `record-${id}`, icon: '', sortOrder: id * 10, visible,
  categoryId: 1, url: 'https://example.test/', description: '', isRecommend: false, isExternal: true,
})
interface PageState {
  categories: Row[]
  bookmarks: Row[]
  selectedCategory: number | ''
  visibilityUpdating(row: Row): boolean
  toggleVisible(row: Row): Promise<void>
  openCreate(): void
  save(payload: Record<string, unknown>): Promise<void>
  saveSort(items: SortOrderItem[]): Promise<void>
  saveBookmarkSort(items: SortOrderItem[]): Promise<void>
  load(): Promise<void>
}
const pages = [
  { name: '分类', component: CategoryManageView, collection: 'categories' as const, get: api.getCategories, create: api.createCategory, visible: api.setCategoryVisible, sort: api.sortCategories, saveSort: 'saveSort' as const },
  { name: '书签', component: BookmarkManageView, collection: 'bookmarks' as const, get: api.getBookmarks, create: api.createBookmark, visible: api.setBookmarkVisible, sort: api.sortBookmarks, saveSort: 'saveBookmarkSort' as const },
]
const completionOrders = ['列表先返回', '显隐先完成'] as const
const cleanups: Array<() => void> = []
async function flush() {
  for (let index = 0; index < 8; index += 1) await Promise.resolve()
  await nextTick()
}

beforeEach(() => {
  Object.values(api).forEach((mock) => mock.mockReset())
  api.getCategories.mockResolvedValue([row(1), row(2)])
  api.getBookmarks.mockResolvedValue([row(1), row(2)])
})
afterEach(() => cleanups.splice(0).forEach((cleanup) => cleanup()))

describe.each(pages)('$name显隐与列表快照并发', (page) => {
  it.each(completionOrders.flatMap((order) => ['新增后刷新', '排序响应'].map((source) => ({ order, source }))))(
    '$source、$order：保留已确认的显隐，同时接受其他字段与列表成员',
    async ({ order, source }) => {
      const mounted = mountComponent<PageState>(page.component)
      cleanups.push(mounted.unmount)
      await flush()
      const state = mounted.state
      const previous = state[page.collection][0]!
      const visibilityResponse = deferred<Row>()
      page.visible.mockReturnValueOnce(visibilityResponse.promise)
      // el-switch 的 v-model 先写值，再触发 change。
      previous.visible = false
      const hiding = state.toggleVisible(previous)
      expect(page.visible).toHaveBeenCalledExactlyOnceWith(1, false)

      const snapshotResponse = deferred<Row[]>()
      const snapshot = [
        { ...row(1), name: '快照中的最新名称', sortOrder: 90 }, row(2),
        ...(source === '新增后刷新' ? [row(3)] : []),
      ]
      let refreshing: Promise<void>
      if (source === '新增后刷新') {
        page.create.mockResolvedValueOnce(row(3))
        page.get.mockReturnValueOnce(snapshotResponse.promise)
        state.openCreate()
        refreshing = state.save({ name: 'new-row' })
        await vi.waitFor(() => expect(page.get).toHaveBeenCalledTimes(2))
      } else {
        state.selectedCategory = 1
        page.sort.mockReturnValueOnce(snapshotResponse.promise)
        refreshing = state[page.saveSort]([{ id: 1, sortOrder: 90 }, { id: 2, sortOrder: 20 }])
        expect(page.sort).toHaveBeenCalledOnce()
      }

      if (order === '列表先返回') {
        snapshotResponse.resolve(snapshot)
        await refreshing
        expect(state[page.collection][0]).not.toBe(previous)
        expect(state.visibilityUpdating(state[page.collection][0]!)).toBe(true)
        // 新数组仍持有该行互斥，不能再次提交显隐。
        await state.toggleVisible(state[page.collection][0]!)
        expect(page.visible).toHaveBeenCalledOnce()
        visibilityResponse.resolve(row(1, false))
        await hiding
      } else {
        visibilityResponse.resolve(row(1, false))
        await hiding
        snapshotResponse.resolve(snapshot)
        await refreshing
      }
      expect(state[page.collection][0]).toMatchObject({ visible: false, name: '快照中的最新名称', sortOrder: 90 })
      expect(state[page.collection]).toHaveLength(snapshot.length)
      expect(state.visibilityUpdating(state[page.collection][0]!)).toBe(false)
      expect(api.error).not.toHaveBeenCalled()

      // 交错结束后，新发出的权威快照仍可反映其他会话的后续修改。
      page.get.mockResolvedValueOnce([row(1, true), row(2)])
      await state.load()
      expect(state[page.collection][0]!.visible).toBe(true)
    },
  )

  it.each(completionOrders)('%s：显隐失败恢复当前行，并释放互斥供重试', async (order) => {
    const mounted = mountComponent<PageState>(page.component)
    cleanups.push(mounted.unmount)
    await flush()
    const state = mounted.state
    const previous = state[page.collection][0]!
    const visibilityResponse = deferred<Row>()
    page.visible.mockReturnValueOnce(visibilityResponse.promise)
    previous.visible = false
    const hiding = state.toggleVisible(previous)
    const snapshotResponse = deferred<Row[]>()
    page.get.mockReturnValueOnce(snapshotResponse.promise)
    const refreshing = state.load()
    const snapshot = [{ ...row(1), name: '刷新后的名称' }, row(2)]

    if (order === '列表先返回') {
      snapshotResponse.resolve(snapshot)
      await refreshing
      visibilityResponse.reject(new Error('显隐请求失败'))
      await hiding
    } else {
      visibilityResponse.reject(new Error('显隐请求失败'))
      await hiding
      snapshotResponse.resolve(snapshot)
      await refreshing
    }
    const current = state[page.collection][0]!
    expect(current).toMatchObject({ visible: true, name: '刷新后的名称' })
    expect(state.visibilityUpdating(current)).toBe(false)
    expect(api.error).toHaveBeenCalledExactlyOnceWith('显隐请求失败')

    page.visible.mockResolvedValueOnce(row(1, false))
    current.visible = false
    await state.toggleVisible(current)
    expect(page.visible).toHaveBeenCalledTimes(2)
    expect(current.visible).toBe(false)
  })
})
