import { describe, expect, it } from 'vitest'
import {
  buildInstallDatabaseConfig,
  isInstallDatabaseSslMode,
  isInstallDatabaseTicketExpired,
  normalizeConfigureInstallDatabaseResult,
  normalizeInstallDatabaseTestResult,
  type InstallDatabaseFormValue,
} from './installDatabase'

function externalForm(overrides: Partial<InstallDatabaseFormValue> = {}): InstallDatabaseFormValue {
  return {
    host: ' db.example.com ',
    port: 5432,
    database: ' navigation ',
    username: ' navigation_app ',
    password: 'database-secret',
    sslMode: 'VERIFY_FULL',
    caCertificatePem: '-----BEGIN CERTIFICATE-----\nCA\n-----END CERTIFICATE-----',
    acknowledgeUnverifiedTls: false,
    ...overrides,
  }
}

describe('installation database request shaping', () => {
  it('trims identifiers and includes the CA only for certificate-verifying modes', () => {
    expect(buildInstallDatabaseConfig(externalForm())).toEqual({
      host: 'db.example.com',
      port: 5432,
      database: 'navigation',
      username: 'navigation_app',
      password: 'database-secret',
      sslMode: 'VERIFY_FULL',
      caCertificatePem: '-----BEGIN CERTIFICATE-----\nCA\n-----END CERTIFICATE-----',
    })
  })

  it('sends the explicit risk acknowledgement instead of a CA in REQUIRE mode', () => {
    expect(buildInstallDatabaseConfig(externalForm({
      sslMode: 'REQUIRE',
      acknowledgeUnverifiedTls: true,
    }))).toMatchObject({
      sslMode: 'REQUIRE',
      acknowledgeUnverifiedTls: true,
    })
    expect(buildInstallDatabaseConfig(externalForm({
      sslMode: 'REQUIRE',
      acknowledgeUnverifiedTls: true,
    }))).not.toHaveProperty('caCertificatePem')
  })

  it('exposes only the three supported external TLS modes', () => {
    expect(isInstallDatabaseSslMode('VERIFY_FULL')).toBe(true)
    expect(isInstallDatabaseSslMode('VERIFY_CA')).toBe(true)
    expect(isInstallDatabaseSslMode('REQUIRE')).toBe(true)
    expect(isInstallDatabaseSslMode('PREFER')).toBe(false)
    expect(isInstallDatabaseSslMode('DISABLE')).toBe(false)
  })
})

describe('installation database response validation', () => {
  const validTestResult = {
    ok: true,
    connectionTicket: 'a'.repeat(64),
    expiresAt: '2026-08-15T12:05:00Z',
    schemaState: 'EMPTY',
    requiresInitialization: true,
  }

  it('accepts a strict one-time ticket response and detects expiry', () => {
    const result = normalizeInstallDatabaseTestResult(validTestResult)
    expect(result).toEqual(validTestResult)
    expect(isInstallDatabaseTicketExpired(result, Date.parse('2026-08-15T12:04:59Z'))).toBe(false)
    expect(isInstallDatabaseTicketExpired(result, Date.parse('2026-08-15T12:05:00Z'))).toBe(true)
  })

  it('rejects malformed tickets and unknown schema states', () => {
    expect(() => normalizeInstallDatabaseTestResult({
      ...validTestResult,
      connectionTicket: 'short',
    })).toThrow('数据库连接测试响应缺少必要字段')
    expect(() => normalizeInstallDatabaseTestResult({
      ...validTestResult,
      schemaState: 'PARTIAL',
    })).toThrow('数据库连接测试响应缺少必要字段')
  })

  it('requires restartRequired in the configure response', () => {
    expect(normalizeConfigureInstallDatabaseResult({
      configured: true,
      initialized: false,
      installed: false,
      restartRequired: true,
    })).toEqual({
      configured: true,
      initialized: false,
      installed: false,
      restartRequired: true,
    })
    expect(() => normalizeConfigureInstallDatabaseResult({
      configured: true,
      initialized: false,
      installed: false,
    })).toThrow('数据库配置响应缺少必要字段')
  })
})
