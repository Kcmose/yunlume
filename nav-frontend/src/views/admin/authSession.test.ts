import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { FormInstance } from 'element-plus'
import type { AdminUser, ChangePasswordPayload, LoginPayload, LoginResult } from '@/types/auth'
import { deferred, mountComponent } from '@/test/componentHarness'

const mocks = vi.hoisted(() => ({
  loginApi: vi.fn(),
  profileApi: vi.fn(),
  logoutApi: vi.fn(),
  changePasswordApi: vi.fn(),
  logoutAllApi: vi.fn(),
  replace: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  confirm: vi.fn(),
  route: { query: {} as Record<string, unknown> },
}))

// 默认 token 适配器在模块加载时绑定 Storage，因此先提供稳定实例，再逐用例清空数据。
const { values, local } = vi.hoisted(() => {
  const values = new Map<string, string>()
  const local: Storage = {
    get length() { return values.size },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => [...values.keys()][index] ?? null,
    removeItem: (key) => { values.delete(key) },
    setItem: (key, value) => { values.set(key, value) },
  }
  vi.stubGlobal('localStorage', local)
  return { values, local }
})

vi.mock('@/api/auth.api', () => ({
  loginApi: mocks.loginApi,
  profileApi: mocks.profileApi,
  logoutApi: mocks.logoutApi,
  changePasswordApi: mocks.changePasswordApi,
  logoutAllApi: mocks.logoutAllApi,
}))
vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ replace: mocks.replace }),
}))
vi.mock('element-plus', () => ({
  ElMessage: { success: mocks.success, error: mocks.error },
  ElMessageBox: { confirm: mocks.confirm },
}))

import LoginView from './LoginView.vue'
import AccountManageView from './AccountManageView.vue'
import { useAuthStore } from '@/stores/auth.store'
import {
  AUTH_BARRIER_KEY,
  AUTH_ENVELOPE_KEY,
  USER_KEY,
  TokenPersistenceError,
  boundUserStorage,
  createTokenStorage,
  tokenStorage,
} from '@/utils/storage'

interface LoginState {
  formRef: FormInstance | undefined
  form: LoginPayload
  submit: () => Promise<void>
}

interface AccountState {
  formRef: FormInstance | undefined
  form: ChangePasswordPayload
  changingPassword: boolean
  loggingOutAll: boolean
  submitPasswordChange: () => Promise<void>
  logoutAllSessions: () => Promise<void>
}

const originalUser: AdminUser = { id: 1, username: 'admin', nickname: '原管理员', role: 'ADMIN' }
const otherUser: AdminUser = { id: 2, username: 'other-admin', nickname: '新管理员', role: 'ADMIN' }
const credentials: LoginPayload = { username: 'admin', password: 'secret' }
const passwords: ChangePasswordPayload = {
  currentPassword: 'OldPassword1!', newPassword: 'ChangedPassword2!', confirmPassword: 'ChangedPassword2!',
}

let tabWindow: EventTarget
let store: ReturnType<typeof useAuthStore> | undefined
let identity = 0
const mounted: Array<{ unmount: () => void }> = []

function dispatchStorage(key: string): void {
  tabWindow.dispatchEvent(Object.assign(new Event('storage'), { key, storageArea: local }))
}

// 第二个适配器写入同一持久化空间，事件单独派发以覆盖浏览器延迟送达的情况。
function loginInOtherTab(token = 'shared-token', deliverEvents = true) {
  const adapter = createTokenStorage(local, () => `other-${++identity}`, () => `commit-${++identity}`)
  adapter.set(token)
  const snapshot = adapter.getSnapshot()!
  expect(boundUserStorage.set(otherUser, snapshot)).toBe(true)
  if (deliverEvents) {
    dispatchStorage(AUTH_BARRIER_KEY)
    dispatchStorage(USER_KEY)
  }
  return { adapter, snapshot, records: new Map(values) }
}

function startSession(): ReturnType<typeof useAuthStore> {
  tokenStorage.set('shared-token')
  boundUserStorage.set(originalUser, tokenStorage.getSnapshot())
  store = useAuthStore()
  return store
}

function mountLogin() {
  const component = mountComponent<LoginState>(LoginView)
  mounted.push(component)
  component.state.formRef = { validate: vi.fn().mockResolvedValue(true) } as unknown as FormInstance
  Object.assign(component.state.form, credentials)
  store = useAuthStore()
  return component.state
}

function mountAccount() {
  const component = mountComponent<AccountState>(AccountManageView)
  mounted.push(component)
  component.state.formRef = { validate: vi.fn().mockResolvedValue(true) } as unknown as FormInstance
  Object.assign(component.state.form, passwords)
  return component.state
}

describe('认证操作与跨标签页会话归属', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mocks.route.query = {}
    mocks.replace.mockResolvedValue(undefined)
    mocks.confirm.mockResolvedValue('confirm')
    values.clear()
    tabWindow = new EventTarget()
    vi.stubGlobal('localStorage', local)
    vi.stubGlobal('window', tabWindow)
    vi.stubGlobal('crypto', { randomUUID: () => `identity-${++identity}` })
    setActivePinia(createPinia())
  })

  afterEach(() => {
    mounted.splice(0).forEach((component) => component.unmount())
    store?.$dispose()
    store = undefined
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it.each([undefined, '/admin/bookmarks'])('登录组件在真实 store 成功后跳转到 %s', async (redirect) => {
    if (redirect) mocks.route.query.redirect = redirect
    const response = deferred<LoginResult>()
    mocks.loginApi.mockReturnValue(response.promise)
    const view = mountLogin()

    const submit = view.submit()
    await vi.waitFor(() => expect(mocks.loginApi).toHaveBeenCalledOnce())
    expect(mocks.replace).not.toHaveBeenCalled()
    response.resolve({ token: 'accepted-login', user: originalUser })
    await submit

    expect(store!.isAuthenticated).toBe(true)
    expect(boundUserStorage.get(tokenStorage.getSnapshot())).toEqual(originalUser)
    expect(mocks.replace).toHaveBeenCalledExactlyOnceWith(redirect ?? '/admin')
    expect(mocks.success).toHaveBeenCalledExactlyOnceWith('登录成功，欢迎回来')
    expect(mocks.error).not.toHaveBeenCalled()
  })

  it.each([
    { outcome: 'success', deliverEvents: true },
    { outcome: 'failure', deliverEvents: true },
    { outcome: 'success', deliverEvents: false },
    { outcome: 'failure', deliverEvents: false },
  ])('旧登录 $outcome 不覆盖同 token 新会话（storage 事件送达：$deliverEvents）', async ({ outcome, deliverEvents }) => {
    const auth = startSession()
    const previous = tokenStorage.getSnapshot()!
    const response = deferred<LoginResult>()
    mocks.loginApi.mockReturnValue(response.promise)
    const view = mountLogin()
    const submit = view.submit()
    await vi.waitFor(() => expect(mocks.loginApi).toHaveBeenCalledOnce())
    const next = loginInOtherTab('shared-token', deliverEvents)
    expect(next.snapshot.token).toBe(previous.token)
    expect(next.snapshot.generation).not.toBe(previous.generation)

    if (outcome === 'success') response.resolve({ token: 'obsolete-login', user: originalUser })
    else response.reject(new Error('旧请求登录失败'))
    await submit

    expect(tokenStorage.getSnapshot()).toEqual(next.snapshot)
    expect(values).toEqual(next.records)
    expect(boundUserStorage.get(next.snapshot)).toEqual(otherUser)
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.loading).toBe(false)
    expect(mocks.replace).not.toHaveBeenCalled()
    expect(mocks.success).not.toHaveBeenCalled()
    expect(mocks.error).not.toHaveBeenCalled()
  })

  it('已观察到登录再退出后，不让更早的匿名登录响应恢复会话', async () => {
    store = useAuthStore()
    const response = deferred<LoginResult>()
    mocks.loginApi.mockReturnValue(response.promise)
    const pending = store.login(credentials)
    const other = loginInOtherTab()
    other.adapter.remove()
    dispatchStorage(AUTH_BARRIER_KEY)
    const loggedOutRecords = new Map(values)

    response.resolve({ token: 'obsolete-login', user: originalUser })
    await expect(pending).resolves.toBeUndefined()

    expect(tokenStorage.getSnapshot()).toBeNull()
    expect(values).toEqual(loggedOutRecords)
    expect(store.isAuthenticated).toBe(false)
    expect(store.loading).toBe(false)
  })

  it('保留持久化交错过程中另一标签页已提交的会话和绑定资料', async () => {
    const auth = startSession()
    mocks.loginApi.mockResolvedValue({ token: 'interrupted-login', user: originalUser })
    let next: ReturnType<typeof loginInOtherTab> | undefined
    vi.spyOn(local, 'setItem').mockImplementation((key: string, value: string) => {
      values.set(key, value)
      if (key === AUTH_ENVELOPE_KEY && value.includes('interrupted-login')) {
        next = loginInOtherTab('other-token', false)
      }
    })

    await expect(auth.login(credentials)).resolves.toBeUndefined()

    expect(next).toBeDefined()
    expect(tokenStorage.getSnapshot()).toEqual(next!.snapshot)
    expect(values).toEqual(next!.records)
    expect(boundUserStorage.get(next!.snapshot)).toEqual(otherUser)
    expect(auth.isAuthenticated).toBe(true)
  })

  it('写入刚完成便出现同 token 新会话时，不绑定旧资料或提示登录成功', async () => {
    const auth = startSession()
    mocks.loginApi.mockResolvedValue({ token: 'shared-token', user: originalUser })
    const originalSetSnapshot = tokenStorage.setSnapshot.bind(tokenStorage)
    let next: ReturnType<typeof loginInOtherTab> | undefined
    vi.spyOn(tokenStorage, 'setSnapshot').mockImplementation((token) => {
      const ownSnapshot = originalSetSnapshot(token)
      next = loginInOtherTab(token, false)
      expect(next.snapshot.token).toBe(ownSnapshot.token)
      expect(next.snapshot.generation).not.toBe(ownSnapshot.generation)
      return ownSnapshot
    })
    const view = mountLogin()

    await view.submit()

    expect(next).toBeDefined()
    expect(tokenStorage.getSnapshot()).toEqual(next!.snapshot)
    expect(values).toEqual(next!.records)
    expect(boundUserStorage.get(next!.snapshot)).toEqual(otherUser)
    expect(auth.user).not.toEqual(originalUser)
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.loading).toBe(false)
    expect(mocks.replace).not.toHaveBeenCalled()
    expect(mocks.success).not.toHaveBeenCalled()
    expect(mocks.error).not.toHaveBeenCalled()
  })

  it('自身登录持久化全部失败时仍清空本地会话并抛出类型化错误', async () => {
    const auth = startSession()
    mocks.loginApi.mockResolvedValue({ token: 'unpersisted-login', user: originalUser })
    vi.spyOn(local, 'setItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError') })

    await expect(auth.login(credentials)).rejects.toBeInstanceOf(TokenPersistenceError)

    expect(auth.token).toBe('')
    expect(auth.user).toBeNull()
    expect(auth.isAuthenticated).toBe(false)
    expect(auth.loading).toBe(false)
  })

  it.each([
    { action: 'password', deliverEvents: true },
    { action: 'logoutAll', deliverEvents: true },
    { action: 'password', deliverEvents: false },
    { action: 'logoutAll', deliverEvents: false },
  ])('旧 $action 成功不清除同 token 新会话或让组件跳转（事件送达：$deliverEvents）', async ({ action, deliverEvents }) => {
    const auth = startSession()
    const response = deferred<void>()
    const api = action === 'password' ? mocks.changePasswordApi : mocks.logoutAllApi
    api.mockReturnValue(response.promise)
    const view = mountAccount()
    const pending = action === 'password' ? view.submitPasswordChange() : view.logoutAllSessions()
    await vi.waitFor(() => expect(api).toHaveBeenCalledOnce())
    const next = loginInOtherTab('shared-token', deliverEvents)

    response.resolve(undefined)
    await pending

    expect(tokenStorage.getSnapshot()).toEqual(next.snapshot)
    expect(values).toEqual(next.records)
    expect(boundUserStorage.get(next.snapshot)).toEqual(otherUser)
    expect(auth.isAuthenticated).toBe(true)
    expect(view.changingPassword).toBe(false)
    expect(view.loggingOutAll).toBe(false)
    expect(mocks.replace).not.toHaveBeenCalled()
    expect(mocks.success).not.toHaveBeenCalled()
    expect(mocks.error).not.toHaveBeenCalled()
  })

  it.each(['password', 'logoutAll'])('当前会话的 %s 成功仍清除认证并提示重新登录', async (action) => {
    const auth = startSession()
    const response = deferred<void>()
    const api = action === 'password' ? mocks.changePasswordApi : mocks.logoutAllApi
    api.mockReturnValue(response.promise)
    const view = mountAccount()
    const pending = action === 'password' ? view.submitPasswordChange() : view.logoutAllSessions()
    await vi.waitFor(() => expect(api).toHaveBeenCalledOnce())

    response.resolve(undefined)
    await pending

    expect(tokenStorage.getSnapshot()).toBeNull()
    expect(auth.isAuthenticated).toBe(false)
    expect(values.has(USER_KEY)).toBe(false)
    expect(mocks.replace).toHaveBeenCalledExactlyOnceWith('/admin/login')
    expect(mocks.success).toHaveBeenCalledOnce()
    expect(mocks.error).not.toHaveBeenCalled()
  })
})
