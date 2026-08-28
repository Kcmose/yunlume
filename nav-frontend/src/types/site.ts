export type BackgroundType = 'color' | 'image'

export interface SiteConfig {
  id?: number
  version: number
  siteName: string
  siteDescription: string
  publishUrl: string
  backgroundType: BackgroundType
  backgroundColor: string
  backgroundImage: string
  mobileBackgroundImage: string
  fontColor: string
  backgroundEffect: boolean
  musicEnabled: boolean
  musicUrl: string
  subscribeEnabled: boolean
  topContentEnabled: boolean
  messageText: string
}

export type SiteConfigUpdatePayload = Omit<SiteConfig, 'id' | 'version'> & {
  expectedVersion: number
}
