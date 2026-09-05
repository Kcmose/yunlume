import { defineComponent, h, nextTick, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Schema, { type Rules } from 'async-validator'
import SearchEngineDialog from './SearchEngineDialog.vue'
import SearchEngineManageView from '@/views/admin/SearchEngineManageView.vue'
import { mountComponent } from '@/test/componentHarness'
import { searchEngineIconUrl, searchEngineMark } from '@/utils/searchEnginePicker'
import type { AdminSearchEngine, SearchEnginePayload } from '@/types/searchEngine'

vi.mock('@/api/searchEngine.api', () => ({ getSearchEngines: vi.fn().mockResolvedValue([]) }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }))

interface DialogState {
  form: SearchEnginePayload
  rules: Rules
  formRef: { validate(): Promise<boolean>; clearValidate(): void }
  submit(): Promise<void>
}

const cleanups: Array<() => void> = []
afterEach(() => cleanups.splice(0).forEach((cleanup) => cleanup()))

describe('搜索引擎图标保存和展示契约', () => {
  it.each(['🇨🇳', '😀😀😀', 'A😀中', 'abc'])('新增及编辑均保留短图标 %s', async (icon) => {
    const engine: AdminSearchEngine = {
      id: 1, name: '测试引擎', icon, searchUrl: 'https://example.test/?q={keyword}',
      sortOrder: 0, visible: true, isDefault: false,
    }
    for (const editing of [false, true]) {
      const onSubmit = vi.fn()
      const dialog = { ...SearchEngineDialog, render: () => null }
      const parent = defineComponent({
        setup() { return { visible: ref(false) } },
        render() {
          return h(dialog, {
            modelValue: this.visible, engine: editing ? engine : null, submitting: false, onSubmit,
          })
        },
      })
      const mounted = mountComponent<{ visible: boolean }>(parent, {}, { render: true })
      cleanups.push(mounted.unmount)
      const state = (mounted.vm.$.subTree.component as unknown as { setupState: DialogState }).setupState
      state.formRef = {
        validate: async () => { await new Schema(state.rules).validate(state.form); return true },
        clearValidate: vi.fn(),
      }
      mounted.state.visible = true
      await nextTick()
      if (!editing) Object.assign(state.form, engine)
      else expect(state.form.icon).toBe(icon)
      state.form.name = '已更新名称'
      await state.submit()
      expect(onSubmit).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ icon, name: '已更新名称' }))

      const saved = { ...engine, ...onSubmit.mock.calls[0]![0] } as AdminSearchEngine
      const page = mountComponent<{
        iconUrl(engine: AdminSearchEngine): string
        iconMark(engine: AdminSearchEngine): string
      }>(SearchEngineManageView)
      cleanups.push(page.unmount)
      expect(page.state.iconUrl(saved)).toBe('')
      expect(searchEngineIconUrl(saved)).toBe('')
      expect(page.state.iconMark(saved)).toBe(icon)
      expect(searchEngineMark(saved)).toBe(icon)
    }
  })

  it.each([
    ['example.test/icon.svg', 'https://example.test/icon.svg'],
    ['https://example.test/icon.svg', 'https://example.test/icon.svg'],
    ['', ''],
  ])('图片地址和空图标仍按原规则保存：%s', async (icon, expected) => {
    const onSubmit = vi.fn()
    const mounted = mountComponent<DialogState>(SearchEngineDialog, {
      modelValue: true, engine: null, submitting: false, onSubmit,
    })
    cleanups.push(mounted.unmount)
    const state = mounted.state
    Object.assign(state.form, { name: 'Example', icon, searchUrl: 'https://example.test/?q={keyword}' })
    state.formRef = {
      validate: async () => { await new Schema(state.rules).validate(state.form); return true },
      clearValidate: vi.fn(),
    }
    await state.submit()
    expect(onSubmit).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ icon: expected }))
  })
})
