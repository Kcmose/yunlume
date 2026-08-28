export interface LoginPayload {
  username: string
  password: string
}

export interface ChangePasswordPayload {
  currentPassword: string
  newPassword: string
  confirmPassword: string
}

export interface AdminUser {
  id: number
  username: string
  nickname: string
  avatar?: string
  role: string
}

export interface LoginResult {
  token: string
  tokenType?: string
  expiresIn?: number
  user: AdminUser
}
