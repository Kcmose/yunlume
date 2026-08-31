<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watchEffect } from 'vue'
import TopActionBar from '@/components/portal/TopActionBar.vue'
import SiteHeader from '@/components/portal/SiteHeader.vue'
import SearchBar from '@/components/portal/SearchBar.vue'
import CategoryGrid from '@/components/portal/CategoryGrid.vue'
import { getPublicSearchEngines } from '@/api/public.api'
import { useBookmarks } from '@/composables/useBookmarks'
import { useSearch } from '@/composables/useSearch'
import { useSiteConfig } from '@/composables/useSiteConfig'
import { useTheme } from '@/composables/useTheme'
import type { SearchEngine } from '@/types/searchEngine'
import { withPublicRequestRetry } from '@/utils/publicRequestRetry'
import {
  isSameSearchEngine,
  persistSearchEngineId,
  readPersistedSearchEngineId,
  resolveSearchEngineId,
} from '@/utils/searchEnginePicker'

const fallbackSearchEngines: SearchEngine[] = [
  {
    id: 'baidu',
    name: '百度',
    icon: '',
    searchUrl: 'https://www.baidu.com/s?wd={keyword}',
    placeholder: '想要搜索什么',
    isDefault: true,
    sortOrder: 0,
  },
]

function getBrowserStorage() {
  try {
    return typeof window === 'undefined' ? null : window.localStorage
  } catch {
    return null
  }
}

const {
  config,
  loading: siteLoading,
  usingFallback: siteUsingFallback,
  fetchConfig,
} = useSiteConfig()
const {
  categories,
  loading: navigationLoading,
  usingFallback: navigationUsingFallback,
  fetchNavigation,
} = useBookmarks()
const { themeStyle } = useTheme(config)
const searchEngines = ref<SearchEngine[]>(fallbackSearchEngines)
const browserStorage = getBrowserStorage()
const persistedEngineId = ref(readPersistedSearchEngineId(browserStorage))
const activeEngineId = ref<SearchEngine['id']>(
  persistedEngineId.value || fallbackSearchEngines[0].id,
)
const searchEnginesLoading = ref(false)
const searchEnginesUsingFallback = ref(false)
const hasRemoteSearchEngines = ref(false)
const initialPublicDataSettled = ref(false)
const activeEngine = computed(() =>
  searchEngines.value.find((engine) => isSameSearchEngine(engine.id, activeEngineId.value))
  ?? searchEngines.value[0]
  ?? fallbackSearchEngines[0],
)
const { keyword, filteredCategories, submitSearch, clearSearch } = useSearch(categories, activeEngine)

const year = new Date().getFullYear()
const bookmarkCount = computed(() =>
  filteredCategories.value.reduce((total, category) => total + category.bookmarks.length, 0),
)
const publicDataLoading = computed(() =>
  siteLoading.value || navigationLoading.value || searchEnginesLoading.value,
)
const publicDataUsingFallback = computed(() =>
  siteUsingFallback.value || navigationUsingFallback.value || searchEnginesUsingFallback.value,
)
const originalDocumentTitle = document.title
const descriptionMeta = document.querySelector<HTMLMetaElement>('meta[name="description"]')
const themeColorMeta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
const originalDescription = descriptionMeta?.getAttribute('content') ?? null
const originalThemeColor = themeColorMeta?.getAttribute('content') ?? null

async function fetchSearchEngines() {
  searchEnginesLoading.value = true
  try {
    const engines = await withPublicRequestRetry(getPublicSearchEngines)
    if (!engines.length) {
      searchEnginesUsingFallback.value = !hasRemoteSearchEngines.value
      return
    }
    const nextEngineId = resolveSearchEngineId(
      engines,
      activeEngineId.value,
      persistedEngineId.value,
    )
    searchEngines.value = engines
    if (nextEngineId !== null) {
      activeEngineId.value = nextEngineId
      persistedEngineId.value = String(nextEngineId)
      persistSearchEngineId(browserStorage, nextEngineId)
    }
    hasRemoteSearchEngines.value = true
    searchEnginesUsingFallback.value = false
  } catch {
    // Preserve the last successful result. On first load the ref contains a
    // safe fallback, but make that degraded state visible to the visitor.
    searchEnginesUsingFallback.value = !hasRemoteSearchEngines.value
  } finally {
    searchEnginesLoading.value = false
  }
}

function selectSearchEngine(engineId: SearchEngine['id']) {
  if (searchEngines.value.some((engine) => isSameSearchEngine(engine.id, engineId))) {
    activeEngineId.value = engineId
    persistedEngineId.value = String(engineId)
    persistSearchEngineId(browserStorage, engineId)
  }
}

async function loadPublicData() {
  await Promise.allSettled([
    fetchConfig(),
    fetchNavigation(),
    fetchSearchEngines(),
  ])
  initialPublicDataSettled.value = true
}

watchEffect(() => {
  if (!initialPublicDataSettled.value) return
  const siteName = config.value.siteName.trim() || '导航站'
  const description = config.value.siteDescription.trim() || '常用网站导航'
  document.title = siteName
  descriptionMeta?.setAttribute('content', description)
  themeColorMeta?.setAttribute('content', config.value.backgroundColor || '#050505')
})

onMounted(() => void loadPublicData())
onBeforeUnmount(() => {
  document.title = originalDocumentTitle
  if (descriptionMeta && originalDescription !== null) {
    descriptionMeta.setAttribute('content', originalDescription)
  } else {
    descriptionMeta?.removeAttribute('content')
  }
  if (themeColorMeta && originalThemeColor !== null) {
    themeColorMeta.setAttribute('content', originalThemeColor)
  } else {
    themeColorMeta?.removeAttribute('content')
  }
})
</script>

<template>
  <div
    id="top"
    class="portal-page"
    :data-background-type="config.backgroundType"
    :style="themeStyle"
  >
    <template v-if="initialPublicDataSettled">
      <TopActionBar
        :announcement-enabled="config.topContentEnabled"
        :announcement="config.messageText"
      />
      <main>
        <SiteHeader
          :name="config.siteName"
          :description="config.siteDescription"
        />
        <SearchBar
          v-model="keyword"
          :result-count="bookmarkCount"
          :engine="activeEngine"
          :engines="searchEngines"
          @select-engine="selectSearchEngine"
          @submit="submitSearch"
          @clear="clearSearch"
        />
        <CategoryGrid
          :categories="filteredCategories"
          :loading="publicDataLoading"
          :using-fallback="publicDataUsingFallback"
          :search-active="Boolean(keyword.trim())"
          @retry="loadPublicData"
        />
      </main>
      <footer class="portal-footer">
        <p>© {{ year }} {{ config.siteName }}</p>
      </footer>
    </template>
    <div v-else class="portal-initial-loading" role="status" aria-live="polite">
      <span class="sr-only">正在加载导航内容</span>
    </div>
  </div>
</template>
