import { storeToRefs } from 'pinia'
import { useSiteStore } from '@/stores/site.store'

export function useSiteConfig() {
  const store = useSiteStore()
  const { config, loading, usingFallback } = storeToRefs(store)
  return { config, loading, usingFallback, fetchConfig: store.fetchConfig }
}
