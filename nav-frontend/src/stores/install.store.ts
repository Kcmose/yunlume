import { defineStore } from 'pinia'
import { getInstallStatusApi } from '@/api/install.api'
import type { InstallStatus } from '@/types/install'

export const INSTALL_STATUS_FRESHNESS_MS = 30_000
let statusRequest: Promise<InstallStatus | null> | null = null

function errorMessage(error: unknown): string {
  return error instanceof Error && error.message
    ? error.message
    : '无法读取安装状态，请检查服务端连接后重试'
}

export const useInstallStore = defineStore('install', {
  state: () => ({
    status: null as InstallStatus | null,
    loading: false,
    error: '',
    lastCheckedAt: 0,
  }),
  actions: {
    async fetchStatus(force = false): Promise<InstallStatus | null> {
      if (
        !force
        && this.status
        && Date.now() - this.lastCheckedAt < INSTALL_STATUS_FRESHNESS_MS
      ) {
        return this.status
      }
      if (statusRequest) return statusRequest

      this.loading = true
      this.error = ''
      statusRequest = (async () => {
        try {
          const status = await getInstallStatusApi()
          this.status = status
          this.lastCheckedAt = Date.now()
          return status
        } catch (error) {
          this.error = errorMessage(error)
          return this.status
        } finally {
          this.loading = false
          statusRequest = null
        }
      })()
      return statusRequest
    },
    markInstalled() {
      this.status = {
        state: 'COMPLETED',
        installationRequired: false,
        webInstallEnabled: false,
        ready: true,
      }
      this.error = ''
      this.lastCheckedAt = Date.now()
    },
  },
})
