import { describe, expect, it } from 'vitest'
import { fallbackSiteConfig } from '@/data/fallback'
import { getBackgroundConfigSnapshot, hasBackgroundConfigChanged } from './backgroundConfig'

describe('background config state', () => {
  it('treats the loaded background as saved', () => {
    const saved = getBackgroundConfigSnapshot(fallbackSiteConfig)

    expect(hasBackgroundConfigChanged(fallbackSiteConfig, saved)).toBe(false)
  })

  it('detects an uploaded image that has not been applied', () => {
    const saved = getBackgroundConfigSnapshot(fallbackSiteConfig)
    const changed = {
      ...fallbackSiteConfig,
      backgroundType: 'image' as const,
      backgroundImage: '/uploads/backgrounds/desktop.jpg',
    }

    expect(hasBackgroundConfigChanged(changed, saved)).toBe(true)
  })

  it('returns to saved after the persisted response is recorded', () => {
    const persisted = {
      ...fallbackSiteConfig,
      backgroundType: 'image' as const,
      backgroundImage: '/uploads/backgrounds/desktop.jpg',
    }
    const saved = getBackgroundConfigSnapshot(persisted)

    expect(hasBackgroundConfigChanged(persisted, saved)).toBe(false)
  })
})
