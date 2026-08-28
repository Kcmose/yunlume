import type {
  ConfigureInstallRedisResult,
  InstallRedisConfig,
  InstallRedisTestResult,
  InstallRedisTlsMode,
} from '@/types/install'

export interface InstallRedisFormValue {
  host: string
  port: number
  username: string
  password: string
  database: number
  tlsMode: InstallRedisTlsMode
  caCertificatePem: string
  acknowledgeInsecureTransport: boolean
  connectTimeoutSeconds: number
  readTimeoutSeconds: number
}

const TLS_MODES: InstallRedisTlsMode[] = ['SYSTEM', 'CUSTOM_CA', 'DISABLED']
const TICKET_PATTERN = /^[0-9a-f]{64}$/

export function buildInstallRedisConfig(form: InstallRedisFormValue): InstallRedisConfig {
  const config: InstallRedisConfig = {
    host: form.host.trim(),
    port: Number(form.port),
    password: form.password,
    database: Number(form.database),
    tlsMode: form.tlsMode,
    connectTimeoutSeconds: Number(form.connectTimeoutSeconds),
    readTimeoutSeconds: Number(form.readTimeoutSeconds),
  }
  const username = form.username.trim()
  if (username) config.username = username
  if (form.tlsMode === 'CUSTOM_CA') config.caCertificatePem = form.caCertificatePem
  if (form.tlsMode === 'DISABLED') {
    config.acknowledgeInsecureTransport = form.acknowledgeInsecureTransport
  }
  return config
}

export function normalizeInstallRedisTestResult(payload: unknown): InstallRedisTestResult {
  if (typeof payload !== 'object' || payload === null) {
    throw new Error('Redis 连接测试响应格式无效')
  }
  const source = payload as Record<string, unknown>
  if (
    source.ok !== true
    || typeof source.connectionTicket !== 'string'
    || !TICKET_PATTERN.test(source.connectionTicket)
    || typeof source.expiresAt !== 'string'
    || !Number.isFinite(Date.parse(source.expiresAt))
  ) {
    throw new Error('Redis 连接测试响应缺少必要字段')
  }
  return {
    ok: true,
    connectionTicket: source.connectionTicket,
    expiresAt: source.expiresAt,
  }
}

export function normalizeConfigureInstallRedisResult(payload: unknown): ConfigureInstallRedisResult {
  if (typeof payload !== 'object' || payload === null) {
    throw new Error('Redis 配置响应格式无效')
  }
  const source = payload as Record<string, unknown>
  if (source.configured !== true || typeof source.restartRequired !== 'boolean') {
    throw new Error('Redis 配置响应缺少必要字段')
  }
  return { configured: true, restartRequired: source.restartRequired }
}

export function isInstallRedisTicketExpired(
  result: Pick<InstallRedisTestResult, 'expiresAt'>,
  now = Date.now(),
): boolean {
  const expiresAt = Date.parse(result.expiresAt)
  return !Number.isFinite(expiresAt) || expiresAt <= now
}

export function isInstallRedisTlsMode(value: unknown): value is InstallRedisTlsMode {
  return typeof value === 'string' && TLS_MODES.includes(value as InstallRedisTlsMode)
}

export function isValidInstallRedisUsername(value: unknown): boolean {
  return typeof value === 'string'
    && value.length <= 128
    && !/[\s\u0000-\u001f\u007f-\u009f]/u.test(value)
}
