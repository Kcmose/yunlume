import { storeToRefs } from 'pinia'
import { useNavigationStore } from '@/stores/navigation.store'

export function useBookmarks() {
  const store = useNavigationStore()
  const { visibleCategories, loading, usingFallback } = storeToRefs(store)
  return {
    categories: visibleCategories,
    loading,
    usingFallback,
    fetchNavigation: store.fetchNavigation,
  }
}
