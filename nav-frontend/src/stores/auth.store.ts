import { defineStore } from 'pinia'
import {
  changePasswordApi,
  loginApi,
  logoutAllApi,
  logoutApi,
  profileApi,
} from '@/api/auth.api'
import type { AdminUser, ChangePasswordPayload, LoginPayload } from '@/types/auth'
import { jsonStorage, tokenStorage, USER_KEY } from '@/utils/storage'
import { shouldInvalidateAdminSession } from '@/utils/httpError'
import { invalidateAdminSession } from '@/utils/sessionInvalidation'

const PROFILE_FRESHNESS_MS = 30_000
let profileRequest: Promise<AdminUser | null> | null = null

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: tokenStorage.get(),
    user: jsonStorage.get<AdminUser>(USER_KEY),
    loading: false,
    profileLastAttemptAt: 0,
  }),
  getters: {
    isAuthenticated: (state) => Boolean(state.token),
  },
  actions: {
    async login(payload: LoginPayload) {
      this.loading = true
      try {
        const result = await loginApi(payload)
        this.token = result.token
        this.user = result.user
        this.profileLastAttemptAt = Date.now()
        tokenStorage.set(result.token)
        jsonStorage.set(USER_KEY, result.user)
      } finally {
        this.loading = false
      }
    },
    async fetchProfile(force = false): Promise<AdminUser | null> {
      if (!this.token) return null
      if (
        !force
        && this.profileLastAttemptAt > 0
        && Date.now() - this.profileLastAttemptAt < PROFILE_FRESHNESS_MS
      ) {
        return this.user
      }
      if (profileRequest) return profileRequest

      const requestToken = this.token
      this.profileLastAttemptAt = Date.now()
      profileRequest = (async () => {
        try {
          const user = await profileApi()
          if (this.token !== requestToken) return this.user
          this.user = user
          jsonStorage.set(USER_KEY, user)
          return user
        } catch (error) {
          if (this.token === requestToken && shouldInvalidateAdminSession(error)) {
            const handled = await invalidateAdminSession()
            // Store tests and non-router consumers may call the action before
            // the app-level handler is installed. Keep the state safe there,
            // while the normal runtime follows the central handler exactly once.
            if (!handled && this.token === requestToken) this.clearSession()
          }
          return this.token ? this.user : null
        } finally {
          profileRequest = null
        }
      })()
      return profileRequest
    },
    async logout() {
      try {
        await logoutApi()
      } finally {
        this.clearSession()
      }
    },
    async changePassword(payload: ChangePasswordPayload) {
      await changePasswordApi(payload)
      this.clearSession()
    },
    async logoutAll() {
      await logoutAllApi()
      this.clearSession()
    },
    clearSession() {
      this.token = ''
      this.user = null
      this.profileLastAttemptAt = 0
      tokenStorage.remove()
      jsonStorage.remove(USER_KEY)
    },
  },
})
