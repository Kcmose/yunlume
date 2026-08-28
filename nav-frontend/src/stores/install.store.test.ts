import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { InstallStatus } from '@/types/install'

const apiMocks = vi.hoisted(() => ({
  getInstallStatusApi: vi.fn(),
}))

vi.mock('@/api/install.api', () => ({
  getInstallStatusApi: apiMocks.getInstallStatusApi,
}))

import { useInstallStore } from './install.store'

const requiredStatus: InstallStatus = {
  state: 'REQUIRED',
  installationRequired: true,
  webInstallEnabled: true,
  ready: true,
}

describe('installation status store', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.getInstallStatusApi.mockReset()
    setActivePinia(createPinia())
  })

  it('does not invent an installation-required state after a status failure', async () => {
    apiMocks.getInstallStatusApi.mockRejectedValue(new Error('network unavailable'))
    const store = useInstallStore()

    const status = await store.fetchStatus()

    expect(status).toBeNull()
    expect(store.status).toBeNull()
    expect(store.error).toBe('network unavailable')
  })

  it('deduplicates concurrent checks and caches a recent successful response', async () => {
    let resolveStatus!: (status: InstallStatus) => void
    apiMocks.getInstallStatusApi.mockReturnValue(new Promise<InstallStatus>((resolve) => {
      resolveStatus = resolve
    }))
    const store = useInstallStore()

    const first = store.fetchStatus()
    const second = store.fetchStatus()
    resolveStatus(requiredStatus)
    await Promise.all([first, second])
    await store.fetchStatus()

    expect(apiMocks.getInstallStatusApi).toHaveBeenCalledOnce()
    expect(store.status).toEqual(requiredStatus)
  })

  it('preserves the last confirmed state across a transient refresh failure', async () => {
    apiMocks.getInstallStatusApi
      .mockResolvedValueOnce(requiredStatus)
      .mockRejectedValueOnce(new Error('temporary failure'))
    const store = useInstallStore()

    await store.fetchStatus()
    const fallback = await store.fetchStatus(true)

    expect(fallback).toEqual(requiredStatus)
    expect(store.status).toEqual(requiredStatus)
    expect(store.error).toBe('temporary failure')
  })

  it('marks completion without retaining infrastructure checks', () => {
    const store = useInstallStore()
    store.status = requiredStatus

    store.markInstalled()

    expect(store.status).toEqual({
      state: 'COMPLETED',
      installationRequired: false,
      webInstallEnabled: false,
      ready: true,
    })
  })
})
