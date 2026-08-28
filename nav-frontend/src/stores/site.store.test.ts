import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { fallbackSiteConfig } from '@/data/fallback'
import type { SiteConfig } from '@/types/site'

const apiMocks = vi.hoisted(() => ({
  getPublicSiteConfig: vi.fn(),
}))

vi.mock('@/api/public.api', () => ({
  getPublicSiteConfig: apiMocks.getPublicSiteConfig,
}))

import { useSiteStore } from './site.store'

const serverConfig: SiteConfig = {
  ...fallbackSiteConfig,
  siteName: 'Saved navigation',
  backgroundType: 'image',
  backgroundImage: '/uploads/backgrounds/saved.jpg',
}

describe('site store public loading', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMocks.getPublicSiteConfig.mockReset()
  })

  it('preserves a confirmed server config when a later refresh fails', async () => {
    const store = useSiteStore()
    apiMocks.getPublicSiteConfig.mockResolvedValueOnce(serverConfig)
    await store.fetchConfig()

    apiMocks.getPublicSiteConfig.mockRejectedValueOnce(Object.assign(new Error('failed'), { status: 500 }))
    await store.fetchConfig()

    expect(store.config).toEqual(serverConfig)
    expect(store.hasRemoteConfig).toBe(true)
    expect(store.usingFallback).toBe(false)
    expect(store.loading).toBe(false)
  })

  it('does not let an older failed request overwrite a newer success', async () => {
    const store = useSiteStore()
    let rejectOlder!: (reason: unknown) => void
    const olderRequest = new Promise<SiteConfig>((_resolve, reject) => {
      rejectOlder = reject
    })
    apiMocks.getPublicSiteConfig
      .mockReturnValueOnce(olderRequest)
      .mockResolvedValueOnce(serverConfig)

    const olderLoad = store.fetchConfig()
    const newerLoad = store.fetchConfig()
    await newerLoad
    rejectOlder(Object.assign(new Error('late failure'), { status: 500 }))
    await olderLoad

    expect(store.config).toEqual(serverConfig)
    expect(store.hasRemoteConfig).toBe(true)
    expect(store.usingFallback).toBe(false)
    expect(store.loading).toBe(false)
  })

  it('uses the bundled fallback only when no server config has resolved', async () => {
    const store = useSiteStore()
    apiMocks.getPublicSiteConfig.mockRejectedValueOnce(Object.assign(new Error('failed'), { status: 500 }))

    await store.fetchConfig()

    expect(store.config).toEqual(fallbackSiteConfig)
    expect(store.hasRemoteConfig).toBe(false)
    expect(store.usingFallback).toBe(true)
  })
})
