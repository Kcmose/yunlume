import type {
  ConfigureInstallDatabaseResult,
  InstallDatabaseConfig,
  InstallDatabaseSchemaState,
  InstallDatabaseSslMode,
  InstallDatabaseTestResult,
} from '@/types/install'

export interface InstallDatabaseFormValue {
  host: string
  port: number
  database: string
  username: string
  password: string
  sslMode: InstallDatabaseSslMode
  caCertificatePem: string
  acknowledgeUnverifiedTls: boolean
}

const SSL_MODES: InstallDatabaseSslMode[] = [
  'REQUIRE',
  'VERIFY_CA',
  'VERIFY_FULL',
]
const SCHEMA_STATES: InstallDatabaseSchemaState[] = [
  'EMPTY',
  'READY_UNINSTALLED',
  'READY_INSTALLED',
]
const TICKET_PATTERN = /^[0-9a-f]{64}$/

export function buildInstallDatabaseConfig(
  form: InstallDatabaseFormValue,
): InstallDatabaseConfig {
  const config: InstallDatabaseConfig = {
    host: form.host.trim(),
    port: Number(form.port),
    database: form.database.trim(),
    username: form.username.trim(),
    password: form.password,
    sslMode: form.sslMode,
  }
  if (form.sslMode === 'REQUIRE') {
    config.acknowledgeUnverifiedTls = form.acknowledgeUnverifiedTls
  } else {
    config.caCertificatePem = form.caCertificatePem
  }
  return config
}

export function normalizeInstallDatabaseTestResult(payload: unknown): InstallDatabaseTestResult {
  if (typeof payload !== 'object' || payload === null) {
    throw new Error('数据库连接测试响应格式无效')
  }
  const source = payload as Record<string, unknown>
  if (
    source.ok !== true
    || typeof source.connectionTicket !== 'string'
    || !TICKET_PATTERN.test(source.connectionTicket)
    || typeof source.expiresAt !== 'string'
    || !Number.isFinite(Date.parse(source.expiresAt))
    || typeof source.schemaState !== 'string'
    || !SCHEMA_STATES.includes(source.schemaState as InstallDatabaseSchemaState)
    || typeof source.requiresInitialization !== 'boolean'
  ) {
    throw new Error('数据库连接测试响应缺少必要字段')
  }

  return {
    ok: true,
    connectionTicket: source.connectionTicket,
    expiresAt: source.expiresAt,
    schemaState: source.schemaState as InstallDatabaseSchemaState,
    requiresInitialization: source.requiresInitialization,
  }
}

export function normalizeConfigureInstallDatabaseResult(
  payload: unknown,
): ConfigureInstallDatabaseResult {
  if (typeof payload !== 'object' || payload === null) {
    throw new Error('数据库配置响应格式无效')
  }
  const source = payload as Record<string, unknown>
  if (
    source.configured !== true
    || typeof source.initialized !== 'boolean'
    || typeof source.installed !== 'boolean'
    || typeof source.restartRequired !== 'boolean'
  ) {
    throw new Error('数据库配置响应缺少必要字段')
  }
  return {
    configured: true,
    initialized: source.initialized,
    installed: source.installed,
    restartRequired: source.restartRequired,
  }
}

export function isInstallDatabaseTicketExpired(
  result: Pick<InstallDatabaseTestResult, 'expiresAt'>,
  now = Date.now(),
): boolean {
  const expiresAt = Date.parse(result.expiresAt)
  return !Number.isFinite(expiresAt) || expiresAt <= now
}

export function installDatabaseSchemaLabel(state: InstallDatabaseSchemaState): string {
  if (state === 'EMPTY') return '空数据库，可初始化结构'
  if (state === 'READY_UNINSTALLED') return '结构完整，尚未创建站点'
  return '已存在完成安装的站点'
}

export function isInstallDatabaseSslMode(value: unknown): value is InstallDatabaseSslMode {
  return typeof value === 'string' && SSL_MODES.includes(value as InstallDatabaseSslMode)
}
