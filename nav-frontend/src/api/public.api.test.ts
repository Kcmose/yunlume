import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMocks = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('./request', () => ({
  default: requestMocks,
  unwrapApiData: (response: { data: unknown }) => response.data,
}))

import {
  getPublicNavigation,
  getPublicSearchEngines,
  getPublicSiteConfig,
} from './public.api'

describe('public API first-screen timeout', () => {
  beforeEach(() => {
    requestMocks.get.mockReset().mockResolvedValue({ data: [] })
  })

  it('limits every public request attempt to three seconds', async () => {
    await Promise.all([
      getPublicSiteConfig(),
      getPublicNavigation(),
      getPublicSearchEngines(),
    ])

    expect(requestMocks.get.mock.calls).toEqual([
      ['/public/site-config', { timeout: 3000 }],
      ['/public/navigation', { timeout: 3000 }],
      ['/public/search-engines', { timeout: 3000 }],
    ])
  })
})
