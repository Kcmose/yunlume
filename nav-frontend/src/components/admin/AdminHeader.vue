<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Fold, FullScreen } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth.store'

defineProps<{ menuExpanded: boolean }>()

const emit = defineEmits<{ toggle: [trigger: HTMLButtonElement] }>()

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const pageTitle = computed(() => {
  const titles: Record<string, string> = {
    'admin-dashboard': '总览',
    'admin-site': '站点配置',
    'admin-search-engines': '搜索引擎管理',
    'admin-categories': '分类管理',
    'admin-bookmarks': '书签管理',
    'admin-data': '数据管理',
    'admin-account': '账号安全',
  }
  return titles[String(route.name)] ?? '管理中心'
})

const displayName = computed(() => authStore.user?.nickname || authStore.user?.username || '管理员')
const initial = computed(() => ([...displayName.value][0] || '管').toUpperCase())

function toggleFullscreen() {
  if (!document.fullscreenElement) void document.documentElement.requestFullscreen()
  else void document.exitFullscreen()
}

async function logout() {
  await authStore.logout()
  await router.replace('/admin/login')
}

async function handleUserCommand(command: string) {
  if (command === 'account') {
    await router.push('/admin/account')
    return
  }
  await logout()
}

function emitToggle(event: MouseEvent) {
  emit('toggle', event.currentTarget as HTMLButtonElement)
}
</script>

<template>
  <header class="admin-header">
    <div class="admin-header__title">
      <button
        id="admin-menu-toggle"
        class="admin-header__menu-toggle"
        type="button"
        aria-controls="admin-sidebar"
        :aria-expanded="menuExpanded"
        :aria-label="menuExpanded ? '收起后台菜单' : '展开后台菜单'"
        @click="emitToggle"
      >
        <Fold />
      </button>
      <div>
        <p>yunlume / 管理中心</p>
        <h1>{{ pageTitle }}</h1>
      </div>
    </div>
    <div class="admin-header__actions">
      <button type="button" aria-label="切换全屏" title="切换全屏" @click="toggleFullscreen"><FullScreen /></button>
      <el-dropdown trigger="click" @command="handleUserCommand">
        <button class="admin-user" type="button" :aria-label="`${displayName}账户菜单`">
          <span class="admin-user__avatar">{{ initial }}</span>
          <span class="admin-user__name">{{ displayName }}</span>
          <ArrowDown />
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="account">账号安全</el-dropdown-item>
            <el-dropdown-item command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>
