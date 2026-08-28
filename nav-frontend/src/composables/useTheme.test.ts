import { ref } from 'vue'
import { describe, expect, it } from 'vitest'
import { fallbackSiteConfig } from '@/data/fallback'
import { backgroundImageCssValue, useTheme } from './useTheme'

describe('portal theme', () => {
  it('uses pure colors without leaking stored background images', () => {
    const config = ref({
      ...fallbackSiteConfig,
      backgroundType: 'color' as const,
      backgroundColor: '#ffffff',
      fontColor: '#111111',
      backgroundImage: 'https://example.com/desktop.jpg',
      mobileBackgroundImage: 'https://example.com/mobile.jpg',
    })

    const { themeStyle } = useTheme(config)

    expect(themeStyle.value).toMatchObject({
      '--portal-background': '#ffffff',
      '--portal-foreground': '#111111',
      '--portal-text-strong': '#111111',
      '--portal-text': '#111111',
      '--portal-text-muted': '#111111',
      '--portal-text-subtle': '#111111',
      '--portal-background-image-desktop': 'none',
      '--portal-background-image-mobile': 'none',
    })
  })

  it('uses one fully opaque pure black color for all portal text levels', () => {
    const config = ref({
      ...fallbackSiteConfig,
      fontColor: '#000000',
    })

    const { themeStyle } = useTheme(config)

    expect([
      themeStyle.value['--portal-foreground'],
      themeStyle.value['--portal-text-strong'],
      themeStyle.value['--portal-text'],
      themeStyle.value['--portal-text-muted'],
      themeStyle.value['--portal-text-subtle'],
    ]).toEqual(Array(5).fill('#000000'))
  })

  it('uses separate desktop and mobile images', () => {
    const config = ref({
      ...fallbackSiteConfig,
      backgroundType: 'image' as const,
      backgroundImage: 'https://example.com/desktop.jpg',
      mobileBackgroundImage: '/uploads/backgrounds/mobile.png',
    })

    const { themeStyle } = useTheme(config)

    expect(themeStyle.value['--portal-background-image-desktop']).toBe(
      'url("https://example.com/desktop.jpg")',
    )
    expect(themeStyle.value['--portal-background-image-mobile']).toBe(
      'url("/uploads/backgrounds/mobile.png")',
    )
  })

  it('falls back to the desktop image on mobile', () => {
    const config = ref({
      ...fallbackSiteConfig,
      backgroundType: 'image' as const,
      backgroundImage: '/uploads/backgrounds/desktop.png',
      mobileBackgroundImage: '',
    })

    const { themeStyle } = useTheme(config)

    expect(themeStyle.value['--portal-background-image-mobile']).toBe(
      themeStyle.value['--portal-background-image-desktop'],
    )
  })

  it('rejects unsafe or CSS-breaking image values', () => {
    expect(backgroundImageCssValue('javascript:alert(1)')).toBe('none')
    expect(backgroundImageCssValue('https://example.com/a"),linear-gradient(red,red)')).toBe('none')
  })
})
