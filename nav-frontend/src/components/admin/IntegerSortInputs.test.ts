import { defineComponent, h, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElInputNumber } from 'element-plus'
import Schema, { type Rules } from 'async-validator'
import CategoryFormDialog from './CategoryFormDialog.vue'
import BookmarkFormDialog from './BookmarkFormDialog.vue'
import SearchEngineDialog from './SearchEngineDialog.vue'
import SearchEngineManageView from '@/views/admin/SearchEngineManageView.vue'
import { mountComponent, type TestNode } from '@/test/componentHarness'
import { SORT_ORDER_ERROR, sortOrderInputProps } from '@/utils/sortOrder'
import type { AdminSearchEngine } from '@/types/searchEngine'
import type { SortOrderItem } from '@/types/common'

const mocks = vi.hoisted(() => ({ get: vi.fn(), sort: vi.fn(), error: vi.fn(), success: vi.fn() }))
vi.mock('@/api/searchEngine.api', () => ({ getSearchEngines: mocks.get, sortSearchEngines: mocks.sort }))
vi.mock('element-plus', async (importOriginal) => ({
  ...await importOriginal<typeof import('element-plus')>(),
  ElMessage: { error: mocks.error, success: mocks.success },
}))

const cleanups: Array<() => void> = []
beforeEach(() => {
  Object.values(mocks).forEach((mock) => mock.mockReset())
  // 数字组件和内部 ElInput 均使用真实实现；只提供自定义 renderer 所需的宿主状态。
  const events = new EventTarget()
  vi.stubGlobal('document', {
    activeElement: null,
    addEventListener: events.addEventListener.bind(events),
    removeEventListener: events.removeEventListener.bind(events),
  })
})
afterEach(() => {
  cleanups.splice(0).reverse().forEach((cleanup) => cleanup())
  vi.unstubAllGlobals()
})

function inputs(node: TestNode): TestNode[] {
  return [...(node.type === 'input' ? [node] : []), ...node.children.flatMap(inputs)]
}

function formValidationRules(rules: Rules): Rules {
  // 与真实 ElFormItem 一致：trigger 只决定触发时机，不传入 async-validator。
  return Object.fromEntries(Object.entries(rules).map(([field, value]) => [
    field,
    (Array.isArray(value) ? value : [value]).map((rule) =>
      Object.fromEntries(Object.entries(rule).filter(([key]) => key !== 'trigger'))),
  ]))
}

async function mountNumber(read: () => number | null | undefined, write: (value: number | null | undefined) => void) {
  const parent = defineComponent({
    render: () => h(ElInputNumber, {
      ...sortOrderInputProps,
      modelValue: read(),
      'onUpdate:modelValue': write,
    }),
  })
  const mounted = mountComponent(parent, {}, { render: true })
  cleanups.push(mounted.unmount)
  await nextTick()
  expect(inputs(mounted.root)).toHaveLength(1)
  const input = inputs(mounted.root)[0]!
  return {
    async type(value: string) {
      input.value = value
      await (input.props.onInput as (event: unknown) => Promise<void>)({ target: input })
      await nextTick()
      await (input.props.onChange as (event: unknown) => Promise<void>)({ target: input })
      await nextTick()
    },
  }
}

interface DialogState {
  form: { sortOrder: number | null | undefined; [key: string]: unknown }
  rules: Rules
  formRef: { validate(): Promise<boolean>; clearValidate(): void }
  submit(): Promise<void>
}
const dialogs = [
  { name: '分类', component: CategoryFormDialog, prop: 'category' },
  { name: '书签', component: BookmarkFormDialog, prop: 'bookmark' },
  { name: '搜索引擎', component: SearchEngineDialog, prop: 'engine' },
]
const invalidInputs = [
  { text: '-0.1', value: -0.1 },
  { text: '1.5', value: 1.5 },
  { text: '2147483648', value: 2147483648 },
  { text: '', value: null },
]

describe.each(dialogs)('$name排序整数输入', ({ component, prop }) => {
  it.each(invalidInputs)('实际组件保留“$text”并阻止提交，改为整数后恢复', async ({ text, value }) => {
    const onSubmit = vi.fn()
    const validationErrors: string[] = []
    const mounted = mountComponent<DialogState>(component, {
      modelValue: true, [prop]: null, categories: [{ id: 1 }], submitting: false, onSubmit,
    })
    cleanups.push(mounted.unmount)
    const state = mounted.state
    state.form.name = '测试记录'
    if ('url' in state.form) state.form.url = 'https://example.test/'
    if ('searchUrl' in state.form) state.form.searchUrl = 'https://example.test/?q={keyword}'
    if ('categoryId' in state.form) expect(state.form.categoryId).toBe(1)
    await new Schema(formValidationRules(state.rules)).validate(state.form)
    state.formRef = {
      validate: async () => {
        try {
          await new Schema(formValidationRules(state.rules)).validate(state.form)
          return true
        } catch (error) {
          validationErrors.push(...(error as { errors: Array<{ message: string }> }).errors.map((item) => item.message))
          throw error
        }
      },
      clearValidate: vi.fn(),
    }
    const input = await mountNumber(() => state.form.sortOrder, (next) => { state.form.sortOrder = next })
    await input.type(text)
    expect(state.form.sortOrder).toBe(value)
    await state.submit()
    expect(onSubmit).not.toHaveBeenCalled()
    expect(validationErrors).toEqual([SORT_ORDER_ERROR])
    expect(state.form.sortOrder).toBe(value)

    await input.type('12')
    await state.submit()
    expect(onSubmit).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ sortOrder: 12 }))
  })

  it.each([50000, 2147483647])('合法已有排序 %s 不会被组件改成 9999', async (value) => {
    const mounted = mountComponent<DialogState>(component, {
      modelValue: true, [prop]: null, categories: [{ id: 1 }], submitting: false,
    })
    cleanups.push(mounted.unmount)
    mounted.state.form.sortOrder = value
    await mountNumber(() => mounted.state.form.sortOrder, (next) => { mounted.state.form.sortOrder = next })
    expect(mounted.state.form.sortOrder).toBe(value)
    await new Schema(formValidationRules({ sortOrder: mounted.state.rules.sortOrder! })).validate({ sortOrder: value })
  })
})

interface SearchPageState {
  engines: AdminSearchEngine[]
  sortDraft: Record<string, number | null | undefined>
  savingSort: boolean
  draftOrder(engine: AdminSearchEngine): number | null | undefined
  updateDraft(engine: AdminSearchEngine, value: number | null | undefined): void
  saveSort(): Promise<void>
  load(): Promise<void>
}

describe('搜索引擎列表排序整数输入', () => {
  it.each(invalidInputs)('实际组件输入“$text”不夹取，失败后保留草稿并可恢复保存', async ({ text, value }) => {
    const persisted: AdminSearchEngine[] = [{
      id: '9007199254740993', name: '引擎', searchUrl: 'https://example.test/?q={keyword}',
      sortOrder: 10, isDefault: true, visible: true,
    }]
    mocks.get.mockImplementation(async () => persisted.map((item) => ({ ...item })))
    mocks.sort.mockImplementation(async (items: SortOrderItem[]) => {
      persisted[0]!.sortOrder = items[0]!.sortOrder
      return persisted.map((item) => ({ ...item }))
    })
    const mounted = mountComponent<SearchPageState>(SearchEngineManageView)
    cleanups.push(mounted.unmount)
    const state = mounted.state
    await vi.waitFor(() => expect(state.engines).toHaveLength(1))
    const engine = state.engines[0]!
    const input = await mountNumber(() => state.draftOrder(engine), (next) => state.updateDraft(engine, next))
    await input.type(text)
    await state.saveSort()
    expect(state.sortDraft[String(engine.id)]).toBe(value)
    expect(state.savingSort).toBe(false)
    expect(mocks.sort).not.toHaveBeenCalled()
    expect(mocks.error).toHaveBeenCalledExactlyOnceWith(SORT_ORDER_ERROR)
    await state.load()
    expect(state.sortDraft[String(engine.id)]).toBe(value)

    await input.type('25')
    await state.saveSort()
    expect(mocks.sort).toHaveBeenCalledExactlyOnceWith([{ id: '9007199254740993', sortOrder: 25 }])
    expect(state.engines[0]!.sortOrder).toBe(25)
    expect(state.sortDraft[String(engine.id)]).toBe(25)
    expect(state.savingSort).toBe(false)
  })
})
