<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Collection, Folder, Link, RefreshRight, View, WarningFilled } from '@element-plus/icons-vue'
import PageHeading from '@/components/admin/PageHeading.vue'
import { getBookmarks } from '@/api/bookmark.api'
import { getCategories } from '@/api/category.api'
import { getAdminSiteConfig } from '@/api/site.api'
import type { Bookmark } from '@/types/bookmark'
import type { Category } from '@/types/category'
import type { SiteConfig } from '@/types/site'
import { countPublicBookmarks } from '@/utils/dashboardStats'

type DashboardResource = 'categories' | 'bookmarks' | 'site'

interface ResourceStatus {
  loading: boolean
  loaded: boolean
  error: string
}

const categories = ref<Category[]>([])
const bookmarks = ref<Bookmark[]>([])
const site = ref<SiteConfig | null>(null)
const resources = reactive<Record<DashboardResource, ResourceStatus>>({
  categories: { loading: false, loaded: false, error: '' },
  bookmarks: { loading: false, loaded: false, error: '' },
  site: { loading: false, loaded: false, error: '' },
})

const visibleBookmarks = computed(() => countPublicBookmarks(categories.value, bookmarks.value))

function statusText(keys: DashboardResource[]) {
  const states = keys.map((key) => resources[key])
  if (states.some((state) => state.loading)) return '加载中'
  if (states.some((state) => state.error)) {
    return states.every((state) => state.loaded) ? '刷新失败' : '加载失败'
  }
  return states.every((state) => state.loaded) ? '已加载' : '等待加载'
}

const stats = computed(() => [
  {
    label: '导航分类',
    value: resources.categories.loaded ? categories.value.length : '—',
    suffix: resources.categories.loaded ? '个' : '',
    icon: Folder,
    tone: 'blue',
    status: statusText(['categories']),
    loading: resources.categories.loading,
    error: Boolean(resources.categories.error),
  },
  {
    label: '全部书签',
    value: resources.bookmarks.loaded ? bookmarks.value.length : '—',
    suffix: resources.bookmarks.loaded ? '条' : '',
    icon: Link,
    tone: 'violet',
    status: statusText(['bookmarks']),
    loading: resources.bookmarks.loading,
    error: Boolean(resources.bookmarks.error),
  },
  {
    label: '公开展示',
    value: resources.categories.loaded && resources.bookmarks.loaded
      ? visibleBookmarks.value
      : '—',
    suffix: resources.categories.loaded && resources.bookmarks.loaded ? '条' : '',
    icon: View,
    tone: 'green',
    status: statusText(['categories', 'bookmarks']),
    loading: resources.categories.loading || resources.bookmarks.loading,
    error: Boolean(resources.categories.error || resources.bookmarks.error),
  },
  {
    label: '站点状态',
    value: resources.site.loaded && site.value ? '运行中' : resources.site.loading ? '检测中' : '—',
    suffix: '',
    icon: Collection,
    tone: 'orange',
    status: statusText(['site']),
    loading: resources.site.loading,
    error: Boolean(resources.site.error),
  },
])

const failedResources = computed(() => (
  (Object.keys(resources) as DashboardResource[])
    .filter((key) => Boolean(resources[key].error))
    .map((key) => ({
      key,
      label: { categories: '分类数据', bookmarks: '书签数据', site: '站点状态' }[key],
      message: resources[key].error,
    }))
))
const retrying = computed(() => failedResources.value.some(({ key }) => resources[key].loading))

function getErrorMessage(error: unknown) {
  return error instanceof Error && error.message ? error.message : '请求失败，请稍后重试'
}

async function loadCategories() {
  resources.categories.loading = true
  resources.categories.error = ''
  try {
    categories.value = await getCategories()
    resources.categories.loaded = true
  } catch (error) {
    resources.categories.error = getErrorMessage(error)
  } finally {
    resources.categories.loading = false
  }
}

async function loadBookmarks() {
  resources.bookmarks.loading = true
  resources.bookmarks.error = ''
  try {
    bookmarks.value = await getBookmarks()
    resources.bookmarks.loaded = true
  } catch (error) {
    resources.bookmarks.error = getErrorMessage(error)
  } finally {
    resources.bookmarks.loading = false
  }
}

async function loadSite() {
  resources.site.loading = true
  resources.site.error = ''
  try {
    site.value = await getAdminSiteConfig()
    resources.site.loaded = true
  } catch (error) {
    resources.site.error = getErrorMessage(error)
  } finally {
    resources.site.loading = false
  }
}

async function loadAll() {
  await Promise.allSettled([loadCategories(), loadBookmarks(), loadSite()])
}

async function retryFailed() {
  const tasks = failedResources.value.map(({ key }) => ({
    categories: loadCategories,
    bookmarks: loadBookmarks,
    site: loadSite,
  })[key]())
  await Promise.allSettled(tasks)
}

onMounted(() => void loadAll())
</script>

<template>
  <div class="admin-page">
    <PageHeading title="你好，欢迎回来" description="这里是你的导航站运行概览，保持内容新鲜而有序。" eyebrow="OVERVIEW">
      <RouterLink to="/" target="_blank"><el-button>预览首页 ↗</el-button></RouterLink>
    </PageHeading>

    <section class="dashboard-stats" aria-label="数据概览">
      <article
        v-for="stat in stats"
        :key="stat.label"
        class="dashboard-stat"
        :class="{ 'is-loading': stat.loading, 'is-error': stat.error }"
        :aria-busy="stat.loading"
      >
        <div class="dashboard-stat__icon" :class="`is-${stat.tone}`"><component :is="stat.icon" /></div>
        <div><p>{{ stat.label }}</p><strong>{{ stat.value }}<small>{{ stat.suffix }}</small></strong></div>
        <span>{{ stat.status }}</span>
      </article>
    </section>

    <section v-if="failedResources.length" class="admin-panel dashboard-load-errors" role="status">
      <WarningFilled aria-hidden="true" />
      <div>
        <strong>部分概览数据加载失败</strong>
        <p v-for="resource in failedResources" :key="resource.key">
          {{ resource.label }}：{{ resource.message }}
        </p>
        <small>已经成功读取的数据会继续保留，不会因单个接口失败而清空。</small>
      </div>
      <el-button type="primary" plain :loading="retrying" @click="retryFailed">
        <RefreshRight /> 重试失败项
      </el-button>
    </section>

    <section class="dashboard-grid">
      <article class="admin-panel dashboard-welcome">
        <div>
          <p>START HERE</p>
          <h2>让每一次打开浏览器，<br />都从秩序与灵感开始。</h2>
          <span>完善站点信息并持续整理书签，你的首页会越来越好用。</span>
          <RouterLink to="/admin/site"><el-button type="primary">配置我的站点</el-button></RouterLink>
        </div>
        <div class="dashboard-welcome__visual" aria-hidden="true"><i /><i /><i /><b>i</b></div>
      </article>
      <article class="admin-panel dashboard-tasks">
        <header><div><p>QUICK START</p><h2>快速开始</h2></div><span>3 STEPS</span></header>
        <ol>
          <li><span>01</span><div><strong>设置站点信息</strong><p>名称、简介与主题色</p></div><RouterLink to="/admin/site">→</RouterLink></li>
          <li><span>02</span><div><strong>创建导航分类</strong><p>搭建清晰的信息结构</p></div><RouterLink to="/admin/categories">→</RouterLink></li>
          <li><span>03</span><div><strong>添加常用书签</strong><p>建立你的快捷入口</p></div><RouterLink to="/admin/bookmarks">→</RouterLink></li>
        </ol>
      </article>
    </section>
  </div>
</template>
