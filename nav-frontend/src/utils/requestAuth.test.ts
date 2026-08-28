import { describe, expect, it } from 'vitest'
import {
  isProtectedAdminRequest,
  requestMatchesCurrentAdminToken,
} from './requestAuth'

describe('isProtectedAdminRequest', () => {
  it.each([
    '/admin/site-config',
    '/api/admin/categories',
    'admin/bookmarks?visible=true',
    'https://example.com/api/admin/auth/profile',
  ])('recognizes protected admin endpoint %s', (url) => {
    expect(isProtectedAdminRequest(url)).toBe(true)
  })

  it.each([
    undefined,
    '/',
    '/public/site-config',
    '/api/public/navigation',
    '/admin/auth/login',
    '/api/admin/auth/login?redirect=%2Fadmin',
    'https://example.com/api/admin/auth/login',
    '/install/status',
    '/api/install/check',
    '/api/install/database/test',
    '/api/install/database/configure',
    '/api/install/redis/test',
    '/api/install/redis/configure',
    '/api/install/complete',
    '/administrator/site-config',
  ])('keeps anonymous endpoint %s free of admin authorization', (url) => {
    expect(isProtectedAdminRequest(url)).toBe(false)
  })
})

describe('admin response session ownership', () => {
  it('accepts only the bearer token that is still current', () => {
    expect(requestMatchesCurrentAdminToken('Bearer current-token', 'current-token')).toBe(true)
    expect(requestMatchesCurrentAdminToken('Bearer old-token', 'current-token')).toBe(false)
    expect(requestMatchesCurrentAdminToken(undefined, 'current-token')).toBe(false)
    expect(requestMatchesCurrentAdminToken(undefined, '')).toBe(true)
  })
})
