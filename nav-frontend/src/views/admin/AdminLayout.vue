<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AdminHeader from '@/components/admin/AdminHeader.vue'
import AdminSidebar from '@/components/admin/AdminSidebar.vue'

const MOBILE_QUERY = '(max-width: 900px)'

const route = useRoute()
const desktopCollapsed = ref(false)
const isMobile = ref(typeof window !== 'undefined' && window.matchMedia(MOBILE_QUERY).matches)
const mobileDrawerOpen = ref(false)
const menuTrigger = ref<HTMLButtonElement | null>(null)
const menuExpanded = computed(() => isMobile.value ? mobileDrawerOpen.value : !desktopCollapsed.value)

let mediaQuery: MediaQueryList | undefined
let scrollLocked = false
let previousBodyOverflow = ''
let previousDocumentOverflow = ''

function setScrollLock(locked: boolean) {
  if (typeof document === 'undefined' || locked === scrollLocked) return

  if (locked) {
    previousBodyOverflow = document.body.style.overflow
    previousDocumentOverflow = document.documentElement.style.overflow
    document.body.style.overflow = 'hidden'
    document.documentElement.style.overflow = 'hidden'
  } else {
    document.body.style.overflow = previousBodyOverflow
    document.documentElement.style.overflow = previousDocumentOverflow
  }

  scrollLocked = locked
}

function focusMenuTrigger() {
  void nextTick(() => {
    const trigger = menuTrigger.value
      ?? document.querySelector<HTMLButtonElement>('#admin-menu-toggle')
    trigger?.focus()
  })
}

function getSidebar() {
  return document.querySelector<HTMLElement>('#admin-sidebar')
}

function getSidebarFocusableElements() {
  const sidebar = getSidebar()
  if (!sidebar) return []

  return Array.from(sidebar.querySelectorAll<HTMLElement>(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
  )).filter((element) => element.getAttribute('aria-hidden') !== 'true' && element.tabIndex >= 0)
}

function focusDrawerMenu() {
  const sidebar = getSidebar()
  if (!sidebar || !mobileDrawerOpen.value) return

  const currentMenuItem = sidebar.querySelector<HTMLElement>(
    'nav .router-link-exact-active, nav .is-active',
  )
  const firstMenuItem = sidebar.querySelector<HTMLElement>('nav .admin-sidebar__link')
  const focusTarget = currentMenuItem ?? firstMenuItem ?? sidebar
  const applyFocus = () => {
    if (!mobileDrawerOpen.value || !focusTarget.isConnected) return
    void sidebar.offsetWidth
    focusTarget.focus({ preventScroll: true })
  }

  applyFocus()
  if (document.activeElement !== focusTarget) window.setTimeout(applyFocus, 280)
}

function closeMobileDrawer(restoreFocus = false) {
  if (!isMobile.value || !mobileDrawerOpen.value) return
  mobileDrawerOpen.value = false
  if (restoreFocus) focusMenuTrigger()
}

function handleMenuToggle(trigger: HTMLButtonElement) {
  menuTrigger.value = trigger
  if (isMobile.value) {
    const opening = !mobileDrawerOpen.value
    if (opening) trigger.blur()
    mobileDrawerOpen.value = opening
    return
  }
  desktopCollapsed.value = !desktopCollapsed.value
}

function handleBreakpointChange(event: MediaQueryListEvent) {
  const sidebarHadFocus = getSidebar()?.contains(document.activeElement) ?? false
  if (event.matches && sidebarHadFocus && document.activeElement instanceof HTMLElement) {
    document.activeElement.blur()
  }
  isMobile.value = event.matches
  mobileDrawerOpen.value = false
  if (event.matches && sidebarHadFocus) focusMenuTrigger()
}

function handleKeydown(event: KeyboardEvent) {
  if (!isMobile.value || !mobileDrawerOpen.value) return

  if (event.key === 'Escape') {
    event.preventDefault()
    closeMobileDrawer(true)
    return
  }

  if (event.key !== 'Tab') return

  const sidebar = getSidebar()
  const focusableElements = getSidebarFocusableElements()
  if (!sidebar || focusableElements.length === 0) {
    event.preventDefault()
    sidebar?.focus()
    return
  }

  const first = focusableElements[0]
  const last = focusableElements[focusableElements.length - 1]
  const activeElement = document.activeElement
  const focusIsOutsideDrawer = !sidebar.contains(activeElement)

  if (event.shiftKey && (activeElement === first || focusIsOutsideDrawer)) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && (activeElement === last || focusIsOutsideDrawer)) {
    event.preventDefault()
    first.focus()
  }
}

watch(
  [isMobile, mobileDrawerOpen],
  ([mobile, open]) => setScrollLock(mobile && open),
  { flush: 'sync' },
)

watch(mobileDrawerOpen, (open) => {
  if (open) focusDrawerMenu()
}, { flush: 'post' })

watch(() => route.fullPath, () => closeMobileDrawer(true))

onMounted(() => {
  mediaQuery = window.matchMedia(MOBILE_QUERY)
  isMobile.value = mediaQuery.matches
  mediaQuery.addEventListener('change', handleBreakpointChange)
  window.addEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  mediaQuery?.removeEventListener('change', handleBreakpointChange)
  window.removeEventListener('keydown', handleKeydown)
  setScrollLock(false)
})
</script>

<template>
  <div
    class="admin-shell"
    :class="{
      'sidebar-collapsed': !isMobile && desktopCollapsed,
      'mobile-drawer-open': isMobile && mobileDrawerOpen,
    }"
  >
    <AdminSidebar
      :collapsed="!isMobile && desktopCollapsed"
      :mobile="isMobile"
      :open="mobileDrawerOpen"
      @navigate="closeMobileDrawer(true)"
    />
    <button
      class="admin-sidebar-backdrop"
      type="button"
      aria-label="关闭后台菜单"
      aria-controls="admin-sidebar"
      aria-hidden="true"
      tabindex="-1"
      @click="closeMobileDrawer(true)"
    />
    <div
      class="admin-shell__main"
      :aria-hidden="isMobile && mobileDrawerOpen"
      :inert="isMobile && mobileDrawerOpen"
    >
      <AdminHeader :menu-expanded="menuExpanded" @toggle="handleMenuToggle" />
      <main class="admin-content">
        <RouterView />
      </main>
    </div>
  </div>
</template>
