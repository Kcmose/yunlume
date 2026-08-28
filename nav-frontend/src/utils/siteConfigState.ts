import type { SiteConfig, SiteConfigUpdatePayload } from '@/types/site'

const EDITABLE_SITE_CONFIG_KEYS = [
  'siteName',
  'siteDescription',
  'publishUrl',
  'backgroundType',
  'backgroundColor',
  'backgroundImage',
  'mobileBackgroundImage',
  'fontColor',
  'backgroundEffect',
  'musicEnabled',
  'musicUrl',
  'subscribeEnabled',
  'topContentEnabled',
  'messageText',
] as const satisfies ReadonlyArray<keyof SiteConfig>

type EditableSiteConfigKey = (typeof EDITABLE_SITE_CONFIG_KEYS)[number]
export type SiteConfigSnapshot = Pick<SiteConfig, EditableSiteConfigKey>

export function getSiteConfigSnapshot(config: SiteConfig): SiteConfigSnapshot {
  return Object.fromEntries(
    EDITABLE_SITE_CONFIG_KEYS.map((key) => [key, config[key]]),
  ) as SiteConfigSnapshot
}

export function hasSiteConfigChanged(
  config: SiteConfig,
  saved: SiteConfigSnapshot | null,
): boolean {
  if (!saved) return false
  const current = getSiteConfigSnapshot(config)
  return EDITABLE_SITE_CONFIG_KEYS.some((key) => current[key] !== saved[key])
}

export function createSiteConfigUpdatePayload(config: SiteConfig): SiteConfigUpdatePayload {
  return {
    ...getSiteConfigSnapshot(config),
    expectedVersion: config.version,
  }
}

export function getSiteConfigValidationError(config: SiteConfig): string | null {
  if (!Number.isInteger(config.version) || config.version < 0) {
    return '服务端没有返回有效的配置版本，请重新加载后再试'
  }
  if (config.backgroundType === 'image' && !config.backgroundImage.trim()) {
    return '图片背景模式必须设置 PC 端背景图；移动端图片可以留空并自动使用 PC 图片'
  }
  return null
}
