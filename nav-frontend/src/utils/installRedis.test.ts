import { describe, expect, it } from 'vitest'
import {
  buildInstallRedisConfig,
  isInstallRedisTicketExpired,
  isInstallRedisTlsMode,
  isValidInstallRedisUsername,
  normalizeConfigureInstallRedisResult,
  normalizeInstallRedisTestResult,
  type InstallRedisFormValue,
} from './installRedis'

function redisForm(overrides: Partial<InstallRedisFormValue> = {}): InstallRedisFormValue {
  return {
    host: ' redis.example.com ',
    port: 6379,
    username: ' navigation_app ',
    password: 'redis-secret',
    database: 2,
    tlsMode: 'SYSTEM',
    caCertificatePem: '',
    acknowledgeInsecureTransport: false,
    connectTimeoutSeconds: 3,
    readTimeoutSeconds: 4,
    ...overrides,
  }
}

describe('installation Redis request shaping', () => {
  it('uses the system trust store without sending empty optional secrets', () => {
    expect(buildInstallRedisConfig(redisForm({ username: ' ' }))).toEqual({
      host: 'redis.example.com',
      port: 6379,
      password: 'redis-secret',
      database: 2,
      tlsMode: 'SYSTEM',
      connectTimeoutSeconds: 3,
      readTimeoutSeconds: 4,
    })
  })

  it('sends a custom CA only in CUSTOM_CA mode', () => {
    const caCertificatePem = '-----BEGIN CERTIFICATE-----\nCA\n-----END CERTIFICATE-----'
    expect(buildInstallRedisConfig(redisForm({ tlsMode: 'CUSTOM_CA', caCertificatePem })))
      .toMatchObject({ tlsMode: 'CUSTOM_CA', caCertificatePem })
    expect(buildInstallRedisConfig(redisForm({ tlsMode: 'SYSTEM', caCertificatePem })))
      .not.toHaveProperty('caCertificatePem')
  })

  it('sends the explicit private-network acknowledgement only when TLS is disabled', () => {
    expect(buildInstallRedisConfig(redisForm({
      tlsMode: 'DISABLED',
      acknowledgeInsecureTransport: true,
      caCertificatePem: 'must-not-leak',
    }))).toMatchObject({
      tlsMode: 'DISABLED',
      acknowledgeInsecureTransport: true,
    })
    expect(buildInstallRedisConfig(redisForm({ tlsMode: 'SYSTEM' })))
      .not.toHaveProperty('acknowledgeInsecureTransport')
  })

  it('exposes only the three backend-supported TLS modes', () => {
    expect(isInstallRedisTlsMode('SYSTEM')).toBe(true)
    expect(isInstallRedisTlsMode('CUSTOM_CA')).toBe(true)
    expect(isInstallRedisTlsMode('DISABLED')).toBe(true)
    expect(isInstallRedisTlsMode('REQUIRE')).toBe(false)
  })
})

describe('Redis ACL username validation', () => {
  it.each(['', 'default', 'nav_app', '用户'])('accepts a compact username: %s', (value) => {
    expect(isValidInstallRedisUsername(value)).toBe(true)
  })

  it.each(['nav app', ' nav', 'nav\napp', `nav${String.fromCharCode(0x80)}app`])(
    'rejects whitespace or control characters: %s',
    (value) => expect(isValidInstallRedisUsername(value)).toBe(false),
  )
})

describe('installation Redis response validation', () => {
  const validResult = {
    ok: true,
    connectionTicket: 'c'.repeat(64),
    expiresAt: '2026-08-16T10:05:00Z',
  }

  it('accepts the minimal one-time ticket response and detects expiry', () => {
    const result = normalizeInstallRedisTestResult(validResult)
    expect(result).toEqual(validResult)
    expect(isInstallRedisTicketExpired(result, Date.parse('2026-08-16T10:04:59Z'))).toBe(false)
    expect(isInstallRedisTicketExpired(result, Date.parse('2026-08-16T10:05:00Z'))).toBe(true)
  })

  it('rejects malformed or expired-looking response fields', () => {
    expect(() => normalizeInstallRedisTestResult({ ...validResult, connectionTicket: 'short' }))
      .toThrow('Redis 连接测试响应缺少必要字段')
    expect(() => normalizeInstallRedisTestResult({ ...validResult, expiresAt: 'not-a-date' }))
      .toThrow('Redis 连接测试响应缺少必要字段')
  })

  it('requires an explicit restart decision', () => {
    expect(normalizeConfigureInstallRedisResult({ configured: true, restartRequired: true }))
      .toEqual({ configured: true, restartRequired: true })
    expect(normalizeConfigureInstallRedisResult({ configured: true, restartRequired: false }))
      .toEqual({ configured: true, restartRequired: false })
    expect(() => normalizeConfigureInstallRedisResult({ configured: true }))
      .toThrow('Redis 配置响应缺少必要字段')
  })
})
