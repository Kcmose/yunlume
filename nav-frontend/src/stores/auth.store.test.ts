import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { AdminUser } from '@/types/auth'

const cachedUser: AdminUser = {
  id: 1,
  username: 'admin',
  nickname: '管理员',
  role: 'ADMIN',
}

const apiMocks = vi.hoisted(() => ({
  loginApi: vi.fn(),
  profileApi: vi.fn(),
  logoutApi: vi.fn(),
}))
const storageMocks = vi.hoisted(() => ({
  tokenGet: vi.fn(),
  tokenSnapshot: vi.fn(),
  tokenSet: vi.fn(),
  tokenRemove: vi.fn(),
  jsonGet: vi.fn(),
  jsonSet: vi.fn(),
  jsonRemove: vi.fn(),
  subscribe: vi.fn(),
}))

vi.mock('@/api/auth.api', () => ({
  changePasswordApi: vi.fn(),
  loginApi: apiMocks.loginApi,
  logoutAllApi: vi.fn(),
  logoutApi: apiMocks.logoutApi,
  profileApi: apiMocks.profileApi,
}))

vi.mock('@/utils/storage', () => ({
  USER_KEY: 'admin-user',
  AUTH_ENVELOPE_KEY: 'auth-envelope',
  AUTH_BARRIER_KEY: 'auth-barrier',
  tokenStorage: {
    get: storageMocks.tokenGet,
    getSnapshot: storageMocks.tokenSnapshot,
    set: storageMocks.tokenSet,
    remove: storageMocks.tokenRemove,
  },
  boundUserStorage: {
    get: storageMocks.jsonGet,
    set: storageMocks.jsonSet,
    remove: storageMocks.jsonRemove,
  },
  subscribeAuthStorage: storageMocks.subscribe,
}))

import { useAuthStore } from './auth.store'

describe('auth profile reliability', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.loginApi.mockReset()
    apiMocks.profileApi.mockReset()
    apiMocks.logoutApi.mockReset()
    storageMocks.tokenGet.mockReturnValue('saved-token')
    storageMocks.tokenSnapshot.mockImplementation(() => {
      const token = storageMocks.tokenGet()
      return token ? { token, generation: `${token}-generation`, commitment: `${token}-commitment` } : null
    })
    storageMocks.tokenSet.mockImplementation((token: string) => {
      storageMocks.tokenGet.mockReturnValue(token)
      return true
    })
    storageMocks.jsonGet.mockReturnValue(cachedUser)
    storageMocks.subscribe.mockReturnValue(() => undefined)
    setActivePinia(createPinia())
  })

  it('does not load stale cached user metadata without an accepted durable token', () => {
    storageMocks.tokenGet.mockReturnValue('')
    const store = useAuthStore()

    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(storageMocks.jsonGet).not.toHaveBeenCalled()
    expect(storageMocks.jsonRemove).toHaveBeenCalledWith()
  })

  it.each([
    ['network failure', new Error('network unavailable')],
    ['server failure', Object.assign(new Error('temporary failure'), { status: 503 })],
  ])('preserves the cached session on %s', async (_label, failure) => {
    apiMocks.profileApi.mockRejectedValue(failure)
    const store = useAuthStore()

    await store.fetchProfile(true)
    await store.fetchProfile()

    expect(store.token).toBe('saved-token')
    expect(store.user).toEqual(cachedUser)
    expect(storageMocks.tokenRemove).not.toHaveBeenCalled()
    expect(storageMocks.jsonRemove).not.toHaveBeenCalled()
    expect(apiMocks.profileApi).toHaveBeenCalledOnce()
  })

  it('keeps the token but returns no profile when the first verification is temporarily unavailable', async () => {
    storageMocks.jsonGet.mockReturnValue(null)
    apiMocks.profileApi.mockRejectedValue(Object.assign(new Error('temporary failure'), { status: 503 }))
    const store = useAuthStore()

    const first = await store.fetchProfile(true)
    const second = await store.fetchProfile()

    expect(first).toBeNull()
    expect(second).toBeNull()
    expect(store.token).toBe('saved-token')
    expect(store.user).toBeNull()
    expect(apiMocks.profileApi).toHaveBeenCalledOnce()
    expect(storageMocks.tokenRemove).not.toHaveBeenCalled()
  })

  it.each([401, 403])('clears the session only for an authentication failure (%s)', async (status) => {
    apiMocks.profileApi.mockRejectedValue(Object.assign(new Error('invalid session'), { status }))
    const store = useAuthStore()

    await store.fetchProfile(true)

    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(storageMocks.tokenRemove).toHaveBeenCalledOnce()
    expect(storageMocks.jsonRemove).toHaveBeenCalledOnce()
  })

  it('deduplicates concurrent requests and reuses a recently verified profile', async () => {
    let resolveProfile!: (user: AdminUser) => void
    apiMocks.profileApi.mockReturnValue(new Promise<AdminUser>((resolve) => {
      resolveProfile = resolve
    }))
    const store = useAuthStore()

    const first = store.fetchProfile(true)
    const second = store.fetchProfile(true)
    resolveProfile(cachedUser)
    await Promise.all([first, second])
    await store.fetchProfile()

    expect(apiMocks.profileApi).toHaveBeenCalledOnce()
    expect(store.user).toEqual(cachedUser)
    expect(store.profileLastAttemptAt).toBeGreaterThan(0)
  })

  it('fails closed before returning a fresh cached profile when durable authority was removed', async () => {
    const store = useAuthStore()
    store.profileLastAttemptAt = Date.now()
    storageMocks.tokenGet.mockReturnValue('')

    expect(await store.fetchProfile()).toBeNull()
    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(apiMocks.profileApi).not.toHaveBeenCalled()
  })

  it('continuously reconciles two-tab storage events and clears stale user metadata', () => {
    const store = useAuthStore()
    const listener = storageMocks.subscribe.mock.calls.at(-1)?.[0]
    expect(listener).toBeTypeOf('function')

    storageMocks.tokenGet.mockReturnValue('new-token')
    storageMocks.jsonGet.mockReturnValue({ ...cachedUser, nickname: 'other tab' })
    listener({ key: 'auth-barrier', token: 'new-token' })
    expect(store.token).toBe('new-token')
    expect(store.user).toBeNull()
    expect(store.profileLastAttemptAt).toBe(0)

    listener({ key: 'admin-user', token: 'new-token' })
    expect(store.user?.nickname).toBe('other tab')

    storageMocks.tokenGet.mockReturnValue('')
    listener({ key: 'auth-envelope', token: 'forged-event-value' })
    expect(store.token).toBe('')
    expect(store.user).toBeNull()

    storageMocks.jsonGet.mockReturnValue(cachedUser)
    listener({ key: 'admin-user', token: '' })
    expect(store.user).toBeNull()
  })

  it('reports unauthenticated whenever memory differs from current durable authority', () => {
    const store = useAuthStore()
    expect(store.isAuthenticated).toBe(true)
    storageMocks.tokenGet.mockReturnValue('other-token')
    expect(store.isAuthenticated).toBe(false)
  })

  it('reports unauthenticated when the token text is reused by a different operation identity', () => {
    const store = useAuthStore()
    expect(store.isAuthenticated).toBe(true)
    storageMocks.tokenSnapshot.mockReturnValue({
      token: 'saved-token',
      generation: 'replacement-generation',
      commitment: 'replacement-commitment',
    })
    expect(store.isAuthenticated).toBe(false)
  })

  it('installs one synchronization subscription per Pinia store and removes it on disposal', () => {
    const stop = vi.fn()
    storageMocks.subscribe.mockReturnValue(stop)
    const first = useAuthStore()
    const second = useAuthStore()

    expect(second).toBe(first)
    expect(storageMocks.subscribe).toHaveBeenCalledOnce()
    first.$dispose()
    expect(stop).toHaveBeenCalledOnce()
  })

  it('completes local logout when server revocation is unavailable', async () => {
    apiMocks.logoutApi.mockRejectedValue(new Error('offline'))
    const store = useAuthStore()

    await expect(store.logout()).resolves.toBeUndefined()

    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(storageMocks.tokenRemove).toHaveBeenCalledOnce()
    expect(storageMocks.jsonRemove).toHaveBeenCalledOnce()
  })

  it('clears UI and durable state synchronously while server logout remains pending', async () => {
    let resolveLogout!: () => void
    apiMocks.logoutApi.mockReturnValue(new Promise<void>((resolve) => { resolveLogout = resolve }))
    const store = useAuthStore()

    const pending = store.logout()

    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(storageMocks.tokenRemove).toHaveBeenCalledOnce()
    expect(storageMocks.jsonRemove).toHaveBeenCalledOnce()
    expect(apiMocks.logoutApi).toHaveBeenCalledWith('saved-token')
    resolveLogout()
    await expect(pending).resolves.toBeUndefined()
  })

  it('persists a successful login before exposing token or user in memory', async () => {
    apiMocks.loginApi.mockResolvedValue({ token: 'new-token', user: cachedUser })
    storageMocks.tokenSet.mockImplementation(() => {
      const store = useAuthStore()
      expect(store.token).toBe('saved-token')
      expect(store.user).toEqual(cachedUser)
      storageMocks.tokenGet.mockReturnValue('new-token')
      return true
    })
    const store = useAuthStore()

    await store.login({ username: 'admin', password: 'secret' })

    expect(store.token).toBe('new-token')
    expect(store.user).toEqual(cachedUser)
    expect(storageMocks.jsonSet).toHaveBeenCalledWith(cachedUser, {
      token: 'new-token',
      generation: 'new-token-generation',
      commitment: 'new-token-commitment',
    })
  })

  it('leaves no memory authentication and exposes the explicit persistence error when login storage fails', async () => {
    const persistenceError = Object.assign(new Error('Unable to persist the authenticated session'), {
      name: 'TokenPersistenceError',
      code: 'AUTH_PERSISTENCE_FAILED',
    })
    apiMocks.loginApi.mockResolvedValue({ token: 'new-token', user: cachedUser })
    storageMocks.tokenSet.mockImplementation(() => { throw persistenceError })
    storageMocks.tokenGet.mockReturnValue('')
    storageMocks.jsonGet.mockReturnValue(null)
    const store = useAuthStore()

    await expect(store.login({ username: 'admin', password: 'secret' })).rejects.toBe(persistenceError)

    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(store.profileLastAttemptAt).toBe(0)
    expect(storageMocks.jsonSet).not.toHaveBeenCalled()
  })

  it('clears a populated session and preserves the typed error when relogin persistence fails', async () => {
    const persistenceError = Object.assign(new Error('Unable to persist the authenticated session'), {
      name: 'TokenPersistenceError',
      code: 'AUTH_PERSISTENCE_FAILED',
      phase: 'active-envelope-write',
    })
    apiMocks.loginApi.mockResolvedValue({ token: 'new-token', user: cachedUser })
    storageMocks.tokenSet.mockImplementation(() => { throw persistenceError })
    const store = useAuthStore()
    store.profileLastAttemptAt = 123

    await expect(store.login({ username: 'admin', password: 'secret' })).rejects.toBe(persistenceError)

    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(store.profileLastAttemptAt).toBe(0)
    expect(storageMocks.tokenRemove).toHaveBeenCalledOnce()
    expect(storageMocks.jsonRemove).toHaveBeenCalledWith()
    expect(storageMocks.jsonSet).not.toHaveBeenCalled()
  })

  it('clears an existing session on login API rejection and rethrows the original failure', async () => {
    const apiFailure = new Error('bad credentials')
    apiMocks.loginApi.mockRejectedValue(apiFailure)
    const store = useAuthStore()
    store.profileLastAttemptAt = 123

    await expect(store.login({ username: 'admin', password: 'wrong' })).rejects.toBe(apiFailure)

    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(store.profileLastAttemptAt).toBe(0)
    expect(storageMocks.tokenRemove).toHaveBeenCalledOnce()
    expect(storageMocks.jsonRemove).toHaveBeenCalledWith()
  })

  it('does not let an older failed login erase a newer successful session', async () => {
    let rejectFirst!: (error: Error) => void
    let resolveSecond!: (value: { token: string; user: AdminUser }) => void
    apiMocks.loginApi
      .mockReturnValueOnce(new Promise((_resolve, reject) => { rejectFirst = reject }))
      .mockReturnValueOnce(new Promise((resolve) => { resolveSecond = resolve }))
    const store = useAuthStore()

    const first = store.login({ username: 'admin', password: 'old' })
    const second = store.login({ username: 'admin', password: 'new' })
    resolveSecond({ token: 'new-token', user: cachedUser })
    await second
    rejectFirst(new Error('stale failure'))
    await expect(first).resolves.toBeUndefined()

    expect(store.token).toBe('new-token')
    expect(store.user).toEqual(cachedUser)
    expect(storageMocks.tokenRemove).not.toHaveBeenCalled()
  })

  it('still clears login memory and rethrows the API failure when every logout write fails', async () => {
    const apiFailure = new Error('offline')
    apiMocks.loginApi.mockRejectedValue(apiFailure)
    storageMocks.tokenRemove.mockImplementation(() => { throw new Error('all persistent writes blocked') })
    storageMocks.jsonRemove.mockImplementation(() => { throw new Error('all persistent writes blocked') })
    const store = useAuthStore()

    await expect(store.login({ username: 'admin', password: 'wrong' })).rejects.toBe(apiFailure)
    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(store.profileLastAttemptAt).toBe(0)
  })

  it('keeps logout nonblocking when durable local logout fails', async () => {
    apiMocks.logoutApi.mockResolvedValue(undefined)
    storageMocks.tokenRemove.mockImplementation(() => { throw new Error('storage blocked') })
    const store = useAuthStore()

    await expect(store.logout()).resolves.toBeUndefined()

    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(storageMocks.jsonRemove).toHaveBeenCalledOnce()
  })
})
