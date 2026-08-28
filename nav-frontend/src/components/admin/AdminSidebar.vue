<script setup lang="ts">
import { Collection, Compass, DataAnalysis, Folder, HomeFilled, Link, Lock, Search, Setting } from '@element-plus/icons-vue'

withDefaults(defineProps<{
  collapsed?: boolean
  mobile?: boolean
  open?: boolean
}>(), {
  collapsed: false,
  mobile: false,
  open: false,
})

const emit = defineEmits<{ navigate: [] }>()

const menuItems = [
  { label: '总览', path: '/admin', icon: HomeFilled },
  { label: '站点配置', path: '/admin/site', icon: Setting },
  { label: '搜索引擎', path: '/admin/search-engines', icon: Search },
  { label: '分类管理', path: '/admin/categories', icon: Folder },
  { label: '书签管理', path: '/admin/bookmarks', icon: Link },
  { label: '数据管理', path: '/admin/data', icon: DataAnalysis },
  { label: '账号安全', path: '/admin/account', icon: Lock },
]
</script>

<template>
  <aside
    id="admin-sidebar"
    class="admin-sidebar"
    :class="{ 'is-collapsed': collapsed }"
    :aria-hidden="mobile && !open"
    :inert="mobile && !open"
    aria-label="后台导航菜单"
    tabindex="-1"
  >
    <RouterLink class="admin-sidebar__brand" to="/" aria-label="返回公开首页" @click="emit('navigate')">
      <span><Compass /></span>
      <div>
        <strong>yunlume</strong>
        <small>NAVIGATION OS</small>
      </div>
    </RouterLink>
    <p class="admin-sidebar__section-label">工作台</p>
    <nav aria-label="后台主菜单">
      <RouterLink
        v-for="item in menuItems"
        :key="item.path"
        :to="item.path"
        class="admin-sidebar__link"
        exact-active-class="is-active"
        :aria-label="collapsed ? item.label : undefined"
        :title="collapsed ? item.label : undefined"
        @click="emit('navigate')"
      >
        <component :is="item.icon" />
        <span>{{ item.label }}</span>
      </RouterLink>
    </nav>
    <div class="admin-sidebar__tip">
      <Collection />
      <div>
        <strong>保持井然有序</strong>
        <p>清晰的分类，让每次抵达都更快。</p>
      </div>
    </div>
    <RouterLink
      class="admin-sidebar__portal-link"
      to="/"
      target="_blank"
      rel="noopener noreferrer"
      @click="emit('navigate')"
    >
      <span>查看公开首页</span>
      <span aria-hidden="true">↗</span>
    </RouterLink>
  </aside>
</template>
