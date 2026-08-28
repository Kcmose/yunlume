export function getHttpStatus(error: unknown): number | undefined {
  if (typeof error !== 'object' || error === null) return undefined

  if ('status' in error && typeof error.status === 'number') return error.status

  if ('response' in error && typeof error.response === 'object' && error.response !== null) {
    const response = error.response as { status?: unknown }
    if (typeof response.status === 'number') return response.status
  }

  return undefined
}

export function shouldInvalidateAdminSession(error: unknown): boolean {
  const status = getHttpStatus(error)
  return status === 401 || status === 403
}
