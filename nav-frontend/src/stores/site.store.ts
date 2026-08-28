import { defineStore } from 'pinia'
import { getPublicSiteConfig } from '@/api/public.api'
import { fallbackSiteConfig } from '@/data/fallback'
import type { SiteConfig } from '@/types/site'
import { withPublicRequestRetry } from '@/utils/publicRequestRetry'

export const useSiteStore = defineStore('site', {
  state: () => ({
    config: { ...fallbackSiteConfig } as SiteConfig,
    loading: false,
    usingFallback: false,
    hasRemoteConfig: false,
    requestVersion: 0,
  }),
  actions: {
    async fetchConfig() {
      const requestVersion = ++this.requestVersion
      this.loading = true
      try {
        const remoteConfig = await withPublicRequestRetry(getPublicSiteConfig)
        if (requestVersion !== this.requestVersion) return
        this.config = {
          ...fallbackSiteConfig,
          ...remoteConfig,
          mobileBackgroundImage: remoteConfig.mobileBackgroundImage ?? '',
        }
        this.hasRemoteConfig = true
        this.usingFallback = false
      } catch {
        if (requestVersion !== this.requestVersion) return
        // Keep the last confirmed server value. On the first load the state is
        // already the bundled fallback, so no destructive reset is necessary.
        this.usingFallback = !this.hasRemoteConfig
      } finally {
        if (requestVersion === this.requestVersion) this.loading = false
      }
    },
  },
})
