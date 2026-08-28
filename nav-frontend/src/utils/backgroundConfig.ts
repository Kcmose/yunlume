import type { BackgroundType, SiteConfig } from '@/types/site'

export interface BackgroundConfigSnapshot {
  backgroundType: BackgroundType
  backgroundColor: string
  backgroundImage: string
  mobileBackgroundImage: string
  fontColor: string
}

export function getBackgroundConfigSnapshot(config: SiteConfig): BackgroundConfigSnapshot {
  return {
    backgroundType: config.backgroundType,
    backgroundColor: config.backgroundColor,
    backgroundImage: config.backgroundImage,
    mobileBackgroundImage: config.mobileBackgroundImage,
    fontColor: config.fontColor,
  }
}

export function hasBackgroundConfigChanged(
  config: SiteConfig,
  saved: BackgroundConfigSnapshot,
): boolean {
  const current = getBackgroundConfigSnapshot(config)
  return (Object.keys(current) as Array<keyof BackgroundConfigSnapshot>).some(
    (key) => current[key] !== saved[key],
  )
}
