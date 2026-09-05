import { defineStore } from 'pinia'
import { getPublicNavigation } from '@/api/public.api'
import { fallbackNavigation } from '@/data/fallback'
import type { NavigationCategory } from '@/types/category'
import { withPublicRequestRetry } from '@/utils/publicRequestRetry'

export const useNavigationStore = defineStore('navigation', {
  state: () => ({
    categories: [] as NavigationCategory[],
    loading: false,
    // Do not show a failure warning during the normal first synchronization;
    // the fallback becomes explicitly degraded only after all retries fail.
    usingFallback: false,
    hasRemoteNavigation: false,
    requestVersion: 0,
  }),
  getters: {
    visibleCategories: (state) =>
      state.categories
        .filter((category) => category.visible)
        .map((category) => ({
          ...category,
          bookmarks: category.bookmarks.filter((bookmark) => bookmark.visible),
        }))
        .filter((category) => category.bookmarks.length > 0),
  },
  actions: {
    async fetchNavigation() {
      const requestVersion = ++this.requestVersion
      this.loading = true
      try {
        const categories = await withPublicRequestRetry(getPublicNavigation)
        if (requestVersion !== this.requestVersion) return
        this.categories = categories
        this.hasRemoteNavigation = true
        this.usingFallback = false
      } catch {
        if (requestVersion !== this.requestVersion) return
        if (!this.hasRemoteNavigation) this.categories = fallbackNavigation
        this.usingFallback = !this.hasRemoteNavigation
      } finally {
        if (requestVersion === this.requestVersion) this.loading = false
      }
    },
  },
})
