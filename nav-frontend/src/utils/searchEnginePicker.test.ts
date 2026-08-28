import { describe, expect, it } from 'vitest'
import type { SearchEngine } from '@/types/searchEngine'
import {
  isSameSearchEngine,
  persistSearchEngineId,
  readPersistedSearchEngineId,
  resolveSearchEngineId,
  SEARCH_ENGINE_STORAGE_KEY,
  searchEngineIconUrl,
  searchEngineMark,
} from './searchEnginePicker'

function engine(overrides: Partial<SearchEngine> = {}): SearchEngine {
  return {
    id: 1,
    name: '百度',
    icon: '',
    searchUrl: 'https://www.baidu.com/s?wd={keyword}',
    placeholder: '百度一下，你就知道',
    isDefault: true,
    sortOrder: 10,
    ...overrides,
  }
}

describe('search engine picker presentation', () => {
  it('uses an HTTP(S) icon URL when configured', () => {
    const target = engine({ icon: 'https://example.com/google.svg' })

    expect(searchEngineIconUrl(target)).toBe('https://example.com/google.svg')
  })

  it('uses a short text icon instead of treating it as a URL', () => {
    const target = engine({ name: 'Google', icon: 'G' })

    expect(searchEngineIconUrl(target)).toBe('')
    expect(searchEngineMark(target)).toBe('G')
  })

  it('falls back to the first upper-case name character', () => {
    expect(searchEngineMark(engine({ name: 'bing', icon: '' }))).toBe('B')
    expect(searchEngineMark(engine({ name: '😀搜索', icon: '' }))).toBe('😀')
    expect(searchEngineMark(engine({ name: '旗帜', icon: '🇨🇳' }))).toBe('🇨🇳')
  })

  it('compares numeric and serialized ids consistently', () => {
    expect(isSameSearchEngine(3, '3')).toBe(true)
    expect(isSameSearchEngine(3, 'google')).toBe(false)
  })

  it('keeps the current valid selection before considering persisted/default choices', () => {
    const engines = [
      engine({ id: 1, name: '百度', isDefault: true }),
      engine({ id: 2, name: '必应', isDefault: false }),
    ]

    expect(resolveSearchEngineId(engines, '2', '1')).toBe(2)
    expect(resolveSearchEngineId(engines, 'missing', '2')).toBe(2)
    expect(resolveSearchEngineId(engines, 'missing', 'missing')).toBe(1)
    expect(resolveSearchEngineId([], 1, 1)).toBeNull()
  })

  it('persists a selection and tolerates unavailable browser storage', () => {
    const values = new Map<string, string>()
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
    }

    persistSearchEngineId(storage, 7)

    expect(values.get(SEARCH_ENGINE_STORAGE_KEY)).toBe('7')
    expect(readPersistedSearchEngineId(storage)).toBe('7')
    expect(readPersistedSearchEngineId({
      getItem: () => { throw new Error('denied') },
      setItem: () => undefined,
    })).toBe('')
  })
})
