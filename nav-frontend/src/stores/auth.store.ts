import { defineStore } from 'pinia'
import {
  changePasswordApi,
  loginApi,
  logoutAllApi,
  logoutApi,
  profileApi,
} from '@/api/auth.api'
import type { AdminUser, ChangePasswordPayload, LoginPayload, LoginResult } from '@/types/auth'
import {
  boundUserStorage,
  subscribeAuthStorage,
  tokenStorage,
} from '@/utils/storage'
import type { AuthTokenSnapshot } from '@/utils/storage'
import { shouldInvalidateAdminSession } from '@/utils/httpError'
import { invalidateAdminSession } from '@/utils/sessionInvalidation'

const PROFILE_FRESHNESS_MS = 30_000
let profileRequest: Promise<AdminUser | null> | null = null
const storageSyncStops = new WeakMap<object, () => void>()

function sameAuthority(state: { token: string, generation: string, commitment: string }, snapshot: AuthTokenSnapshot | null): boolean {
  return Boolean(snapshot
    && state.token === snapshot.token
    && state.generation === snapshot.generation
    && state.commitment === snapshot.commitment)
}

function sameSnapshot(left: AuthTokenSnapshot | null, right: AuthTokenSnapshot | null): boolean {
  return left === null ? right === null : sameAuthority(left, right)
}

function clearBoundUser(): void {
  try { boundUserStorage.remove() } catch { /* metadata cleanup never blocks auth state */ }
}

function initialSession(): { token: string, generation: string, commitment: string, user: AdminUser | null } {
  const snapshot = tokenStorage.getSnapshot()
  if (snapshot) return { ...snapshot, user: boundUserStorage.get<AdminUser>(snapshot) }
  clearBoundUser()
  return { token: '', generation: '', commitment: '', user: null }
}

const useAuthStoreDefinition = defineStore('auth', {
  state: () => ({
    ...initialSession(),
    loading: false,
    profileLastAttemptAt: 0,
    loginRequestVersion: 0,
  }),
  getters: {
    isAuthenticated: (state) => sameAuthority(state, tokenStorage.getSnapshot()),
  },
  actions: {
    ensureStorageSync() {
      if (storageSyncStops.has(this)) return
      const stop = subscribeAuthStorage(() => this.reconcileSession(true))
      storageSyncStops.set(this, stop)
      const dispose = this.$dispose.bind(this)
      this.$dispose = () => {
        storageSyncStops.get(this)?.()
        storageSyncStops.delete(this)
        dispose()
      }
    },
    clearMemorySession() {
      this.token = ''
      this.generation = ''
      this.commitment = ''
      this.user = null
      this.profileLastAttemptAt = 0
    },
    reconcileSession(userMetadataChanged = false): boolean {
      const durable = tokenStorage.getSnapshot()
      const authorityChanged = durable ? !sameAuthority(this, durable) : Boolean(this.token)
      if (authorityChanged) {
        // 已观察到其他标签页切换会话，旧登录即使稍后返回也不能重新接管。
        this.loginRequestVersion += 1
        this.loading = false
      }
      if (!durable) {
        this.clearMemorySession()
        clearBoundUser()
        return false
      }
      if (!sameAuthority(this, durable)) {
        this.token = durable.token
        this.generation = durable.generation
        this.commitment = durable.commitment
        this.user = null
        this.profileLastAttemptAt = 0
      } else if (userMetadataChanged) {
        this.user = boundUserStorage.get<AdminUser>(durable)
        this.profileLastAttemptAt = 0
      }
      return true
    },
    async login(payload: LoginPayload): Promise<LoginResult | undefined> {
      this.reconcileSession()
      const requestAuthority = tokenStorage.getSnapshot()
      const requestVersion = ++this.loginRequestVersion
      let persistenceStarted = false
      this.loading = true
      try {
        const result = await loginApi(payload)
        if (requestVersion !== this.loginRequestVersion) return
        if (!sameSnapshot(requestAuthority, tokenStorage.getSnapshot())) {
          this.reconcileSession(true)
          return
        }
        persistenceStarted = true
        clearBoundUser()
        const accepted = tokenStorage.setSnapshot(result.token)
        if (!sameAuthority(accepted, tokenStorage.getSnapshot())) {
          this.reconcileSession(true)
          return
        }
        this.token = accepted.token
        this.generation = accepted.generation
        this.commitment = accepted.commitment
        this.user = result.user
        this.profileLastAttemptAt = Date.now()
        boundUserStorage.set(result.user, accepted)
        if (!sameAuthority(accepted, tokenStorage.getSnapshot())) {
          this.reconcileSession(true)
          return
        }
        return result
      } catch (error) {
        if (requestVersion !== this.loginRequestVersion) return undefined
        const current = tokenStorage.getSnapshot()
        // 网络失败只归属于发起时的会话；持久化失败可以清理自身留下的空状态，
        // 但不能覆盖存储协议保留下来的另一标签页已提交会话。
        if (!sameSnapshot(requestAuthority, current) && (!persistenceStarted || current !== null)) {
          this.reconcileSession(true)
          return undefined
        }
        this.clearSession()
        throw error
      } finally {
        if (requestVersion === this.loginRequestVersion) this.loading = false
      }
    },
    async fetchProfile(force = false): Promise<AdminUser | null> {
      if (!this.reconcileSession()) return null
      if (
        !force
        && this.profileLastAttemptAt > 0
        && Date.now() - this.profileLastAttemptAt < PROFILE_FRESHNESS_MS
      ) {
        return this.reconcileSession() ? this.user : null
      }
      if (profileRequest) return profileRequest

      const requestAuthority = { token: this.token, generation: this.generation, commitment: this.commitment }
      this.profileLastAttemptAt = Date.now()
      profileRequest = (async () => {
        try {
          const user = await profileApi()
          if (!this.reconcileSession() || !sameAuthority(requestAuthority, tokenStorage.getSnapshot())) return this.user
          this.user = user
          boundUserStorage.set(user, tokenStorage.getSnapshot())
          return user
        } catch (error) {
          if (sameAuthority(requestAuthority, tokenStorage.getSnapshot()) && shouldInvalidateAdminSession(error)) {
            const handled = await invalidateAdminSession()
            // Store tests and non-router consumers may call the action before
            // the app-level handler is installed. Keep the state safe there,
            // while the normal runtime follows the central handler exactly once.
            if (!handled && sameAuthority(requestAuthority, tokenStorage.getSnapshot())) this.clearSession()
            return null
          }
          return this.reconcileSession() ? this.user : null
        } finally {
          profileRequest = null
        }
      })()
      return profileRequest
    },
    async logout() {
      const acceptedToken = tokenStorage.getSnapshot()?.token ?? ''
      this.clearSession()
      try {
        await logoutApi(acceptedToken)
      } catch {
        // Local logout must complete even when server-side token revocation is unavailable.
      }
    },
    async changePassword(payload: ChangePasswordPayload): Promise<boolean> {
      const requestAuthority = tokenStorage.getSnapshot()
      await changePasswordApi(payload)
      if (!requestAuthority || !sameAuthority(requestAuthority, tokenStorage.getSnapshot())) {
        this.reconcileSession(true)
        return false
      }
      this.clearSession()
      return true
    },
    async logoutAll(): Promise<boolean> {
      const requestAuthority = tokenStorage.getSnapshot()
      await logoutAllApi()
      if (!requestAuthority || !sameAuthority(requestAuthority, tokenStorage.getSnapshot())) {
        this.reconcileSession(true)
        return false
      }
      this.clearSession()
      return true
    },
    clearSession() {
      this.loginRequestVersion += 1
      this.loading = false
      this.clearMemorySession()
      try {
        tokenStorage.remove()
      } catch {
        // Local memory logout must not be blocked by unavailable persistence.
      }
      clearBoundUser()
    },
  },
})

export const useAuthStore = Object.assign(
  (...args: Parameters<typeof useAuthStoreDefinition>) => {
    const store = useAuthStoreDefinition(...args)
    store.ensureStorageSync()
    const existing = Object.getOwnPropertyDescriptor(store, 'isAuthenticated')
    if (!existing?.get || existing.get.name !== 'durableIsAuthenticated') {
      Object.defineProperty(store, 'isAuthenticated', {
        configurable: true,
        enumerable: true,
        get: function durableIsAuthenticated() {
          return sameAuthority(store, tokenStorage.getSnapshot())
        },
      })
    }
    return store
  },
  { $id: useAuthStoreDefinition.$id },
) as typeof useAuthStoreDefinition
