import type { RouteRecordRaw } from 'vue-router'

export const portalRoutes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'portal-home',
    component: () => import('@/views/portal/PortalHome.vue'),
  },
]
