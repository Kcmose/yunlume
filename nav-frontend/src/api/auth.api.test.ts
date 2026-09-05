import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AxiosRequestConfig } from 'axios'

const storageMocks = vi.hoisted(() => ({
  get: vi.fn(),
  getSnapshot: vi.fn(),
}))

vi.mock('@/utils/storage', () => ({
  tokenStorage: { get: storageMocks.get, getSnapshot: storageMocks.getSnapshot },
}))

import request from './request'
import { logoutApi } from './auth.api'

describe('captured-token logout isolation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    storageMocks.get.mockReturnValue('new-cross-tab-token')
    storageMocks.getSnapshot.mockReturnValue({
      token: 'new-cross-tab-token',
      generation: 'new-generation',
      commitment: 'new-commitment',
    })
  })

  it('keeps the explicitly captured old token authoritative after a concurrent login', async () => {
    let observedAuthorization: unknown
    const adapter: NonNullable<AxiosRequestConfig['adapter']> = async (config) => {
      observedAuthorization = config.headers?.get('Authorization')
      return { data: { code: 0, data: undefined }, status: 200, statusText: 'OK', headers: {}, config }
    }

    await logoutApi('captured-old-token', adapter)

    expect(observedAuthorization).toBe('Bearer captured-old-token')
  })

  it('still derives current durable authorization for normal protected requests', async () => {
    let observedAuthorization: unknown
    await request.get('/admin/auth/profile', {
      adapter: async (config) => {
        observedAuthorization = config.headers.get('Authorization')
        return { data: {}, status: 200, statusText: 'OK', headers: {}, config }
      },
    })
    expect(observedAuthorization).toBe('Bearer new-cross-tab-token')
  })

  it('deletes incidental stale authorization when durable auth is absent', async () => {
    storageMocks.get.mockReturnValue('')
    storageMocks.getSnapshot.mockReturnValue(null)
    let observedAuthorization: unknown
    await request.get('/admin/auth/profile', {
      headers: { Authorization: 'Bearer incidental-stale-token' },
      adapter: async (config) => {
        observedAuthorization = config.headers.get('Authorization')
        return { data: {}, status: 200, statusText: 'OK', headers: {}, config }
      },
    })
    expect(observedAuthorization).toBeUndefined()
  })

  it('sanitizes every logout rejection without retaining config, headers, or token recursively', async () => {
    const token = 'captured-secret-never-exposed'
    const rejection = Object.assign(new Error(`network failed ${token}`), {
      config: { headers: { Authorization: `Bearer ${token}` } },
      nested: { token },
    })
    let failure: unknown
    try {
      await logoutApi(token, async () => Promise.reject(rejection))
    } catch (error) {
      failure = error
    }

    expect(failure).toEqual(expect.objectContaining({ name: 'LogoutRequestError' }))
    const inspect = (value: unknown, seen = new Set<unknown>()): string[] => {
      if (value === null || (typeof value !== 'object' && typeof value !== 'function')) return [String(value)]
      if (seen.has(value)) return []
      seen.add(value)
      return Object.getOwnPropertyNames(value).flatMap((key) => [
        key,
        ...inspect(Object.getOwnPropertyDescriptor(value, key)?.value, seen),
      ])
    }
    const exposed = inspect(failure).join('\n')
    expect(exposed).not.toContain(token)
    expect(exposed).not.toContain('Authorization')
    expect(exposed).not.toContain('config')
  })
})
