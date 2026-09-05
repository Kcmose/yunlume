import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import type { SearchEngine } from '@/types/searchEngine'
import { deferred, mountComponent } from '@/test/componentHarness'
import { SEARCH_ENGINE_STORAGE_KEY } from '@/utils/searchEnginePicker'
import PortalHome from './PortalHome.vue'

const mocks = vi.hoisted(() => ({ engines: vi.fn() }))
vi.mock('@/api/public.api', () => ({ getPublicSearchEngines: mocks.engines }))
vi.mock('@/utils/publicRequestRetry', () => ({
  withPublicRequestRetry: (operation: () => Promise<unknown>) => operation(),
}))
vi.mock('@/composables/useSiteConfig', async () => {
  const { ref } = await import('vue')
  return { useSiteConfig: () => ({
    config: ref({ siteName: 'Site', siteDescription: '', backgroundColor: '#000000' }),
    loading: ref(false), usingFallback: ref(false), fetchConfig: vi.fn().mockResolvedValue(undefined),
  }) }
})
vi.mock('@/composables/useBookmarks', async () => {
  const { ref } = await import('vue')
  return { useBookmarks: () => ({
    categories: ref([]), loading: ref(false), usingFallback: ref(false),
    fetchNavigation: vi.fn().mockResolvedValue(undefined),
  }) }
})
vi.mock('@/composables/useTheme', async () => {
  const { ref } = await import('vue')
  return { useTheme: () => ({ themeStyle: ref({}) }) }
})
vi.mock('@/composables/useSearch', async () => {
  const { ref } = await import('vue')
  return { useSearch: () => ({
    keyword: ref(''), filteredCategories: ref([]), submitSearch: vi.fn(), clearSearch: vi.fn(),
  }) }
})

interface PortalState {
  activeEngineId: SearchEngine['id']
  searchEngines: SearchEngine[]
  searchEnginesLoading: boolean
  searchEnginesUsingFallback: boolean
  fetchSearchEngines(): Promise<void>
  selectSearchEngine(id: SearchEngine['id']): void
}
const cleanup: Array<() => void> = []
const stored = new Map<string, string>()
const storage = {
  getItem: (key: string) => stored.get(key) ?? null,
  setItem: vi.fn((key: string, value: string) => { stored.set(key, value) }),
}
const engine = (id: number, isDefault = false): SearchEngine => ({
  id, name: 'Engine ' + id, icon: '', searchUrl: 'https://example.com/?q={keyword}',
  placeholder: '', isDefault, sortOrder: id,
})
const settle = async () => { await new Promise<void>((resolve) => setImmediate(resolve)); await nextTick() }
function mount() {
  const instance = mountComponent<PortalState>(PortalHome)
  cleanup.push(instance.unmount)
  return instance
}

beforeEach(() => {
  mocks.engines.mockReset()
  stored.clear()
  storage.setItem.mockClear()
  vi.stubGlobal('window', {
    localStorage: storage, innerWidth: 1200, innerHeight: 800,
    addEventListener: vi.fn(), removeEventListener: vi.fn(),
  })
  vi.stubGlobal('document', { title: 'Original', querySelector: () => null })
})
afterEach(() => {
  cleanup.splice(0).forEach((unmount) => unmount())
  vi.unstubAllGlobals()
})

describe('portal search request ownership', () => {
  it('does not let an unmounted page overwrite the replacement page preference', async () => {
    const oldRequest = deferred<SearchEngine[]>()
    mocks.engines.mockReturnValueOnce(oldRequest.promise).mockResolvedValueOnce([engine(1, true), engine(2)])
    const oldPage = mount()
    oldPage.unmount()
    const newPage = mount()
    await settle()
    newPage.state.selectSearchEngine(2)
    expect(stored.get(SEARCH_ENGINE_STORAGE_KEY)).toBe('2')
    const writes = storage.setItem.mock.calls.length

    oldRequest.resolve([engine(1, true)])
    await settle()

    expect(stored.get(SEARCH_ENGINE_STORAGE_KEY)).toBe('2')
    expect(newPage.state.activeEngineId).toBe(2)
    expect(storage.setItem).toHaveBeenCalledTimes(writes)
  })

  it('keeps a user selection made while a refresh is pending', async () => {
    mocks.engines.mockResolvedValueOnce([engine(1, true), engine(2)])
    const page = mount()
    await settle()
    const refresh = deferred<SearchEngine[]>()
    mocks.engines.mockReturnValueOnce(refresh.promise)
    const pending = page.state.fetchSearchEngines()
    page.state.selectSearchEngine(2)
    const writes = storage.setItem.mock.calls.length
    refresh.resolve([engine(1, true), engine(2)])
    await pending

    expect(page.state.activeEngineId).toBe(2)
    expect(stored.get(SEARCH_ENGINE_STORAGE_KEY)).toBe('2')
    expect(storage.setItem).toHaveBeenCalledTimes(writes)
  })

  it('does not replace a newer refresh or its loading state with an older response', async () => {
    const first = deferred<SearchEngine[]>()
    const second = deferred<SearchEngine[]>()
    mocks.engines.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const page = mount()
    const pending = page.state.fetchSearchEngines()
    first.resolve([engine(1, true)])
    await settle()
    expect(page.state.searchEnginesLoading).toBe(true)
    expect(storage.setItem).not.toHaveBeenCalled()
    second.resolve([engine(2, true)])
    await pending
    expect(page.state.searchEngines.map((item) => item.id)).toEqual([2])
    expect(stored.get(SEARCH_ENGINE_STORAGE_KEY)).toBe('2')
    expect(page.state.searchEnginesLoading).toBe(false)
  })

  it('ignores an old rejection after a newer refresh succeeded', async () => {
    const first = deferred<SearchEngine[]>()
    mocks.engines.mockReturnValueOnce(first.promise).mockResolvedValueOnce([engine(2, true)])
    const page = mount()
    await page.state.fetchSearchEngines()
    first.reject(new Error('old request failed'))
    await settle()

    expect(page.state.searchEnginesUsingFallback).toBe(false)
    expect(page.state.activeEngineId).toBe(2)
    expect(stored.get(SEARCH_ENGINE_STORAGE_KEY)).toBe('2')
  })
})
