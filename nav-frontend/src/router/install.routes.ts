import type { RouteRecordRaw } from 'vue-router'

export const installRoutes: RouteRecordRaw[] = [
  {
    path: '/install',
    name: 'install',
    component: () => import('@/views/install/InstallView.vue'),
  },
]
