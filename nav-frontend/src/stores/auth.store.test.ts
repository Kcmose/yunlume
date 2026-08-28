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
  profileApi: vi.fn(),
}))
const storageMocks = vi.hoisted(() => ({
  tokenGet: vi.fn(),
  tokenSet: vi.fn(),
  tokenRemove: vi.fn(),
  jsonGet: vi.fn(),
  jsonSet: vi.fn(),
  jsonRemove: vi.fn(),
}))

vi.mock('@/api/auth.api', () => ({
  changePasswordApi: vi.fn(),
  loginApi: vi.fn(),
  logoutAllApi: vi.fn(),
  logoutApi: vi.fn(),
  profileApi: apiMocks.profileApi,
}))

vi.mock('@/utils/storage', () => ({
  USER_KEY: 'admin-user',
  tokenStorage: {
    get: storageMocks.tokenGet,
    set: storageMocks.tokenSet,
    remove: storageMocks.tokenRemove,
  },
  jsonStorage: {
    get: storageMocks.jsonGet,
    set: storageMocks.jsonSet,
    remove: storageMocks.jsonRemove,
  },
}))

import { useAuthStore } from './auth.store'

describe('auth profile reliability', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.profileApi.mockReset()
    storageMocks.tokenGet.mockReturnValue('saved-token')
    storageMocks.jsonGet.mockReturnValue(cachedUser)
    setActivePinia(createPinia())
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
})
