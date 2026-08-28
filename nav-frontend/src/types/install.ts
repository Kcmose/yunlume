export type InstallState =
  | 'DATABASE_REQUIRED'
  | 'REDIS_REQUIRED'
  | 'REQUIRED'
  | 'COMPLETED'
  | 'DISABLED'
  | 'UNKNOWN'

export type InstallDatabaseSslMode =
  | 'REQUIRE'
  | 'VERIFY_CA'
  | 'VERIFY_FULL'

export type InstallDatabaseSchemaState = 'EMPTY' | 'READY_UNINSTALLED' | 'READY_INSTALLED'

export interface InstallDatabaseConfig {
  host: string
  port: number
  database: string
  username: string
  password: string
  sslMode: InstallDatabaseSslMode
  caCertificatePem?: string
  acknowledgeUnverifiedTls?: boolean
}

export interface InstallDatabaseTestResult {
  ok: true
  connectionTicket: string
  expiresAt: string
  schemaState: InstallDatabaseSchemaState
  requiresInitialization: boolean
}

export interface ConfigureInstallDatabasePayload {
  connectionTicket: string
  initializeSchema: boolean
}

export interface ConfigureInstallDatabaseResult {
  configured: true
  initialized: boolean
  installed: boolean
  restartRequired: boolean
}

export type InstallRedisTlsMode = 'SYSTEM' | 'CUSTOM_CA' | 'DISABLED'

export interface InstallRedisConfig {
  host: string
  port: number
  username?: string
  password: string
  database: number
  tlsMode: InstallRedisTlsMode
  caCertificatePem?: string
  acknowledgeInsecureTransport?: boolean
  connectTimeoutSeconds: number
  readTimeoutSeconds: number
}

export interface InstallRedisTestResult {
  ok: true
  connectionTicket: string
  expiresAt: string
}

export interface ConfigureInstallRedisPayload {
  connectionTicket: string
}

export interface ConfigureInstallRedisResult {
  configured: true
  restartRequired: boolean
}

export interface InstallEnvironmentCheck {
  ok: boolean
  message: string
}

export interface InstallEnvironmentChecks {
  database: InstallEnvironmentCheck
  schema: InstallEnvironmentCheck
  siteConfig: InstallEnvironmentCheck
  upload: InstallEnvironmentCheck
  redis: InstallEnvironmentCheck
}

export interface InstallStatus {
  state: InstallState
  installationRequired: boolean
  webInstallEnabled: boolean
  ready: boolean
}

export interface InstallCheckResult {
  ready: boolean
  checks: InstallEnvironmentChecks
}

export interface CompleteInstallationPayload {
  siteName: string
  siteDescription: string
  username: string
  nickname: string
  password: string
  confirmPassword: string
}

export interface CompleteInstallationResult {
  installed: true
}
