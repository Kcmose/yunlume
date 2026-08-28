import { describe, expect, it } from 'vitest'
import { fallbackNavigation } from '@/data/fallback'
import { filterNavigation, firstSearchMatch } from './search'

describe('navigation search', () => {
  it('filters bookmarks by name and description', () => {
    const result = filterNavigation(fallbackNavigation, '代码托管')
    expect(result).toHaveLength(1)
    expect(result[0]?.bookmarks[0]?.name).toBe('GitHub')
  })

  it('returns the first matching bookmark', () => {
    expect(firstSearchMatch(fallbackNavigation, 'Figma')?.url).toBe('https://www.figma.com')
  })

  it('returns all categories for empty input', () => {
    expect(filterNavigation(fallbackNavigation, '')).toHaveLength(fallbackNavigation.length)
  })
})
