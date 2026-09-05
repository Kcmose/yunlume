import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import InstallView from './InstallView.vue'
import { deferred, mountComponent } from '@/test/componentHarness'
import { useInstallStore } from '@/stores/install.store'
import type { CompleteInstallationPayload, InstallStatus } from '@/types/install'

const api = vi.hoisted(() => ({
  getInstallStatusApi: vi.fn(), checkInstallationApi: vi.fn(), completeInstallationApi: vi.fn(),
  configureInstallDatabaseApi: vi.fn(), configureInstallRedisApi: vi.fn(),
  testInstallDatabaseApi: vi.fn(), testInstallRedisApi: vi.fn(),
  success: vi.fn(), error: vi.fn(), replace: vi.fn(), clearSession: vi.fn(),
}))
vi.mock('@/api/install.api', () => ({ ...api }))
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: api.replace }) }))
vi.mock('@/stores/auth.store', () => ({ useAuthStore: () => ({ clearSession: api.clearSession }) }))
vi.mock('element-plus', () => ({
  ElMessage: { success: api.success, error: api.error, warning: vi.fn(), info: vi.fn() },
}))

const required: InstallStatus = { state: 'REQUIRED', installationRequired: true, webInstallEnabled: true, ready: true }
const completed: InstallStatus = { state: 'COMPLETED', installationRequired: false, webInstallEnabled: false, ready: true }
interface PageState {
  form: CompleteInstallationPayload & { confirmationAccepted: boolean }
  formRef: { validateField(): Promise<unknown> }
  submitting: boolean
  submissionFinished: boolean
  completeInstallation(): Promise<void>
  refreshStatus(force: boolean): Promise<void>
}
const cleanups: Array<() => void> = []
async function flush() { await new Promise<void>((resolve) => setImmediate(resolve)); await nextTick() }
async function mountPage() {
  const mounted = mountComponent<PageState>(InstallView)
  cleanups.push(mounted.unmount)
  await flush()
  mounted.state.form.confirmationAccepted = true
  mounted.state.formRef = { validateField: vi.fn().mockResolvedValue(true) }
  api.getInstallStatusApi.mockClear()
  return mounted
}
beforeEach(() => {
  setActivePinia(createPinia())
  Object.values(api).forEach((mock) => mock.mockReset())
  api.getInstallStatusApi.mockResolvedValue(required)
  api.checkInstallationApi.mockResolvedValue({ ready: true })
  api.completeInstallationApi.mockResolvedValue({ installed: true })
  api.replace.mockResolvedValue(undefined)
  vi.stubGlobal('window', { clearTimeout: vi.fn() })
  vi.stubGlobal('document', { getElementById: vi.fn() })
})
afterEach(() => {
  cleanups.splice(0).forEach((cleanup) => cleanup())
  vi.unstubAllGlobals()
})

describe('完成安装的提交归属和互斥', () => {
  it.each(['validation', 'status', 'environment', 'complete'] as const)(
    '%s 等待期间再次触发只完成一次安装', async (stage) => {
      const { state } = await mountPage()
      const pending = deferred<unknown>()
      if (stage === 'validation') state.formRef = { validateField: () => pending.promise }
      if (stage === 'status') api.getInstallStatusApi.mockReturnValueOnce(pending.promise)
      if (stage === 'environment') api.checkInstallationApi.mockReturnValueOnce(pending.promise)
      if (stage === 'complete') api.completeInstallationApi.mockReturnValueOnce(pending.promise)
      const first = state.completeInstallation()
      expect(state.submitting).toBe(true)
      await flush()
      await state.completeInstallation()
      expect(state.submitting).toBe(true)
      pending.resolve(stage === 'status' ? required : { ready: true, installed: true })
      await first
      await state.completeInstallation()
      expect(api.getInstallStatusApi).toHaveBeenCalledTimes(1)
      expect(api.checkInstallationApi).toHaveBeenCalledTimes(1)
      expect(api.completeInstallationApi).toHaveBeenCalledTimes(1)
      expect(api.success).toHaveBeenCalledTimes(1)
      expect(api.error).not.toHaveBeenCalled()
      expect(state.submitting).toBe(false)
      expect(state.submissionFinished).toBe(true)
    },
  )

  it.each(['validation', 'status', 'changed-status', 'environment', 'complete'] as const)(
    '%s 提前退出或失败后释放互斥并允许重试', async (stage) => {
      const { state } = await mountPage()
      if (stage === 'validation') state.formRef = { validateField: vi.fn().mockRejectedValueOnce(new Error('invalid')).mockResolvedValue(true) }
      if (stage === 'status') api.getInstallStatusApi.mockRejectedValueOnce(new Error('offline'))
      if (stage === 'changed-status') api.getInstallStatusApi.mockResolvedValueOnce({ ...required, ready: false })
      if (stage === 'environment') api.checkInstallationApi.mockResolvedValueOnce({ ready: false })
      if (stage === 'complete') api.completeInstallationApi.mockRejectedValueOnce(new Error('failed'))
      await state.completeInstallation()
      expect(state.submitting).toBe(false)
      expect(state.submissionFinished).toBe(false)
      expect(api.success).not.toHaveBeenCalled()
      state.form.confirmationAccepted = true
      await state.completeInstallation()
      expect(api.success).toHaveBeenCalledTimes(1)
      expect(state.submissionFinished).toBe(true)
      expect(state.submitting).toBe(false)
    },
  )

  it.each(['preflight', 'conflict'] as const)('%s 确认已完成时只跳转且不提示安装失败', async (stage) => {
    const { state } = await mountPage()
    if (stage === 'preflight') api.getInstallStatusApi.mockResolvedValueOnce(completed)
    else {
      api.completeInstallationApi.mockRejectedValueOnce(Object.assign(new Error('already installed'), { status: 409 }))
      api.getInstallStatusApi.mockResolvedValueOnce(required).mockResolvedValueOnce(completed)
    }
    await state.completeInstallation()
    expect(state.submissionFinished).toBe(true)
    expect(state.submitting).toBe(false)
    expect(api.replace).toHaveBeenCalledExactlyOnceWith({ name: 'admin-login' })
    expect(api.error).not.toHaveBeenCalled()
  })

  it.each(['validation', 'status', 'environment', 'complete-success', 'complete-failure'] as const)(
    '卸载后忽略 %s 迟到结果，不继续请求或提示', async (stage) => {
      const mounted = await mountPage()
      const pending = deferred<unknown>()
      if (stage === 'validation') mounted.state.formRef = { validateField: () => pending.promise }
      if (stage === 'status') api.getInstallStatusApi.mockReturnValueOnce(pending.promise)
      if (stage === 'environment') api.checkInstallationApi.mockReturnValueOnce(pending.promise)
      if (stage.startsWith('complete')) api.completeInstallationApi.mockReturnValueOnce(pending.promise)
      const operation = mounted.state.completeInstallation()
      await flush()
      const calls = [api.getInstallStatusApi.mock.calls.length, api.checkInstallationApi.mock.calls.length, api.completeInstallationApi.mock.calls.length]
      mounted.unmount()
      if (stage === 'complete-failure') pending.reject(new Error('late failure'))
      else pending.resolve(stage === 'status' ? required : { ready: true, installed: true })
      await operation
      expect([api.getInstallStatusApi.mock.calls.length, api.checkInstallationApi.mock.calls.length, api.completeInstallationApi.mock.calls.length]).toEqual(calls)
      expect(api.success).not.toHaveBeenCalled()
      expect(api.error).not.toHaveBeenCalled()
      expect(api.replace).not.toHaveBeenCalled()
      expect(api.clearSession).not.toHaveBeenCalled()
      expect(mounted.state.submitting).toBe(false)
    },
  )

  it.each(['success', 'failure'] as const)('其他状态刷新已确认完成后，旧提交 %s 不重复跳转或提示', async (result) => {
    const { state } = await mountPage()
    const mutation = deferred<unknown>()
    api.completeInstallationApi.mockReturnValueOnce(mutation.promise)
    const operation = state.completeInstallation()
    await flush()
    api.getInstallStatusApi.mockResolvedValueOnce(completed)
    await state.refreshStatus(true)
    if (result === 'success') mutation.resolve({ installed: true })
    else mutation.reject(new Error('late failure'))
    await operation
    expect(state.submissionFinished).toBe(true)
    expect(state.submitting).toBe(false)
    expect(api.replace).toHaveBeenCalledTimes(1)
    expect(api.clearSession).toHaveBeenCalledTimes(1)
    expect(api.error).not.toHaveBeenCalled()
    expect(api.success).not.toHaveBeenCalled()
  })

  it('已成功安装后的路由错误不改写安装结果或提示安装失败', async () => {
    const { state } = await mountPage()
    api.replace.mockRejectedValueOnce(new Error('navigation cancelled'))
    await state.completeInstallation()
    expect(useInstallStore().status?.state).toBe('COMPLETED')
    expect(state.submissionFinished).toBe(true)
    expect(state.submitting).toBe(false)
    expect(api.error).not.toHaveBeenCalled()
  })
})
