import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CompleteInstallationPayload } from '@/types/install'

const requestMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('./request', () => ({
  default: requestMocks,
  unwrapApiData: (response: { data: unknown }) => response.data,
}))

import {
  checkInstallationApi,
  completeInstallationApi,
  configureInstallDatabaseApi,
  configureInstallRedisApi,
  getInstallStatusApi,
  testInstallDatabaseApi,
  testInstallRedisApi,
} from './install.api'

describe('installation API contract', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('uses a short status timeout so a backend outage does not block public routing', async () => {
    const status = {
      state: 'UNKNOWN',
      installationRequired: false,
      webInstallEnabled: false,
      ready: false,
    }
    requestMocks.get.mockResolvedValue({ data: status })

    await expect(getInstallStatusApi()).resolves.toEqual(status)
    expect(requestMocks.get).toHaveBeenCalledWith('/install/status', { timeout: 2500 })
  })

  it('requests detailed installation checks without a credential header', async () => {
    const result = {
      ready: true,
      checks: {
        database: { ok: true, message: 'ok' },
        schema: { ok: true, message: 'ok' },
        siteConfig: { ok: true, message: 'ok' },
        upload: { ok: true, message: 'ok' },
        redis: { ok: true, message: 'ok' },
      },
    }
    requestMocks.post.mockResolvedValue({ data: result })

    await expect(checkInstallationApi()).resolves.toEqual(result)
    expect(requestMocks.post).toHaveBeenCalledWith('/install/check', undefined, {
      timeout: 12000,
    })
  })

  it('submits only the installation completion payload', async () => {
    const payload: CompleteInstallationPayload = {
      siteName: 'yunlume',
      siteDescription: 'Navigation',
      username: 'admin',
      nickname: '管理员',
      password: 'Example!Pass2026',
      confirmPassword: 'Example!Pass2026',
    }
    requestMocks.post.mockResolvedValue({ data: { installed: true } })

    await expect(completeInstallationApi(payload)).resolves.toEqual({ installed: true })
    expect(requestMocks.post).toHaveBeenCalledWith('/install/complete', payload, {
      timeout: 20000,
    })
  })

  it('submits external database credentials only to the ticket-producing test endpoint', async () => {
    const ticket = 'a'.repeat(64)
    requestMocks.post.mockResolvedValue({
      data: {
        ok: true,
        connectionTicket: ticket,
        expiresAt: '2026-08-15T12:05:00Z',
        schemaState: 'EMPTY',
        requiresInitialization: true,
      },
    })
    const database = {
      host: 'db.example.com',
      port: 5432,
      database: 'navigation',
      username: 'navigation_app',
      password: 'database-secret',
      sslMode: 'VERIFY_FULL' as const,
      caCertificatePem: '-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----',
    }

    await expect(testInstallDatabaseApi(database)).resolves.toMatchObject({
      connectionTicket: ticket,
      schemaState: 'EMPTY',
    })
    expect(requestMocks.post).toHaveBeenCalledWith('/install/database/test', database, {
      timeout: 20000,
    })
  })

  it('consumes only the one-time database ticket when applying configuration', async () => {
    const payload = {
      connectionTicket: 'b'.repeat(64),
      initializeSchema: true,
    }
    requestMocks.post.mockResolvedValue({
      data: {
        configured: true,
        initialized: true,
        installed: false,
        restartRequired: true,
      },
    })

    await expect(configureInstallDatabaseApi(payload)).resolves.toEqual({
      configured: true,
      initialized: true,
      installed: false,
      restartRequired: true,
    })
    expect(requestMocks.post).toHaveBeenCalledWith('/install/database/configure', payload, {
      timeout: 90000,
    })
    expect(payload).not.toHaveProperty('password')
  })

  it('submits Redis credentials only to the ticket-producing test endpoint', async () => {
    const ticket = 'c'.repeat(64)
    requestMocks.post.mockResolvedValue({
      data: {
        ok: true,
        connectionTicket: ticket,
        expiresAt: '2026-08-16T12:05:00Z',
      },
    })
    const redis = {
      host: 'redis.example.com',
      port: 6380,
      username: 'navigation_app',
      password: 'redis-secret',
      database: 0,
      tlsMode: 'SYSTEM' as const,
      connectTimeoutSeconds: 3,
      readTimeoutSeconds: 3,
    }

    await expect(testInstallRedisApi(redis)).resolves.toMatchObject({
      connectionTicket: ticket,
    })
    expect(requestMocks.post).toHaveBeenCalledWith('/install/redis/test', redis, {
      timeout: 75000,
    })
  })

  it('consumes only the one-time Redis ticket when applying configuration', async () => {
    const payload = { connectionTicket: 'd'.repeat(64) }
    requestMocks.post.mockResolvedValue({
      data: { configured: true, restartRequired: true },
    })

    await expect(configureInstallRedisApi(payload)).resolves.toEqual({
      configured: true,
      restartRequired: true,
    })
    expect(requestMocks.post).toHaveBeenCalledWith('/install/redis/configure', payload, {
      timeout: 90000,
    })
    expect(payload).not.toHaveProperty('password')
    expect(payload).not.toHaveProperty('caCertificatePem')
  })
})
