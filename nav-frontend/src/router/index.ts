import { createRouter, createWebHistory } from 'vue-router'
import { adminRoutes } from './admin.routes'
import { installRoutes } from './install.routes'
import { portalRoutes } from './portal.routes'
import { useAuthStore } from '@/stores/auth.store'
import { useInstallStore } from '@/stores/install.store'
import { decideInstallRoute } from '@/utils/installState'
import { registerAdminSessionInvalidationHandler } from '@/utils/sessionInvalidation'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    ...portalRoutes,
    ...installRoutes,
    ...adminRoutes,
    { path: '/:pathMatch(.*)*', redirect: { name: 'portal-home' } },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

registerAdminSessionInvalidationHandler(async () => {
  const authStore = useAuthStore()
  const currentRoute = router.currentRoute.value
  const shouldReturnToLogin = currentRoute.path.startsWith('/admin')
    && currentRoute.name !== 'admin-login'
  const redirect = currentRoute.fullPath

  authStore.clearSession()

  if (shouldReturnToLogin) {
    await router.replace({
      name: 'admin-login',
      query: redirect ? { redirect } : undefined,
    })
  }
})

router.beforeEach(async (to) => {
  const installStore = useInstallStore()
  const installStatus = await installStore.fetchStatus()
  const installDecision = decideInstallRoute(to.name, installStatus)

  if (installDecision === 'install') return { name: 'install' }
  if (installDecision === 'login') return { name: 'admin-login' }

  const authStore = useAuthStore()
  const requiresAuth = to.matched.some((record) => record.meta.requiresAuth)

  if (requiresAuth) {
    if (!authStore.token) {
      return { name: 'admin-login', query: { redirect: to.fullPath } }
    }
    const profile = await authStore.fetchProfile()
    if (!authStore.token || !profile) {
      return { name: 'admin-login', query: { redirect: to.fullPath } }
    }
  }

  if (to.name === 'admin-login' && authStore.token) {
    const profile = await authStore.fetchProfile()
    if (authStore.token && profile) return { name: 'admin-dashboard' }
  }
  return true
})

export default router
