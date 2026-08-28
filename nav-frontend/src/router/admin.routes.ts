import type { RouteRecordRaw } from 'vue-router'

export const adminRoutes: RouteRecordRaw[] = [
  {
    path: '/admin/login',
    name: 'admin-login',
    component: () => import('@/views/admin/LoginView.vue'),
  },
  {
    path: '/admin',
    component: () => import('@/views/admin/AdminLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', name: 'admin-dashboard', component: () => import('@/views/admin/DashboardView.vue') },
      { path: 'site', name: 'admin-site', component: () => import('@/views/admin/SiteConfigView.vue') },
      { path: 'search-engines', name: 'admin-search-engines', component: () => import('@/views/admin/SearchEngineManageView.vue') },
      { path: 'categories', name: 'admin-categories', component: () => import('@/views/admin/CategoryManageView.vue') },
      { path: 'bookmarks', name: 'admin-bookmarks', component: () => import('@/views/admin/BookmarkManageView.vue') },
      { path: 'data', name: 'admin-data', component: () => import('@/views/admin/DataManageView.vue') },
      { path: 'account', name: 'admin-account', component: () => import('@/views/admin/AccountManageView.vue') },
      { path: 'custom-links', redirect: { name: 'admin-dashboard' } },
      { path: ':pathMatch(.*)*', redirect: { name: 'admin-dashboard' } },
    ],
  },
]
