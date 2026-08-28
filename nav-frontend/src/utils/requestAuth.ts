function requestPath(url: string | undefined): string {
  if (!url) return ''

  try {
    const pathname = new URL(url, 'http://localhost').pathname
    return pathname.startsWith('/api/') ? pathname.slice(4) : pathname
  } catch {
    const pathname = url.split(/[?#]/, 1)[0] ?? ''
    const normalized = pathname.startsWith('/') ? pathname : `/${pathname}`
    return normalized.startsWith('/api/') ? normalized.slice(4) : normalized
  }
}

/**
 * The admin token is only meaningful for protected management endpoints.
 * Public requests must stay anonymous so an expired admin token cannot make
 * the public homepage fail with 401.
 */
export function isProtectedAdminRequest(url: string | undefined): boolean {
  const path = requestPath(url)
  if (!/^\/admin(?:\/|$)/.test(path)) return false
  return !/^\/admin\/auth\/login(?:\/|$)/.test(path)
}

/**
 * A delayed 401 from an older token must never invalidate a newer login.
 * A request without a bearer token is current only while storage is empty.
 */
export function requestMatchesCurrentAdminToken(
  authorization: unknown,
  currentToken: string,
): boolean {
  const requestAuthorization = typeof authorization === 'string'
    ? authorization.trim()
    : ''
  if (!requestAuthorization) return !currentToken
  return Boolean(currentToken) && requestAuthorization === `Bearer ${currentToken}`
}
