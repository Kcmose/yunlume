import { defineComponent, h, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CategoryManageView from './CategoryManageView.vue'
import BookmarkManageView from './BookmarkManageView.vue'
import SortOrderDialog from '@/components/admin/SortOrderDialog.vue'
import { deferred, mountComponent } from '@/test/componentHarness'
import type { EntityId, SortOrderItem } from '@/types/common'

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
  ElMessage: { success: api.success, error: api.error, warning: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn(), alert: vi.fn() },
}))

type Row = { id: number; name: string; sortOrder: number; [key: string]: unknown }
type DialogItem = { id: EntityId; label: string }
interface PageState {
  categories: Row[]
  bookmarks: Row[]
  selectedCategory: EntityId | ''
  sortVisible: boolean
  savingSort: boolean
  sortItems: DialogItem[]
  bookmarkSortItems: DialogItem[]
  openBookmarkSort(): void
  saveSort(items: SortOrderItem[]): Promise<void>
  saveBookmarkSort(items: SortOrderItem[]): Promise<void>
}
interface DialogState {
  draft: DialogItem[]
  move(index: number, offset: number): void
  submit(): void
  updateVisible(visible: boolean): void
}
const row = (id: number): Row => ({
  id, name: `record-${id}`, icon: '', sortOrder: (id - 1) * 10, visible: true,
  bookmarkCount: 0, categoryId: 1, url: 'https://example.test/', description: '',
  isRecommend: false, isExternal: true,
})
const pages = [
  { name: '分类', component: CategoryManageView, collection: 'categories' as const, items: 'sortItems' as const, sort: api.sortCategories, save: 'saveSort' as const },
  { name: '书签', component: BookmarkManageView, collection: 'bookmarks' as const, items: 'bookmarkSortItems' as const, sort: api.sortBookmarks, save: 'saveBookmarkSort' as const },
]
const cleanups: Array<() => void> = []
async function flush() {
  for (let index = 0; index < 8; index += 1) await Promise.resolve()
  await nextTick()
}

beforeEach(() => Object.values(api).forEach((mock) => mock.mockReset()))
afterEach(() => cleanups.splice(0).reverse().forEach((cleanup) => cleanup()))

describe.each(pages)('$name超过 1000 条的完整排序', (page) => {
  it.each([false, true])('先失败再重试：%s；单请求保存首尾调整，成功后采纳响应', async (retry) => {
    const records = Array.from({ length: 1001 }, (_, index) => row(index + 1))
    const outsideCategory = { ...row(2001), categoryId: 2 }
    api.getCategories.mockResolvedValue(page.collection === 'categories' ? records : [row(1), row(2)])
    api.getBookmarks.mockResolvedValue([...records, outsideCategory])
    const mounted = mountComponent<PageState>(page.component)
    cleanups.push(mounted.unmount)
    await flush()
    const state = mounted.state
    const originalRows = state[page.collection]
    const scriptOnlyDialog = { ...SortOrderDialog, render: () => null }
    const submissions: Promise<void>[] = []
    // 真实父子 props/event 连线，让打开、保存中和关闭都经过 Vue 的更新周期。
    const parent = defineComponent({
      render: () => h(scriptOnlyDialog, {
        modelValue: state.sortVisible,
        title: '调整顺序', description: '',
        submitting: state.savingSort,
        items: state[page.items],
        'onUpdate:modelValue': (visible: boolean) => { state.sortVisible = visible },
        onSubmit: (items: SortOrderItem[]) => { submissions.push(state[page.save](items)) },
      }),
    })
    const dialog = mountComponent(parent, {}, { render: true })
    cleanups.push(dialog.unmount)
    const draft = (dialog.vm.$.subTree.component as unknown as { setupState: DialogState }).setupState
    if (page.collection === 'bookmarks') {
      state.selectedCategory = 1
      state.openBookmarkSort()
    } else state.sortVisible = true
    await nextTick()
    expect(draft.draft).toHaveLength(1001)
    draft.move(0, 1)
    draft.move(1000, -1)
    const expectedIds = [2, 1, ...Array.from({ length: 997 }, (_, index) => index + 3), 1001, 1000]
    const expectedPayload = expectedIds.map((id, index) => ({ id, sortOrder: index * 10 }))

    let pending = deferred<Row[]>()
    page.sort.mockReturnValueOnce(pending.promise)
    draft.submit()
    // 同一事件轮次的连点由页面互斥拦住，props 更新后由弹窗拦住。
    draft.submit()
    await nextTick()
    draft.submit()
    draft.updateVisible(false)
    expect(page.sort).toHaveBeenCalledExactlyOnceWith(expectedPayload)
    expect(state.sortVisible).toBe(true)
    expect(state.savingSort).toBe(true)
    expect(state[page.collection]).toBe(originalRows)
    expect(api.success).not.toHaveBeenCalled()

    if (retry) {
      pending.reject(new Error('排序暂时失败'))
      await Promise.all(submissions)
      await flush()
      expect(state.sortVisible).toBe(true)
      expect(state.savingSort).toBe(false)
      expect(state[page.collection]).toBe(originalRows)
      expect(draft.draft.map((item) => item.id)).toEqual(expectedIds)
      expect(api.error).toHaveBeenCalledExactlyOnceWith('排序暂时失败')
      expect(api.success).not.toHaveBeenCalled()
      pending = deferred<Row[]>()
      page.sort.mockReturnValueOnce(pending.promise)
      draft.submit()
      await nextTick()
      draft.submit()
      expect(page.sort).toHaveBeenCalledTimes(2)
      expect(page.sort.mock.calls[1]).toEqual([expectedPayload])
      expect(state.savingSort).toBe(true)
    }

    const response = expectedPayload.map(({ id, sortOrder }) => ({
      ...row(Number(id)), sortOrder, name: `服务端确认-${id}`,
    }))
    if (page.collection === 'bookmarks') response.push(outsideCategory)
    pending.resolve(response)
    await Promise.all(submissions)
    await flush()
    expect(state.sortVisible).toBe(false)
    expect(state.savingSort).toBe(false)
    expect(state[page.collection]).toEqual(response)
    expect(api.success).toHaveBeenCalledOnce()
    expect(api.error).toHaveBeenCalledTimes(retry ? 1 : 0)
    expect(page.sort).toHaveBeenCalledTimes(retry ? 2 : 1)
  })
})
