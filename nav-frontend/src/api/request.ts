import axios, { type AxiosError, type AxiosResponse } from 'axios'
import type { ApiResponse } from '@/types/common'
import { tokenStorage } from '@/utils/storage'
import {
  isProtectedAdminRequest,
  requestMatchesCurrentAdminToken,
} from '@/utils/requestAuth'
import { shouldInvalidateAdminSession } from '@/utils/httpError'
import { invalidateAdminSession } from '@/utils/sessionInvalidation'

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 12000,
  headers: {
    'Content-Type': 'application/json',
  },
})

request.interceptors.request.use((config) => {
  if (isProtectedAdminRequest(config.url)) {
    const token = tokenStorage.get()
    if (token) config.headers.Authorization = `Bearer ${token}`
  } else {
    config.headers.delete('Authorization')
  }
  if (typeof FormData !== 'undefined' && config.data instanceof FormData) {
    config.headers.delete('Content-Type')
  }
  return config
})

request.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    const isProtectedAdmin = isProtectedAdminRequest(error.config?.url)
    const headers = error.config?.headers
    const authorization = typeof headers?.get === 'function'
      ? headers.get('Authorization')
      : headers?.Authorization
    const requestStillBelongsToCurrentSession = requestMatchesCurrentAdminToken(
      authorization,
      tokenStorage.get(),
    )
    if (
      isProtectedAdmin
      && requestStillBelongsToCurrentSession
      && shouldInvalidateAdminSession(error)
    ) {
      void invalidateAdminSession().catch(() => undefined)
    }
    const payload = error.response?.data
    if (typeof payload === 'object' && payload !== null && 'message' in payload) {
      const message = (payload as { message?: unknown }).message
      if (typeof message === 'string' && message) {
        return Promise.reject(Object.assign(new Error(message), { status: error.response?.status }))
      }
    }
    return Promise.reject(error)
  },
)

export function unwrapApiData<T>(response: AxiosResponse<ApiResponse<T> | T>): T {
  const payload = response.data
  if (
    typeof payload === 'object' &&
    payload !== null &&
    'code' in payload
  ) {
    const apiPayload = payload as ApiResponse<T>
    if (apiPayload.code !== 0 && (apiPayload.code < 200 || apiPayload.code >= 300)) {
      throw new Error(apiPayload.message || '请求失败')
    }
    return apiPayload.data as T
  }
  return payload as T
}

export default request
