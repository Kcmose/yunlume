<script setup lang="ts">
import { Bell, Position, Tools } from '@element-plus/icons-vue'

defineProps<{
  announcement: string
  announcementEnabled: boolean
}>()

function scrollToNavigation() {
  document.querySelector('#navigation-content')?.scrollIntoView({ behavior: 'smooth' })
}
</script>

<template>
  <header class="portal-topbar" aria-label="页面工具栏">
    <div v-if="announcementEnabled && announcement.trim()" class="portal-topbar__announcement" role="status">
      <Bell aria-hidden="true" />
      <span>{{ announcement }}</span>
    </div>
    <nav class="portal-topbar__actions" aria-label="快捷操作">
      <button type="button" aria-label="查看导航分类" title="查看导航分类" @click="scrollToNavigation">
        <Position aria-hidden="true" />
      </button>
      <RouterLink to="/admin/site" aria-label="站点配置" title="站点配置">
        <Tools aria-hidden="true" />
      </RouterLink>
    </nav>
  </header>
</template>
