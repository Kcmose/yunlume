import { describe, expect, it } from 'vitest'
import type { InstallStatus } from '@/types/install'
import {
  decideInstallRoute,
  normalizeInstallCheckResult,
  normalizeInstallStatus,
} from './installState'

const requiredStatus: InstallStatus = {
  state: 'REQUIRED',
  installationRequired: true,
  webInstallEnabled: true,
  ready: true,
}
const checkResult = {
  ready: true,
  checks: {
    database: { ok: true, message: '数据库连接正常' },
    schema: { ok: true, message: '数据库结构正常' },
    siteConfig: { ok: true, message: '站点可以初始化' },
    upload: { ok: true, message: '上传目录可写' },
    redis: { ok: true, message: 'Redis 连接正常' },
  },
}

describe('installation route decision', () => {
  it('redirects to the installer only after the server explicitly requires installation', () => {
    expect(decideInstallRoute('portal-home', requiredStatus)).toBe('install')
    expect(decideInstallRoute('admin-login', requiredStatus)).toBe('install')
    expect(decideInstallRoute('install', requiredStatus)).toBe('allow')
  })

  it('fails closed when installation status is unavailable', () => {
    expect(decideInstallRoute('portal-home', null)).toBe('allow')
    expect(decideInstallRoute('admin-dashboard', null)).toBe('allow')
    expect(decideInstallRoute('install', null)).toBe('allow')
  })

  it('keeps a disabled fresh state on the installer', () => {
    const pending: InstallStatus = {
      state: 'DISABLED',
      installationRequired: true,
      webInstallEnabled: false,
      ready: false,
    }
    expect(decideInstallRoute('portal-home', pending)).toBe('install')
    expect(decideInstallRoute('install', pending)).toBe('allow')
  })

  it('routes an explicitly unconfigured database to the installer without treating it as ready', () => {
    const databaseRequired: InstallStatus = {
      state: 'DATABASE_REQUIRED',
      installationRequired: true,
      webInstallEnabled: true,
      ready: false,
    }
    expect(decideInstallRoute('portal-home', databaseRequired)).toBe('install')
    expect(decideInstallRoute('install', databaseRequired)).toBe('allow')
    expect(normalizeInstallStatus(databaseRequired)).toEqual(databaseRequired)
  })

  it('keeps an explicitly unconfigured Redis instance in the one-time installer', () => {
    const redisRequired: InstallStatus = {
      state: 'REDIS_REQUIRED',
      installationRequired: true,
      webInstallEnabled: true,
      ready: false,
    }
    expect(decideInstallRoute('portal-home', redisRequired)).toBe('install')
    expect(decideInstallRoute('install', redisRequired)).toBe('allow')
    expect(normalizeInstallStatus(redisRequired)).toEqual(redisRequired)
  })

  it('does not hijack existing routes when the server cannot determine installation state', () => {
    const unknown: InstallStatus = {
      state: 'UNKNOWN',
      installationRequired: false,
      webInstallEnabled: false,
      ready: false,
    }
    expect(decideInstallRoute('portal-home', unknown)).toBe('allow')
    expect(decideInstallRoute('admin-dashboard', unknown)).toBe('allow')
    expect(decideInstallRoute('install', unknown)).toBe('allow')
  })

  it('sends an installed site away from the one-time installer', () => {
    const completed: InstallStatus = {
      state: 'COMPLETED',
      installationRequired: false,
      webInstallEnabled: false,
      ready: true,
    }
    expect(decideInstallRoute('install', completed)).toBe('login')
    expect(decideInstallRoute('portal-home', completed)).toBe('allow')
  })
})

describe('installation status normalization', () => {
  it('accepts the minimal public status response', () => {
    expect(normalizeInstallStatus(requiredStatus)).toEqual(requiredStatus)
  })

  it('accepts an installed response without infrastructure details', () => {
    const completed = {
      state: 'COMPLETED',
      installationRequired: false,
      webInstallEnabled: false,
      ready: true,
    }
    expect(normalizeInstallStatus(completed)).toEqual(completed)
  })

  it('normalizes detailed checks only from the protected check response', () => {
    expect(normalizeInstallCheckResult(checkResult)).toEqual(checkResult)
  })

  it('rejects malformed check details instead of treating them as installation state', () => {
    expect(() => normalizeInstallCheckResult({
      ...checkResult,
      checks: { ...checkResult.checks, redis: true },
    })).toThrow('安装环境检查响应格式无效')
  })
})
