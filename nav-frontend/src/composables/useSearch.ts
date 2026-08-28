import { computed, ref, type ComputedRef, type Ref } from 'vue'
import type { NavigationCategory } from '@/types/category'
import type { SearchEngine } from '@/types/searchEngine'
import { baiduSearchUrl, buildSearchUrl } from '@/utils/url'
import { filterNavigation } from '@/utils/search'

export function useSearch(
  categories: Ref<NavigationCategory[]>,
  activeEngine?: ComputedRef<SearchEngine | undefined>,
) {
  const keyword = ref('')
  const filteredCategories = computed(() => filterNavigation(categories.value, keyword.value))

  function submitSearch() {
    const value = keyword.value.trim()
    if (!value) return
    const engine = activeEngine?.value
    const target = engine ? buildSearchUrl(engine.searchUrl, value) : baiduSearchUrl(value)
    window.open(target, '_blank', 'noopener,noreferrer')
  }

  function clearSearch() {
    keyword.value = ''
  }

  return { keyword, filteredCategories, submitSearch, clearSearch }
}
