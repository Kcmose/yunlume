import { defineComponent, h, nextTick, type Component } from 'vue'
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
  success: vi.fn(), error: vi.fn(),
}))
vi.mock('@/api/category.api', () => ({ ...api }))
vi.mock('@/api/bookmark.api', () => ({ ...api }))
vi.mock('@/api/searchEngine.api', () => ({ ...api }))
vi.mock('element-plus', () => ({
  ElMessage: { success: api.success, error: api.error, warning: vi.fn() },
  ElMessageBox: { confirm: vi.fn(), alert: vi.fn() },
}))

type Row = { id: number; name: string; visible: boolean; sortOrder: number; [key: string]: unknown }
const row = (): Row => ({
  id: 1, name: '原始名称', icon: '✦', sortOrder: 10, visible: true,
  categoryId: 1, url: 'https://example.test/', description: '原始描述', isRecommend: false, isExternal: true,
  searchUrl: 'https://example.test/?q={keyword}', placeholder: '原始提示', isDefault: false,
})
interface PageState {
  categories: Row[]
  bookmarks: Row[]
  engines: Row[]
  editing: Row | null
  dialogVisible: boolean
  submitting: boolean
  openEdit(value: Row): void
  openCreate(): void
  save(payload: Record<string, unknown>): Promise<void>
  load(): Promise<void>
}
interface DialogState {
  form: Record<string, unknown>
  formRef: { validate(): Promise<boolean>; clearValidate(): void }
  submit(): Promise<void>
}
const pages = [
  { name: '分类', component: CategoryManageView, dialog: CategoryFormDialog, prop: 'category', collection: 'categories' as const, get: api.getCategories, update: api.updateCategory, create: api.createCategory, otherChanges: { visible: false, sortOrder: 90 } },
  { name: '书签', component: BookmarkManageView, dialog: BookmarkFormDialog, prop: 'bookmark', collection: 'bookmarks' as const, get: api.getBookmarks, update: api.updateBookmark, create: api.createBookmark, otherChanges: { visible: false, sortOrder: 90, categoryId: 2, isExternal: false } },
  { name: '搜索引擎', component: SearchEngineManageView, dialog: SearchEngineDialog, prop: 'engine', collection: 'engines' as const, get: api.getSearchEngines, update: api.updateSearchEngine, create: api.createSearchEngine, otherChanges: { visible: false, sortOrder: 90 } },
]
type Page = typeof pages[number]
const cleanups: Array<() => void> = []
async function flush() {
  for (let index = 0; index < 8; index += 1) await Promise.resolve()
  await nextTick()
}

async function mountEditor(page: Page) {
  const mounted = mountComponent<PageState>(page.component)
  cleanups.push(mounted.unmount)
  await flush()
  let saving = Promise.resolve()
  const dialog: Component = { ...page.dialog, render: () => null }
  const parent = defineComponent({
    render() {
      return h(dialog, {
        modelValue: mounted.state.dialogVisible,
        [page.prop]: mounted.state.editing,
        categories: mounted.state.categories,
        submitting: mounted.state.submitting,
        onSubmit: (payload: Record<string, unknown>) => { saving = mounted.state.save(payload) },
      })
    },
  })
  const form = mountComponent(parent, {}, { render: true })
  cleanups.push(form.unmount)
  const state = (form.vm.$.subTree.component as unknown as { setupState: DialogState }).setupState
  state.formRef = { validate: async () => true, clearValidate: vi.fn() }
  mounted.state.openEdit(mounted.state[page.collection][0]!)
  await nextTick()
  return {
    page: mounted.state,
    form: state.form,
    async submit() {
      await state.submit()
      return { completed: saving }
    },
  }
}

beforeEach(() => {
  Object.values(api).forEach((mock) => mock.mockReset())
  api.getCategories.mockImplementation(async () => [row(), { ...row(), id: 2 }])
  api.getBookmarks.mockImplementation(async () => [row()])
  api.getSearchEngines.mockImplementation(async () => [row()])
})
afterEach(() => cleanups.splice(0).reverse().forEach((cleanup) => cleanup()))

describe.each(pages)('$name编辑只提交用户改变的字段', (page) => {
  it.each(['名称先提交', '其他字段先提交'])('%s：两个编辑会话保留彼此结果，迟到的响应不回写旧实体', async (order) => {
    const persisted = row()
    page.get.mockImplementation(async () => [{ ...persisted }])
    const first = await mountEditor(page)
    const second = await mountEditor(page)
    first.form.name = '新的名称'
    Object.assign(second.form, page.otherChanges)
    const nameResponse = deferred<Row>()
    const fieldsResponse = deferred<Row>()
    page.update.mockReturnValueOnce(nameResponse.promise).mockReturnValueOnce(fieldsResponse.promise)
    const nameSave = await first.submit()
    const fieldsSave = await second.submit()
    expect(page.update.mock.calls).toEqual([[1, { name: '新的名称' }], [1, page.otherChanges]])

    // API 边界模拟数据库已分别提交；让旧响应后到，检查管理页是否错误地整行回写。
    const commits = order === '名称先提交'
      ? [{ changes: { name: '新的名称' }, response: nameResponse }, { changes: page.otherChanges, response: fieldsResponse }]
      : [{ changes: page.otherChanges, response: fieldsResponse }, { changes: { name: '新的名称' }, response: nameResponse }]
    const results = commits.map(({ changes, response }) => {
      Object.assign(persisted, changes)
      return { response, snapshot: { ...persisted } }
    })
    results[1]!.response.resolve(results[1]!.snapshot)
    await (order === '名称先提交' ? fieldsSave.completed : nameSave.completed)
    results[0]!.response.resolve(results[0]!.snapshot)
    await Promise.all([nameSave.completed, fieldsSave.completed])
    expect(persisted).toMatchObject({ name: '新的名称', ...page.otherChanges })
    expect(first.page[page.collection][0]).toMatchObject(persisted)
    expect(second.page[page.collection][0]).toMatchObject(persisted)
    expect(api.error).not.toHaveBeenCalled()
  })

  it('打开后原列表行变化不改变编辑基线，也不把旧显隐、排序和所属分类补回请求', async () => {
    const persisted = row()
    page.get.mockImplementation(async () => [{ ...persisted }])
    const editor = await mountEditor(page)
    Object.assign(persisted, page.otherChanges, { isRecommend: true })
    Object.assign(editor.page[page.collection][0]!, persisted)
    expect(editor.page.editing).not.toBe(editor.page[page.collection][0])
    expect(editor.form.visible).toBe(true)
    expect(editor.form.sortOrder).toBe(10)
    editor.form.name = '只改名称'
    page.update.mockImplementation(async (_id, changes) => ({ ...Object.assign(persisted, changes) }))
    await (await editor.submit()).completed
    expect(page.update).toHaveBeenCalledExactlyOnceWith(1, { name: '只改名称' })
    expect(editor.page[page.collection][0]).toMatchObject({ name: '只改名称', ...page.otherChanges, isRecommend: true })
  })

  it('失败保留草稿和基线，重试仍只发送相同改动并保留期间的新状态', async () => {
    const persisted = row()
    page.get.mockImplementation(async () => [{ ...persisted }])
    const editor = await mountEditor(page)
    editor.form.name = '待重试名称'
    page.update.mockRejectedValueOnce(new Error('编辑写入失败'))
    await (await editor.submit()).completed
    expect(editor.page.dialogVisible).toBe(true)
    expect(editor.page.submitting).toBe(false)
    expect(editor.form.name).toBe('待重试名称')
    expect(persisted.name).toBe('原始名称')
    Object.assign(persisted, page.otherChanges)
    page.update.mockImplementation(async (_id, changes) => ({ ...Object.assign(persisted, changes) }))
    await (await editor.submit()).completed
    expect(page.update.mock.calls).toEqual([[1, { name: '待重试名称' }], [1, { name: '待重试名称' }]])
    expect(editor.page.dialogVisible).toBe(false)
    expect(editor.page.submitting).toBe(false)
    expect(editor.page[page.collection][0]).toMatchObject({ name: '待重试名称', ...page.otherChanges })
    expect(api.error).toHaveBeenCalledExactlyOnceWith('编辑写入失败')
  })

  it('显式清空、false 和零都是修改，不会被当作未提供字段', async () => {
    const persisted = row()
    page.get.mockImplementation(async () => [{ ...persisted }])
    const editor = await mountEditor(page)
    const changes: Record<string, unknown> = { icon: '', visible: false, sortOrder: 0 }
    if ('description' in editor.form) changes.description = ''
    if ('placeholder' in editor.form) changes.placeholder = ''
    Object.assign(editor.form, changes)
    page.update.mockImplementation(async (_id, payload) => ({ ...Object.assign(persisted, payload) }))
    await (await editor.submit()).completed
    expect(page.update).toHaveBeenCalledExactlyOnceWith(1, changes)
  })

  it('写入成功后刷新失败，再从旧列表编辑也不覆盖已保存的字段', async () => {
    const persisted = row()
    page.get.mockImplementation(async () => [{ ...persisted }])
    const editor = await mountEditor(page)
    editor.form.name = '已经保存的名称'
    page.update.mockImplementation(async (_id, changes) => ({ ...Object.assign(persisted, changes) }))
    page.get.mockRejectedValueOnce(new Error('刷新失败'))
    await (await editor.submit()).completed
    expect(persisted.name).toBe('已经保存的名称')
    expect(editor.page[page.collection][0]!.name).toBe('原始名称')
    expect(editor.page.dialogVisible).toBe(false)
    expect(editor.page.submitting).toBe(false)

    editor.page.openEdit(editor.page[page.collection][0]!)
    await nextTick()
    editor.form.icon = '新'
    await (await editor.submit()).completed
    expect(page.update.mock.calls).toEqual([[1, { name: '已经保存的名称' }], [1, { icon: '新' }]])
    expect(editor.page[page.collection][0]).toMatchObject({ name: '已经保存的名称', icon: '新' })
    expect(api.error).toHaveBeenCalledExactlyOnceWith('刷新失败')
  })

  it('改回原值后关闭并刷新，不提交旧表单，也不提示已经更新', async () => {
    const persisted = row()
    page.get.mockImplementation(async () => [{ ...persisted }])
    const editor = await mountEditor(page)
    editor.form.name = '临时草稿'
    editor.form.name = '原始名称'
    Object.assign(persisted, page.otherChanges)
    await (await editor.submit()).completed
    expect(page.update).not.toHaveBeenCalled()
    expect(page.create).not.toHaveBeenCalled()
    expect(api.success).not.toHaveBeenCalled()
    expect(editor.page.dialogVisible).toBe(false)
    expect(editor.page[page.collection][0]).toMatchObject(page.otherChanges)
  })

  it('新增仍提交完整表单，不套用编辑的字段省略规则', async () => {
    const editor = await mountEditor(page)
    editor.page.dialogVisible = false
    editor.page.openCreate()
    await nextTick()
    editor.form.name = '新记录'
    if ('url' in editor.form) editor.form.url = 'https://example.test/'
    if ('searchUrl' in editor.form) editor.form.searchUrl = 'https://example.test/?q={keyword}'
    page.create.mockResolvedValueOnce({ ...row(), name: '新记录' })
    await (await editor.submit()).completed
    expect(page.update).not.toHaveBeenCalled()
    expect(page.create).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      name: '新记录', visible: true, sortOrder: 0,
    }))
  })
})

it('搜索引擎的空图标和提示文字不会因 null 与空字符串表示差异产生伪修改', async () => {
  const page = pages[2]!
  page.get.mockResolvedValue([{ ...row(), icon: null, placeholder: null }])
  const editor = await mountEditor(page)
  await (await editor.submit()).completed
  expect(page.update).not.toHaveBeenCalled()
  expect(editor.page.dialogVisible).toBe(false)
})
