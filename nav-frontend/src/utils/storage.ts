const TOKEN_KEY = 'ilinks_admin_token'
const USER_KEY = 'ilinks_admin_user'

export const tokenStorage = {
  get: () => localStorage.getItem(TOKEN_KEY) ?? '',
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  remove: () => localStorage.removeItem(TOKEN_KEY),
}

export const jsonStorage = {
  get<T>(key: string): T | null {
    const raw = localStorage.getItem(key)
    if (!raw) return null
    try {
      return JSON.parse(raw) as T
    } catch {
      return null
    }
  },
  set<T>(key: string, value: T) {
    localStorage.setItem(key, JSON.stringify(value))
  },
  remove(key: string) {
    localStorage.removeItem(key)
  },
}

export { USER_KEY }
