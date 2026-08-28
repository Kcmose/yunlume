import type {
  InstallCheckResult,
  InstallEnvironmentCheck,
  InstallEnvironmentChecks,
  InstallState,
  InstallStatus,
} from '@/types/install'

export type InstallRouteDecision = 'allow' | 'install' | 'login'

const CHECK_KEYS: Array<keyof InstallEnvironmentChecks> = [
  'database',
  'schema',
  'siteConfig',
  'upload',
  'redis',
]
const INSTALL_STATES: InstallState[] = [
  'DATABASE_REQUIRED',
  'REDIS_REQUIRED',
  'REQUIRED',
  'COMPLETED',
  'DISABLED',
  'UNKNOWN',
]

/**
 * Installation routing is deliberately fail-closed: an unavailable or
 * malformed status response never turns an existing site into an installer.
 */
export function decideInstallRoute(
  routeName: string | symbol | null | undefined,
  status: InstallStatus | null,
): InstallRouteDecision {
  const isInstallRoute = routeName === 'install'
  if (!status) return 'allow'
  if (status.state === 'COMPLETED') return isInstallRoute ? 'login' : 'allow'
  if (status.state === 'UNKNOWN') return 'allow'
  return isInstallRoute ? 'allow' : 'install'
}

export function normalizeInstallStatus(payload: unknown): InstallStatus {
  if (typeof payload !== 'object' || payload === null) {
    throw new Error('安装状态响应格式无效')
  }

  const source = payload as Record<string, unknown>
  if (
    typeof source.state !== 'string'
    || !INSTALL_STATES.includes(source.state as InstallState)
    || typeof source.installationRequired !== 'boolean'
    || typeof source.webInstallEnabled !== 'boolean'
    || typeof source.ready !== 'boolean'
  ) {
    throw new Error('安装状态响应缺少必要字段')
  }

  return {
    state: source.state as InstallState,
    installationRequired: source.installationRequired,
    webInstallEnabled: source.webInstallEnabled,
    ready: source.ready,
  }
}

export function normalizeInstallCheckResult(payload: unknown): InstallCheckResult {
  if (typeof payload !== 'object' || payload === null) {
    throw new Error('安装环境检查响应格式无效')
  }
  const source = payload as Record<string, unknown>
  const checks = source.checks
  if (typeof source.ready !== 'boolean' || typeof checks !== 'object' || checks === null) {
    throw new Error('安装环境检查响应缺少必要字段')
  }
  const checkRecord = checks as Record<string, unknown>
  if (CHECK_KEYS.some((key) => {
    const check = checkRecord[key]
    return typeof check !== 'object'
      || check === null
      || typeof (check as Record<string, unknown>).ok !== 'boolean'
      || typeof (check as Record<string, unknown>).message !== 'string'
  })) {
    throw new Error('安装环境检查响应格式无效')
  }
  const normalizedCheck = (key: keyof InstallEnvironmentChecks): InstallEnvironmentCheck => {
    const check = checkRecord[key] as Record<string, unknown>
    return { ok: check.ok as boolean, message: check.message as string }
  }
  return {
    ready: source.ready,
    checks: {
      database: normalizedCheck('database'),
      schema: normalizedCheck('schema'),
      siteConfig: normalizedCheck('siteConfig'),
      upload: normalizedCheck('upload'),
      redis: normalizedCheck('redis'),
    },
  }
}
