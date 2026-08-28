import request, { unwrapApiData } from './request'
import type {
  AdminUser,
  ChangePasswordPayload,
  LoginPayload,
  LoginResult,
} from '@/types/auth'

export async function loginApi(payload: LoginPayload): Promise<LoginResult> {
  return unwrapApiData(await request.post('/admin/auth/login', payload))
}

export async function profileApi(): Promise<AdminUser> {
  return unwrapApiData(await request.get('/admin/auth/profile'))
}

export async function logoutApi(): Promise<void> {
  return unwrapApiData(await request.post('/admin/auth/logout'))
}

export async function changePasswordApi(payload: ChangePasswordPayload): Promise<void> {
  return unwrapApiData(await request.put('/admin/auth/password', payload))
}

export async function logoutAllApi(): Promise<void> {
  return unwrapApiData(await request.post('/admin/auth/logout-all'))
}
