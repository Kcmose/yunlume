import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Schema, { type Rules } from 'async-validator'
import type { SiteConfig } from '@/types/site'
import { fallbackSiteConfig } from '@/data/fallback'
import { mountComponent } from '@/test/componentHarness'
import SiteConfigView from './SiteConfigView.vue'

const mocks = vi.hoisted(() => ({
  get: vi.fn(), update: vi.fn(), success: vi.fn(), warning: vi.fn(), error: vi.fn(),
}))
vi.mock('@/api/site.api', () => ({ getAdminSiteConfig: mocks.get, updateSiteConfig: mocks.update }))
vi.mock('@/stores/auth.store', () => ({ useAuthStore: () => ({ token: 'fixture-token' }) }))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))
vi.mock('element-plus', () => ({
  ElMessage: { success: mocks.success, warning: mocks.warning, error: mocks.error },
  ElMessageBox: { confirm: vi.fn() },
}))
vi.mock('@/components/admin/BackgroundImageField.vue', () => ({ default: {} }))

interface SiteState {
  rules: Rules
  form: SiteConfig
  configLoaded: boolean
  formRef: { validate(): Promise<unknown> } | undefined
  save(): Promise<boolean>
}
const cleanup: Array<() => void> = []
beforeEach(() => {
  vi.clearAllMocks()
  mocks.get.mockResolvedValue({ ...fallbackSiteConfig, version: 1, siteDescription: '' })
  mocks.update.mockImplementation(async (payload) => ({ ...payload, version: 2 }))
  vi.stubGlobal('window', { addEventListener: vi.fn(), removeEventListener: vi.fn() })
})
afterEach(() => {
  cleanup.splice(0).forEach((unmount) => unmount())
  vi.unstubAllGlobals()
})
async function mount() {
  const instance = mountComponent<SiteState>(SiteConfigView)
  cleanup.push(instance.unmount)
  await new Promise<void>((resolve) => setImmediate(resolve))
  // Element Plus 的实际验证引擎读取当前组件规则；只替换表单宿主与网络边界。
  instance.state.formRef = { validate: async () => {
    await new Schema(instance.state.rules).validate(instance.state.form)
    return true
  } }
  return instance.state
}

describe('site identity form contract', () => {
  it('saves other settings when an installed site has no description', async () => {
    const state = await mount()
    expect(state.configLoaded).toBe(true)
    state.form.messageText = '新公告'

    expect(await state.save()).toBe(true)
    expect(mocks.update).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      siteDescription: '', messageText: '新公告', expectedVersion: 1,
    }))
  })

  it('accepts the same name and description lengths as installation and the API', async () => {
    const state = await mount()
    state.form.siteName = 'n'.repeat(50)
    state.form.siteDescription = 'd'.repeat(255)
    expect(await state.save()).toBe(true)
  })

  it.each([
    { siteName: '' },
    { siteName: '   ' },
    { siteName: 'n'.repeat(51) },
    { siteDescription: 'd'.repeat(256) },
  ])('rejects invalid identity fields before writing: %j', async (fields) => {
    const state = await mount()
    Object.assign(state.form, fields)
    expect(await state.save()).toBe(false)
    expect(mocks.update).not.toHaveBeenCalled()
  })
})
