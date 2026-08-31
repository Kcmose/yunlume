import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { NavigationCategory } from '@/types/category'

const apiMocks = vi.hoisted(() => ({
  getPublicNavigation: vi.fn(),
}))

vi.mock('@/api/public.api', () => ({
  getPublicNavigation: apiMocks.getPublicNavigation,
}))

vi.mock('@/utils/publicRequestRetry', () => ({
  withPublicRequestRetry: <T>(request: () => Promise<T>) => request(),
}))

import { useNavigationStore } from './navigation.store'

const remoteNavigation: NavigationCategory[] = [{
  id: 99,
  name: '远程分类',
  icon: '远',
  sortOrder: 1,
  visible: true,
  bookmarks: [{
    id: 991,
    categoryId: 99,
    name: '远程书签',
    url: 'https://example.com',
    icon: '远',
    description: '',
    sortOrder: 1,
    isRecommend: false,
    isExternal: true,
    visible: true,
  }],
}]

describe('navigation first-screen reliability', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.getPublicNavigation.mockReset()
    setActivePinia(createPinia())
  })

  it('does not expose bundled demo categories before the first remote request settles', () => {
    const store = useNavigationStore()

    expect(store.usingFallback).toBe(false)
    expect(store.visibleCategories).toEqual([])
  })

  it('replaces the fallback after a successful request', async () => {
    let resolveNavigation!: (value: NavigationCategory[]) => void
    apiMocks.getPublicNavigation.mockReturnValue(new Promise<NavigationCategory[]>((resolve) => {
      resolveNavigation = resolve
    }))
    const store = useNavigationStore()

    const request = store.fetchNavigation()
    expect(store.loading).toBe(true)
    expect(store.visibleCategories).toEqual([])

    resolveNavigation(remoteNavigation)
    await request

    expect(store.categories).toEqual(remoteNavigation)
    expect(store.usingFallback).toBe(false)
    expect(store.hasRemoteNavigation).toBe(true)
  })

  it('keeps the last successful navigation during a later failure', async () => {
    apiMocks.getPublicNavigation
      .mockResolvedValueOnce(remoteNavigation)
      .mockRejectedValueOnce(new Error('offline'))
    const store = useNavigationStore()

    await store.fetchNavigation()
    await store.fetchNavigation()

    expect(store.categories).toEqual(remoteNavigation)
    expect(store.usingFallback).toBe(false)
  })

  it('marks the bundled content as degraded only after the initial request fails', async () => {
    apiMocks.getPublicNavigation.mockRejectedValue(new Error('offline'))
    const store = useNavigationStore()

    await store.fetchNavigation()

    expect(store.usingFallback).toBe(true)
    expect(store.visibleCategories.length).toBeGreaterThan(0)
  })
})
