import { computed, type Ref } from 'vue'
import type { SiteConfig } from '@/types/site'

export function backgroundImageCssValue(value: string | null | undefined): string {
  const candidate = value?.trim() ?? ''
  if (!candidate || /["'\\\r\n]/.test(candidate) || /\s/.test(candidate)) return 'none'

  if (candidate.startsWith('/')) {
    if (candidate.startsWith('//')) return 'none'
    return `url("${candidate}")`
  }

  try {
    const parsed = new URL(candidate)
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return 'none'
    return `url("${candidate}")`
  } catch {
    return 'none'
  }
}

export function useTheme(config: Ref<SiteConfig>) {
  const themeStyle = computed(() => {
    const imageMode = config.value.backgroundType === 'image'
    const foreground = config.value.fontColor || '#ffffff'
    const desktopImage = imageMode
      ? backgroundImageCssValue(config.value.backgroundImage)
      : 'none'
    const mobileImage = imageMode
      ? backgroundImageCssValue(config.value.mobileBackgroundImage || config.value.backgroundImage)
      : 'none'

    return {
      '--portal-background': config.value.backgroundColor || '#000000',
      '--portal-foreground': foreground,
      '--portal-text-strong': foreground,
      '--portal-text': foreground,
      '--portal-text-muted': foreground,
      '--portal-text-subtle': foreground,
      '--portal-background-image-desktop': desktopImage,
      '--portal-background-image-mobile': mobileImage,
    }
  })
  return { themeStyle }
}
