import { describe, expect, it } from 'vitest'
import { fallbackSiteConfig } from '@/data/fallback'
import {
  createSiteConfigUpdatePayload,
  getSiteConfigSnapshot,
  getSiteConfigValidationError,
  hasSiteConfigChanged,
} from './siteConfigState'

describe('site config reliability state', () => {
  it('tracks changes across the complete editable configuration', () => {
    const saved = getSiteConfigSnapshot(fallbackSiteConfig)

    expect(hasSiteConfigChanged(fallbackSiteConfig, saved)).toBe(false)
    expect(hasSiteConfigChanged({
      ...fallbackSiteConfig,
      messageText: '新的公告',
    }, saved)).toBe(true)
    expect(hasSiteConfigChanged({
      ...fallbackSiteConfig,
      topContentEnabled: !fallbackSiteConfig.topContentEnabled,
    }, saved)).toBe(true)
  })

  it('creates an optimistic update payload without server identity fields', () => {
    const payload = createSiteConfigUpdatePayload({
      ...fallbackSiteConfig,
      id: 8,
      version: 12,
    })

    expect(payload.expectedVersion).toBe(12)
    expect(payload).not.toHaveProperty('id')
    expect(payload).not.toHaveProperty('version')
    expect(payload.siteName).toBe(fallbackSiteConfig.siteName)
  })

  it('requires a PC image in image mode while allowing a mobile fallback', () => {
    expect(getSiteConfigValidationError({
      ...fallbackSiteConfig,
      backgroundType: 'image',
      backgroundImage: '  ',
      mobileBackgroundImage: '/uploads/backgrounds/mobile.jpg',
    })).toContain('PC 端背景图')

    expect(getSiteConfigValidationError({
      ...fallbackSiteConfig,
      backgroundType: 'image',
      backgroundImage: '/uploads/backgrounds/desktop.jpg',
      mobileBackgroundImage: '',
    })).toBeNull()
  })
})
