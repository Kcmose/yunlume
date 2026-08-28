<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  CircleCheckFilled,
  CircleCloseFilled,
  Coin,
  Connection,
  CopyDocument,
  Key,
  Lock,
  Refresh,
  Right,
  Setting,
  User,
} from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  checkInstallationApi,
  completeInstallationApi,
  configureInstallDatabaseApi,
  configureInstallRedisApi,
  testInstallDatabaseApi,
  testInstallRedisApi,
} from '@/api/install.api'
import { useAuthStore } from '@/stores/auth.store'
import { useInstallStore } from '@/stores/install.store'
import type {
  CompleteInstallationPayload,
  InstallCheckResult,
  InstallDatabaseSslMode,
  InstallDatabaseTestResult,
  InstallEnvironmentCheck,
  InstallRedisTestResult,
  InstallRedisTlsMode,
  InstallState,
  InstallStatus,
} from '@/types/install'
import { getHttpStatus } from '@/utils/httpError'
import {
  buildInstallDatabaseConfig,
  installDatabaseSchemaLabel,
  isInstallDatabaseTicketExpired,
  type InstallDatabaseFormValue,
} from '@/utils/installDatabase'
import {
  buildPostgresProvisioningSql,
} from '@/utils/installPostgresProvisioning'
import { shouldAdvanceInstallOnEnter } from '@/utils/installKeyboard'
import {
  buildInstallRedisConfig,
  isInstallRedisTicketExpired,
  isValidInstallRedisUsername,
  type InstallRedisFormValue,
} from '@/utils/installRedis'
import { isInstallPrimaryActionDisabled, resolveInstallWizardEntry } from '@/utils/installWizard'
import { copyTextWithFallback } from '@/utils/clipboard'
import {
  evaluatePasswordPolicy,
  PASSWORD_MAX_LENGTH,
  PASSWORD_MIN_LENGTH,
} from '@/utils/passwordPolicy'

interface InstallForm extends CompleteInstallationPayload {
  database: InstallDatabaseFormValue
  redis: InstallRedisFormValue
  initializeSchema: boolean
  confirmationAccepted: boolean
}

interface CheckItem {
  key: string
  label: string
  check: InstallEnvironmentCheck
}

type InstallDatabaseTestSummary = Omit<InstallDatabaseTestResult, 'connectionTicket'>
type InstallRedisTestSummary = Omit<InstallRedisTestResult, 'connectionTicket'>

const USERNAME_PATTERN = /^[A-Za-z][A-Za-z0-9._-]{2,31}$/
const DATABASE_DNS_HOST_PATTERN = /^[A-Za-z0-9.-]{1,253}$/
const DATABASE_IPV6_HOST_PATTERN = /^[0-9A-Fa-f:.%]+$/
const DATABASE_NAME_PATTERN = /^[A-Za-z0-9_.-]{1,63}$/
const POSTGRES_MAINTENANCE_DATABASES = new Set(['postgres', 'template0', 'template1'])
const POSTGRES_DEFAULT_SUPERUSER = 'postgres'
const STEP_LABELS = ['PostgreSQL', 'Redis', '环境检查', '站点信息', '管理员', '确认安装'] as const
const STEP_HEADING_IDS = [
  'install-database-title',
  'install-redis-title',
  'install-environment-title',
  'install-site-title',
  'install-admin-title',
  'install-confirm-title',
] as const
const SSL_MODE_OPTIONS: Array<{ value: InstallDatabaseSslMode; label: string; help: string }> = [
  { value: 'VERIFY_FULL', label: '完整验证', help: '验证证书链及服务器主机名' },
  { value: 'VERIFY_CA', label: '验证 CA', help: '验证证书链，不校验主机名' },
  { value: 'REQUIRE', label: '仅要求加密', help: '建立加密连接，但不验证证书或主机名' },
]
const DATABASE_CA_MAX_BYTES = 65_536
const REDIS_CA_MAX_BYTES = 65_536
const REDIS_TLS_MODE_OPTIONS: Array<{
  value: InstallRedisTlsMode
  label: string
  help: string
}> = [
  {
    value: 'SYSTEM',
    label: 'TLS + 系统信任库',
    help: '使用 Java 系统信任库验证证书链和 Redis 主机名，适合公共 CA 证书',
  },
  {
    value: 'CUSTOM_CA',
    label: 'TLS + 自定义 CA',
    help: '使用当前页面提供的私有 CA 验证证书链和 Redis 主机名',
  },
  {
    value: 'DISABLED',
    label: '不使用 TLS（仅可信私网）',
    help: '只允许解析结果均为可信私网地址，并需要显式确认明文传输风险',
  },
]
const formRef = ref<FormInstance>()
const caFileInput = ref<HTMLInputElement>()
const redisCaFileInput = ref<HTMLInputElement>()
const activeStep = ref(0)
const testingDatabase = ref(false)
const configuringDatabase = ref(false)
const testingRedis = ref(false)
const configuringRedis = ref(false)
const checking = ref(false)
const submitting = ref(false)
const submissionFinished = ref(false)
const environmentCheck = ref<InstallCheckResult | null>(null)
const databaseTest = ref<InstallDatabaseTestSummary | null>(null)
const databaseTicket = ref('')
const databaseConfigured = ref(false)
const databaseStepWasUsed = ref(false)
const redisTest = ref<InstallRedisTestSummary | null>(null)
const redisTicket = ref('')
const redisConfigured = ref(false)
const redisStepWasUsed = ref(false)
const form = reactive<InstallForm>({
  database: {
    host: '',
    port: 5432,
    database: '',
    username: '',
    password: '',
    sslMode: 'VERIFY_FULL',
    caCertificatePem: '',
    acknowledgeUnverifiedTls: false,
  },
  redis: {
    host: '',
    port: 6379,
    username: '',
    password: '',
    database: 0,
    tlsMode: 'SYSTEM',
    caCertificatePem: '',
    acknowledgeInsecureTransport: false,
    connectTimeoutSeconds: 3,
    readTimeoutSeconds: 3,
  },
  initializeSchema: false,
  siteName: 'yunlume',
  siteDescription: '简洁、快速、可自定义的网址导航',
  username: 'admin',
  nickname: '管理员',
  password: '',
  confirmPassword: '',
  confirmationAccepted: false,
})

const installStore = useInstallStore()
const authStore = useAuthStore()
const router = useRouter()
const status = computed(() => installStore.status)
const activeStepLabel = computed(() => STEP_LABELS[activeStep.value] ?? '')
const databaseTestReady = computed(() => Boolean(
  databaseTest.value
  && databaseTicket.value
  && !isInstallDatabaseTicketExpired(databaseTest.value),
))
const databaseFieldsLocked = computed(() => Boolean(
  testingDatabase.value
  || configuringDatabase.value
  || databaseTestReady.value
  || databaseConfigured.value,
))
const databaseCanConfigure = computed(() => Boolean(
  databaseTestReady.value
  && databaseTest.value?.schemaState !== 'READY_INSTALLED'
  && (
    !databaseTest.value?.requiresInitialization
    || form.initializeSchema
  ),
))
const databaseSchemaLabel = computed(() => (
  databaseTest.value ? installDatabaseSchemaLabel(databaseTest.value.schemaState) : ''
))
const databaseConfigurationLabel = computed(() => (
  databaseStepWasUsed.value
    ? '外部 PostgreSQL（已通过测试）'
    : '外部 PostgreSQL（已通过检查）'
))
const databaseSslModeHelp = computed(() => (
  SSL_MODE_OPTIONS.find((item) => item.value === form.database.sslMode)?.help ?? ''
))
const postgresProvisioningReady = computed(() => Boolean(
  DATABASE_NAME_PATTERN.test(form.database.database)
  && form.database.database === form.database.database.trim()
  && !POSTGRES_MAINTENANCE_DATABASES.has(form.database.database.toLowerCase())
  && isTrimmedSingleLine(form.database.username)
  && form.database.username.length > 0
  && form.database.username.length <= 128
  && form.database.username.toLowerCase() !== POSTGRES_DEFAULT_SUPERUSER
))
const postgresProvisioning = computed(() => (
  postgresProvisioningReady.value
    ? buildPostgresProvisioningSql(form.database.database, form.database.username)
    : null
))
const redisTestReady = computed(() => Boolean(
  redisTest.value
  && redisTicket.value
  && !isInstallRedisTicketExpired(redisTest.value),
))
const redisFieldsLocked = computed(() => Boolean(
  testingRedis.value
  || configuringRedis.value
  || redisTestReady.value
  || redisConfigured.value,
))
const redisCanConfigure = computed(() => Boolean(redisTestReady.value))
const redisTlsModeHelp = computed(() => (
  REDIS_TLS_MODE_OPTIONS.find((item) => item.value === form.redis.tlsMode)?.help ?? ''
))
const redisConfigurationLabel = computed(() => (
  redisStepWasUsed.value
    ? '外部 Redis（已通过测试）'
    : '外部 Redis（已通过检查）'
))
const canEnterDatabaseStep = computed(() => {
  const currentStatus = status.value
  return Boolean(
    currentStatus
    && ['DATABASE_REQUIRED', 'REDIS_REQUIRED', 'REQUIRED'].includes(currentStatus.state)
    && currentStatus.webInstallEnabled
    && !installStore.error,
  )
})
const canEnterRedisStep = computed(() => {
  const currentStatus = status.value
  return Boolean(
    currentStatus?.state === 'REDIS_REQUIRED'
    && currentStatus.webInstallEnabled
    && !installStore.error,
  )
})
const environmentReady = computed(() => {
  const currentStatus = status.value
  return Boolean(
    environmentCheck.value?.ready
    && databaseConfigured.value
    && redisConfigured.value
    && currentStatus?.state === 'REQUIRED'
    && currentStatus.webInstallEnabled
    && currentStatus.ready
    && !installStore.error,
  )
})
const canCheckEnvironment = computed(() => {
  const currentStatus = status.value
  return Boolean(
    databaseConfigured.value
    && redisConfigured.value
    && currentStatus?.state === 'REQUIRED'
    && currentStatus.webInstallEnabled
    && currentStatus.ready
    && !installStore.error,
  )
})
const primaryActionDisabled = computed(() => isInstallPrimaryActionDisabled({
  step: activeStep.value,
  databaseConfigured: databaseConfigured.value,
  databaseCanConfigure: databaseCanConfigure.value,
  databaseBusy: testingDatabase.value || configuringDatabase.value,
  redisConfigured: redisConfigured.value,
  redisCanConfigure: redisCanConfigure.value,
  redisBusy: testingRedis.value || configuringRedis.value,
  environmentReady: environmentReady.value,
  checkingEnvironment: checking.value,
}))
const canGoPrevious = computed(() => {
  if (activeStep.value === 0) return false
  if (activeStep.value === 1) return databaseStepWasUsed.value
  if (activeStep.value === 2) {
    return redisStepWasUsed.value || databaseStepWasUsed.value
  }
  return true
})
const checkItems = computed<CheckItem[]>(() => {
  const checks = environmentCheck.value?.checks
  if (!checks) return []
  return [
    { key: 'database', label: 'PostgreSQL 数据库', check: checks.database },
    { key: 'schema', label: '数据库结构', check: checks.schema },
    { key: 'siteConfig', label: '站点初始化条件', check: checks.siteConfig },
    { key: 'upload', label: '上传存储目录', check: checks.upload },
    { key: 'redis', label: 'Redis 连接', check: checks.redis },
  ]
})
const passwordPolicy = computed(() => evaluatePasswordPolicy(form.password, form.username))
const strengthLabel = computed(() => ({
  empty: '等待输入',
  weak: '未满足要求',
  medium: '符合要求',
  strong: '强密码',
}[passwordPolicy.value.strength]))
const strengthWidth = computed(() => ({
  empty: '0%',
  weak: '28%',
  medium: '68%',
  strong: '100%',
}[passwordPolicy.value.strength]))
function isTrimmedSingleLine(value: string): boolean {
  return value === value.trim() && ![...value].some((character) => {
    const codePoint = character.codePointAt(0) ?? 0
    return codePoint <= 0x1f
      || (codePoint >= 0x7f && codePoint <= 0x9f)
      || codePoint === 0x2028
      || codePoint === 0x2029
  })
}

function isValidDatabaseHost(value: string): boolean {
  const dns = DATABASE_DNS_HOST_PATTERN.test(value)
    && !value.startsWith('.')
    && !value.endsWith('.')
    && !value.startsWith('-')
    && !value.endsWith('-')
    && !value.includes('..')
  const ipv6 = value.includes(':') && DATABASE_IPV6_HOST_PATTERN.test(value)
  return dns || ipv6
}

function installRequestErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}

function databaseUsesCaCertificate(): boolean {
  return form.database.sslMode !== 'REQUIRE'
}

function redisUsesCustomCaCertificate(): boolean {
  return form.redis.tlsMode === 'CUSTOM_CA'
}

function isValidCaCertificatePem(value: string): boolean {
  return value.includes('-----BEGIN CERTIFICATE-----')
    && value.includes('-----END CERTIFICATE-----')
    && new TextEncoder().encode(value).byteLength <= DATABASE_CA_MAX_BYTES
}

const rules: FormRules = {
  siteName: [
    { required: true, message: '请输入站点名称', trigger: 'blur' },
    { max: 50, message: '站点名称不能超过 50 个字符', trigger: ['blur', 'change'] },
    {
      validator: (_rule, value, callback) => {
        if (!isTrimmedSingleLine(value ?? '')) return callback(new Error('站点名称必须是首尾无空格的单行文字'))
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  siteDescription: [
    { max: 255, message: '站点简介不能超过 255 个字符', trigger: ['blur', 'change'] },
    {
      validator: (_rule, value, callback) => {
        if (!isTrimmedSingleLine(value ?? '')) return callback(new Error('站点简介必须是首尾无空格的单行文字'))
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  username: [
    { required: true, message: '请输入管理员用户名', trigger: 'blur' },
    {
      pattern: USERNAME_PATTERN,
      message: '用户名需以英文字母开头，由 3–32 位字母、数字、点、下划线或短横线组成',
      trigger: ['blur', 'change'],
    },
  ],
  nickname: [
    { required: true, message: '请输入管理员昵称', trigger: 'blur' },
    { max: 50, message: '管理员昵称不能超过 50 个字符', trigger: ['blur', 'change'] },
    {
      validator: (_rule, value, callback) => {
        if (!isTrimmedSingleLine(value ?? '')) return callback(new Error('管理员昵称必须是首尾无空格的单行文字'))
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  password: [
    { required: true, message: '请输入管理员密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        const result = evaluatePasswordPolicy(value ?? '', form.username)
        if (!result.lengthValid) {
          return callback(new Error(`密码至少 ${PASSWORD_MIN_LENGTH} 个字符，且不能超过 ${PASSWORD_MAX_LENGTH} 个 UTF-8 字节`))
        }
        if (!result.whitespaceFree) return callback(new Error('密码不能包含空格或其他空白字符'))
        if (!result.categoriesValid) return callback(new Error('请至少使用大写字母、小写字母、数字、符号中的三类'))
        if (!result.usernameFree) return callback(new Error('密码不能包含管理员用户名'))
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入管理员密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== form.password) return callback(new Error('两次输入的密码不一致'))
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  'database.host': [
    {
      validator: (_rule, value, callback) => {
        if (typeof value !== 'string' || !value) return callback(new Error('请输入 PostgreSQL 主机名或 IP 地址'))
        if (value !== value.trim() || !isValidDatabaseHost(value)) {
          return callback(new Error('请输入不含协议、路径、账号或空格的 DNS 主机名、IPv4 或 IPv6 地址'))
        }
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  'database.port': [
    {
      validator: (_rule, value, callback) => {
        const port = Number(value)
        if (!Number.isInteger(port) || port < 1 || port > 65535) {
          return callback(new Error('端口必须是 1–65535 之间的整数'))
        }
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  'database.database': [
    {
      validator: (_rule, value, callback) => {
        if (typeof value !== 'string' || !value) return callback(new Error('请输入数据库名称'))
        if (value !== value.trim() || !DATABASE_NAME_PATTERN.test(value)) {
          return callback(new Error('数据库名称只能包含英文字母、数字、点、下划线和短横线，且不超过 63 个字符'))
        }
        if (POSTGRES_MAINTENANCE_DATABASES.has(value.toLowerCase())) {
          return callback(new Error('请使用专用业务库，不要使用 postgres 或 template 维护库'))
        }
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  'database.username': [
    {
      validator: (_rule, value, callback) => {
        if (typeof value !== 'string' || !value) return callback(new Error('请输入数据库用户名'))
        if (!isTrimmedSingleLine(value) || value.length > 128) {
          return callback(new Error('数据库用户名应为首尾无空格的单行文字，且不超过 128 个字符'))
        }
        if (value.toLowerCase() === POSTGRES_DEFAULT_SUPERUSER) {
          return callback(new Error('请填写在 1Panel 中创建的普通业务用户，不要使用默认超级用户 postgres'))
        }
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  'database.password': [
    {
      validator: (_rule, value, callback) => {
        if (databaseTestReady.value) return callback()
        if (typeof value !== 'string' || !value) return callback(new Error('请输入数据库密码'))
        if (value.length > 1024) return callback(new Error('数据库密码长度不能超过 1024 个字符'))
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  'database.sslMode': [
    {
      validator: (_rule, value, callback) => {
        if (!SSL_MODE_OPTIONS.some((item) => item.value === value)) {
          return callback(new Error('请选择有效的 SSL 模式'))
        }
        callback()
      },
      trigger: 'change',
    },
  ],
  'database.caCertificatePem': [
    {
      validator: (_rule, value, callback) => {
        if (!databaseUsesCaCertificate()) return callback()
        if (typeof value !== 'string' || !value.trim()) {
          return callback(new Error('VERIFY_CA / VERIFY_FULL 必须提供 CA 证书 PEM'))
        }
        if (!isValidCaCertificatePem(value)) {
          return callback(new Error('请输入不超过 64KiB 的完整 PEM CA 证书'))
        }
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  'database.acknowledgeUnverifiedTls': [
    {
      validator: (_rule, value, callback) => {
        if (
          form.database.sslMode === 'REQUIRE'
          && value !== true
        ) {
          return callback(new Error('使用 REQUIRE 前必须确认未验证证书和主机名的风险'))
        }
        callback()
      },
      trigger: 'change',
    },
  ],
  'redis.host': [
    {
      validator: (_rule, value, callback) => {
        if (typeof value !== 'string' || !value) return callback(new Error('请输入 Redis 主机名或 IP 地址'))
        if (value !== value.trim() || !isValidDatabaseHost(value)) {
          return callback(new Error('请输入不含协议、路径、账号或空格的 DNS 主机名、IPv4 或 IPv6 地址'))
        }
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  'redis.port': [
    {
      validator: (_rule, value, callback) => {
        const port = Number(value)
        if (!Number.isInteger(port) || port < 1 || port > 65535) {
          return callback(new Error('端口必须是 1–65535 之间的整数'))
        }
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  'redis.username': [
    {
      validator: (_rule, value, callback) => {
        if (value === '') return callback()
        if (!isValidInstallRedisUsername(value)) {
          return callback(new Error('ACL 用户名不能含空白或控制字符，且不能超过 128 个字符'))
        }
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  'redis.password': [
    {
      validator: (_rule, value, callback) => {
        if (redisTestReady.value) return callback()
        if (typeof value !== 'string' || value.length < 1) return callback(new Error('请输入 Redis 密码'))
        if (value.length > 1024) return callback(new Error('Redis 密码长度不能超过 1024 个字符'))
        if (/[\0\r\n]/.test(value)) return callback(new Error('Redis 密码不能包含 NUL 或换行符'))
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  'redis.database': [
    {
      validator: (_rule, value, callback) => {
        const database = Number(value)
        if (!Number.isInteger(database) || database < 0 || database > 65535) {
          return callback(new Error('逻辑库编号必须是 0–65535 之间的整数'))
        }
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  'redis.tlsMode': [
    {
      validator: (_rule, value, callback) => {
        if (!REDIS_TLS_MODE_OPTIONS.some((item) => item.value === value)) {
          return callback(new Error('请选择有效的 Redis TLS 模式'))
        }
        callback()
      },
      trigger: 'change',
    },
  ],
  'redis.caCertificatePem': [
    {
      validator: (_rule, value, callback) => {
        if (!redisUsesCustomCaCertificate()) return callback()
        if (typeof value !== 'string' || !value.trim()) {
          return callback(new Error('自定义 CA 模式必须提供 CA 证书 PEM'))
        }
        if (!isValidCaCertificatePem(value)) {
          return callback(new Error('请输入不超过 64KiB 的完整 PEM CA 证书链'))
        }
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  'redis.acknowledgeInsecureTransport': [
    {
      validator: (_rule, value, callback) => {
        if (form.redis.tlsMode === 'DISABLED' && value !== true) {
          return callback(new Error('关闭 TLS 前必须确认 Redis 位于可信私网且凭据将明文传输'))
        }
        callback()
      },
      trigger: 'change',
    },
  ],
  'redis.connectTimeoutSeconds': [
    {
      validator: (_rule, value, callback) => {
        const timeout = Number(value)
        if (!Number.isInteger(timeout) || timeout < 1 || timeout > 10) {
          return callback(new Error('建连超时必须是 1–10 秒之间的整数'))
        }
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  'redis.readTimeoutSeconds': [
    {
      validator: (_rule, value, callback) => {
        const timeout = Number(value)
        if (!Number.isInteger(timeout) || timeout < 1 || timeout > 10) {
          return callback(new Error('读写超时必须是 1–10 秒之间的整数'))
        }
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  initializeSchema: [
    {
      validator: (_rule, value, callback) => {
        if (databaseTest.value?.requiresInitialization && value !== true) {
          return callback(new Error('空数据库需要确认初始化系统表结构'))
        }
        callback()
      },
      trigger: 'change',
    },
  ],
  confirmationAccepted: [
    {
      validator: (_rule, value, callback) => {
        if (value !== true) return callback(new Error('请确认以上信息并知晓安装完成后向导会关闭'))
        callback()
      },
      trigger: 'change',
    },
  ],
}

let restartPollGeneration = 0
let databaseTicketExpiryTimer: number | undefined
let redisTicketExpiryTimer: number | undefined

function copyWithLegacyTextarea(text: string): boolean {
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.readOnly = true
  textarea.setAttribute('aria-label', 'PostgreSQL 建库授权 SQL 复制缓冲区')
  textarea.style.position = 'fixed'
  textarea.style.inset = '0 auto auto 0'
  textarea.style.width = '1px'
  textarea.style.height = '1px'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.focus()
  textarea.select()
  textarea.setSelectionRange(0, textarea.value.length)
  try {
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    textarea.remove()
  }
}

async function copyPostgresProvisioningSql(sql: string | undefined, label: string) {
  if (!sql) {
    ElMessage.warning('请先填写有效的数据库名称和普通业务用户名')
    return
  }
  const result = await copyTextWithFallback(
    sql,
    navigator.clipboard,
    copyWithLegacyTextarea,
  )
  if (result === 'failed') {
    ElMessage.error('自动复制失败，请在 SQL 区域中手动选择复制')
    return
  }
  ElMessage.success(`${label}已复制`)
}

async function validateFields(fields: string[]): Promise<boolean> {
  if (!formRef.value) return false
  return formRef.value.validateField(fields).then(() => true).catch(() => false)
}

function scrubDatabaseAuthorization(clearTest = true) {
  if (databaseTicketExpiryTimer !== undefined) {
    window.clearTimeout(databaseTicketExpiryTimer)
    databaseTicketExpiryTimer = undefined
  }
  form.database.password = ''
  form.database.caCertificatePem = ''
  form.database.acknowledgeUnverifiedTls = false
  databaseTicket.value = ''
  if (clearTest) {
    databaseTest.value = null
    form.initializeSchema = false
  }
}

function scheduleDatabaseTicketExpiry(expiresAt: string) {
  if (databaseTicketExpiryTimer !== undefined) window.clearTimeout(databaseTicketExpiryTimer)
  const delay = Math.max(0, Date.parse(expiresAt) - Date.now())
  databaseTicketExpiryTimer = window.setTimeout(() => {
    databaseTicket.value = ''
    form.initializeSchema = false
    databaseTicketExpiryTimer = undefined
  }, delay)
}

function invalidateDatabaseTest() {
  if (databaseConfigured.value) return
  if (databaseTicketExpiryTimer !== undefined) {
    window.clearTimeout(databaseTicketExpiryTimer)
    databaseTicketExpiryTimer = undefined
  }
  databaseTicket.value = ''
  databaseTest.value = null
  form.initializeSchema = false
  environmentCheck.value = null
}

function handleDatabaseSslModeChange() {
  invalidateDatabaseTest()
  if (form.database.sslMode === 'REQUIRE') form.database.caCertificatePem = ''
  else form.database.acknowledgeUnverifiedTls = false
  void formRef.value?.clearValidate([
    'database.caCertificatePem',
    'database.acknowledgeUnverifiedTls',
  ])
}

function selectCaCertificateFile() {
  caFileInput.value?.click()
}

async function handleCaCertificateFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > DATABASE_CA_MAX_BYTES) {
    scrubDatabaseAuthorization(true)
    ElMessage.error('CA 证书文件不能超过 64KiB')
    return
  }
  try {
    const pem = await file.text()
    if (!isValidCaCertificatePem(pem)) {
      scrubDatabaseAuthorization(true)
      ElMessage.error('文件不是完整的 PEM CA 证书')
      return
    }
    form.database.caCertificatePem = pem
    invalidateDatabaseTest()
    await formRef.value?.validateField('database.caCertificatePem').catch(() => undefined)
    ElMessage.success('CA 证书已载入当前页面内存')
  } catch {
    scrubDatabaseAuthorization(true)
    ElMessage.error('无法读取 CA 证书文件')
  }
}

function scrubRedisAuthorization(clearTest = true) {
  if (redisTicketExpiryTimer !== undefined) {
    window.clearTimeout(redisTicketExpiryTimer)
    redisTicketExpiryTimer = undefined
  }
  form.redis.password = ''
  form.redis.caCertificatePem = ''
  form.redis.acknowledgeInsecureTransport = false
  redisTicket.value = ''
  if (clearTest) redisTest.value = null
}

function scheduleRedisTicketExpiry(expiresAt: string) {
  if (redisTicketExpiryTimer !== undefined) window.clearTimeout(redisTicketExpiryTimer)
  const delay = Math.max(0, Date.parse(expiresAt) - Date.now())
  redisTicketExpiryTimer = window.setTimeout(() => {
    redisTicket.value = ''
    redisTicketExpiryTimer = undefined
  }, delay)
}

function invalidateRedisTest() {
  if (redisConfigured.value) return
  if (redisTicketExpiryTimer !== undefined) {
    window.clearTimeout(redisTicketExpiryTimer)
    redisTicketExpiryTimer = undefined
  }
  redisTicket.value = ''
  redisTest.value = null
  environmentCheck.value = null
}

function handleRedisTlsModeChange() {
  invalidateRedisTest()
  if (form.redis.tlsMode !== 'CUSTOM_CA') form.redis.caCertificatePem = ''
  if (form.redis.tlsMode !== 'DISABLED') form.redis.acknowledgeInsecureTransport = false
  void formRef.value?.clearValidate([
    'redis.caCertificatePem',
    'redis.acknowledgeInsecureTransport',
  ])
}

function selectRedisCaCertificateFile() {
  redisCaFileInput.value?.click()
}

async function handleRedisCaCertificateFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > REDIS_CA_MAX_BYTES) {
    scrubRedisAuthorization(true)
    ElMessage.error('Redis CA 证书文件不能超过 64KiB')
    return
  }
  try {
    const pem = await file.text()
    if (!isValidCaCertificatePem(pem)) {
      scrubRedisAuthorization(true)
      ElMessage.error('文件不是完整的 PEM CA 证书链')
      return
    }
    form.redis.caCertificatePem = pem
    invalidateRedisTest()
    await formRef.value?.validateField('redis.caCertificatePem').catch(() => undefined)
    ElMessage.success('Redis CA 证书已载入当前页面内存')
  } catch {
    scrubRedisAuthorization(true)
    ElMessage.error('无法读取 Redis CA 证书文件')
  }
}

function databaseValidationFields(): string[] {
  const fields = [
    'database.host',
    'database.port',
    'database.database',
    'database.username',
    'database.password',
    'database.sslMode',
  ]
  fields.push(form.database.sslMode === 'REQUIRE'
    ? 'database.acknowledgeUnverifiedTls'
    : 'database.caCertificatePem')
  return fields
}

function redisValidationFields(): string[] {
  const fields = [
    'redis.host',
    'redis.port',
    'redis.username',
    'redis.password',
    'redis.database',
    'redis.tlsMode',
    'redis.connectTimeoutSeconds',
    'redis.readTimeoutSeconds',
  ]
  if (form.redis.tlsMode === 'CUSTOM_CA') fields.push('redis.caCertificatePem')
  if (form.redis.tlsMode === 'DISABLED') fields.push('redis.acknowledgeInsecureTransport')
  return fields
}

async function testDatabaseConnection() {
  if (testingDatabase.value || configuringDatabase.value || databaseConfigured.value) return
  if (!await validateFields(databaseValidationFields())) return
  if (!canEnterDatabaseStep.value) {
    ElMessage.error('服务器尚未开放数据库配置，请先修复部署配置')
    return
  }

  const database = buildInstallDatabaseConfig(form.database)
  testingDatabase.value = true
  databaseTicket.value = ''
  databaseTest.value = null
  form.initializeSchema = false
  try {
    const result = await testInstallDatabaseApi(database)
    databaseTicket.value = result.connectionTicket
    databaseTest.value = {
      ok: true,
      expiresAt: result.expiresAt,
      schemaState: result.schemaState,
      requiresInitialization: result.requiresInitialization,
    }
    scheduleDatabaseTicketExpiry(result.expiresAt)
    form.database.password = ''
    form.database.caCertificatePem = ''
    ElMessage.success('数据库连接与结构检查通过，密码和 CA 证书已从页面内存清除')
  } catch (error) {
    const statusCode = getHttpStatus(error)
    scrubDatabaseAuthorization(true)
    if (statusCode === 409) await refreshStatus(true)
    ElMessage.error(installRequestErrorMessage(
      error,
      '数据库连接或结构检查失败，请核对配置后重试',
    ))
  } finally {
    testingDatabase.value = false
  }
}

async function waitForBackendState(
  acceptedStates: ReadonlySet<InstallState>,
): Promise<InstallStatus | null> {
  const generation = ++restartPollGeneration
  const deadline = Date.now() + 90_000
  while (Date.now() < deadline && generation === restartPollGeneration) {
    await new Promise<void>((resolve) => window.setTimeout(resolve, 2_000))
    if (generation !== restartPollGeneration) return null
    const latest = await installStore.fetchStatus(true)
    if (latest?.state === 'COMPLETED') return latest
    if (latest && acceptedStates.has(latest.state) && !installStore.error) return latest
  }
  return null
}

async function configureDatabase() {
  if (testingDatabase.value || configuringDatabase.value || databaseConfigured.value) return
  if (!databaseTestReady.value || !databaseTest.value || !databaseTicket.value) {
    scrubDatabaseAuthorization(true)
    ElMessage.warning('连接测试结果已失效，请重新输入数据库密码并测试')
    return
  }
  if (databaseTest.value.schemaState === 'READY_INSTALLED') {
    scrubDatabaseAuthorization(true)
    ElMessage.error('该数据库已存在完成安装的站点，不能用于首次初始化')
    return
  }
  if (!await validateFields(['initializeSchema'])) return

  configuringDatabase.value = true
  try {
    const result = await configureInstallDatabaseApi({
      connectionTicket: databaseTicket.value,
      initializeSchema: databaseTest.value.requiresInitialization
        ? form.initializeSchema
        : false,
    })
    scrubDatabaseAuthorization(false)
    databaseConfigured.value = true
    databaseStepWasUsed.value = true

    if (result.installed) {
      await refreshStatus(true)
      return
    }

    let latest = null
    if (result.restartRequired) {
      ElMessage.info('数据库配置已保存，正在等待服务重启，请勿关闭页面')
      latest = await waitForBackendState(new Set<InstallState>([
        'REDIS_REQUIRED',
        'REQUIRED',
      ]))
    } else {
      latest = await installStore.fetchStatus(true)
    }
    if (latest?.state === 'COMPLETED') {
      scrubSensitiveFields()
      await router.replace({ name: 'admin-login' })
      return
    }
    if (latest?.state === 'REDIS_REQUIRED' && latest.webInstallEnabled) {
      redisConfigured.value = false
      redisStepWasUsed.value = true
      activeStep.value = 1
      ElMessage.success('数据库已接入，请继续配置外部 Redis')
      return
    }
    if (latest?.state !== 'REQUIRED' || !latest.ready || installStore.error) {
      databaseConfigured.value = false
      throw new Error('数据库已保存，但服务未在 90 秒内恢复，请检查服务日志后重试')
    }
    redisConfigured.value = true
    await checkEnvironment()
  } catch (error) {
    const statusCode = getHttpStatus(error)
    scrubDatabaseAuthorization(true)
    databaseConfigured.value = false
    if (statusCode === 409) await refreshStatus(true)
    ElMessage.error(installRequestErrorMessage(error, '数据库配置应用失败，请重新测试连接'))
  } finally {
    configuringDatabase.value = false
  }
}

async function testRedisConnection() {
  if (testingRedis.value || configuringRedis.value || redisConfigured.value) return
  if (!await validateFields(redisValidationFields())) return
  if (!canEnterRedisStep.value) {
    ElMessage.error('服务器尚未开放 Redis 配置，请先确认 PostgreSQL 已接入')
    return
  }

  testingRedis.value = true
  redisTicket.value = ''
  redisTest.value = null
  try {
    const result = await testInstallRedisApi(buildInstallRedisConfig(form.redis))
    redisTicket.value = result.connectionTicket
    redisTest.value = {
      ok: true,
      expiresAt: result.expiresAt,
    }
    scheduleRedisTicketExpiry(result.expiresAt)
    form.redis.password = ''
    form.redis.caCertificatePem = ''
    form.redis.acknowledgeInsecureTransport = false
    ElMessage.success('Redis 连接检查通过，密码和 CA 证书已从页面内存清除')
  } catch (error) {
    const statusCode = getHttpStatus(error)
    scrubRedisAuthorization(true)
    if (statusCode === 409) await refreshStatus(true)
    ElMessage.error(installRequestErrorMessage(
      error,
      'Redis 连接检查失败，请核对地址、ACL、TLS 与网络配置',
    ))
  } finally {
    testingRedis.value = false
  }
}

async function configureRedis() {
  if (testingRedis.value || configuringRedis.value || redisConfigured.value) return
  if (!redisTestReady.value || !redisTest.value || !redisTicket.value) {
    scrubRedisAuthorization(true)
    ElMessage.warning('Redis 连接票据已失效，请重新输入密码并测试')
    return
  }

  configuringRedis.value = true
  try {
    const result = await configureInstallRedisApi({
      connectionTicket: redisTicket.value,
    })

    // configure 只使用单次票据；响应一到就立即清理所有 Redis 授权信息。
    scrubRedisAuthorization(false)
    redisConfigured.value = true
    redisStepWasUsed.value = true
    ElMessage.info(result.restartRequired
      ? 'Redis 配置已保存，正在等待服务自动重启，请勿关闭页面'
      : 'Redis 配置已保存，请立即重启后端；页面将在 90 秒内等待恢复')

    const latest = await waitForBackendState(new Set<InstallState>(['REQUIRED']))
    if (latest?.state === 'COMPLETED') {
      scrubSensitiveFields()
      await router.replace({ name: 'admin-login' })
      return
    }
    if (latest?.state !== 'REQUIRED' || !latest.ready || installStore.error) {
      redisConfigured.value = false
      throw new Error('Redis 已保存，但服务未在 90 秒内恢复，请检查服务日志后重试')
    }
    await checkEnvironment()
  } catch (error) {
    const statusCode = getHttpStatus(error)
    scrubRedisAuthorization(true)
    redisConfigured.value = false
    if (statusCode === 409) await refreshStatus(true)
    ElMessage.error(installRequestErrorMessage(error, 'Redis 配置应用失败，请重新测试连接'))
  } finally {
    configuringRedis.value = false
  }
}

async function nextStep() {
  if (
    testingDatabase.value
    || configuringDatabase.value
    || testingRedis.value
    || configuringRedis.value
    || checking.value
    || submitting.value
  ) return
  if (activeStep.value === 0) {
    if (databaseConfigured.value) {
      activeStep.value = 1
      return
    }
    await configureDatabase()
    return
  }
  if (activeStep.value === 1) {
    if (redisConfigured.value) {
      activeStep.value = 2
      return
    }
    await configureRedis()
    return
  }
  if (activeStep.value === 2) {
    if (!environmentReady.value) {
      ElMessage.warning('请先完成全部环境检查')
      return
    }
    activeStep.value = 3
    return
  }
  if (activeStep.value === 3) {
    if (!await validateFields(['siteName', 'siteDescription'])) return
    activeStep.value = 4
    return
  }
  if (activeStep.value === 4) {
    if (!await validateFields(['username', 'nickname', 'password', 'confirmPassword'])) return
    activeStep.value = 5
  }
}

function previousStep() {
  if (
    !testingDatabase.value
    && !configuringDatabase.value
    && !testingRedis.value
    && !configuringRedis.value
    && !checking.value
    && !submitting.value
    && canGoPrevious.value
  ) {
    if (activeStep.value === 2) {
      if (redisStepWasUsed.value) activeStep.value = 1
      else activeStep.value = 0
    } else {
      activeStep.value -= 1
    }
  }
}

function scrubSensitiveFields() {
  scrubDatabaseAuthorization(true)
  scrubRedisAuthorization(true)
  form.password = ''
  form.confirmPassword = ''
}

function applyWizardEntry(latest: InstallStatus): boolean {
  const entry = resolveInstallWizardEntry(latest.state)
  if (!entry) return false
  databaseConfigured.value = entry.databaseConfigured
  redisConfigured.value = entry.redisConfigured
  databaseStepWasUsed.value = entry.databaseStepWasUsed
  redisStepWasUsed.value = entry.redisStepWasUsed
  environmentCheck.value = null
  activeStep.value = entry.step
  return true
}

async function refreshStatus(force = true) {
  const latest = await installStore.fetchStatus(force)
  if (!latest || installStore.error) {
    scrubDatabaseAuthorization(true)
    scrubRedisAuthorization(true)
    return
  }
  if (latest?.state === 'COMPLETED') {
    scrubSensitiveFields()
    await router.replace({ name: 'admin-login' })
    return
  }
  applyWizardEntry(latest)
}

async function checkEnvironment() {
  if (checking.value || submitting.value) return
  if (!canCheckEnvironment.value) {
    ElMessage.error('服务器尚未开放安装检查，请先修复部署配置')
    return
  }

  checking.value = true
  try {
    environmentCheck.value = await checkInstallationApi()
    activeStep.value = 2
    if (environmentCheck.value.ready) ElMessage.success('完整运行环境检查通过')
    else ElMessage.warning('运行环境尚未就绪，请按检查结果修复后重试')
  } catch (error) {
    const statusCode = getHttpStatus(error)
    environmentCheck.value = null
    scrubSensitiveFields()
    if (statusCode === 409) await refreshStatus(true)
    else activeStep.value = 2
    ElMessage.error(installRequestErrorMessage(error, '无法完成安装检查，请稍后重试'))
  } finally {
    checking.value = false
  }
}

async function completeInstallation() {
  if (submitting.value || submissionFinished.value) return
  if (!await validateFields(['confirmationAccepted'])) return

  const latest = await installStore.fetchStatus(true)
  if (latest?.state === 'COMPLETED') {
    scrubSensitiveFields()
    authStore.clearSession()
    await router.replace({ name: 'admin-login' })
    return
  }
  if (
    !latest
    || installStore.error
    || latest.state !== 'REQUIRED'
    || !latest.webInstallEnabled
    || !latest.ready
  ) {
    scrubSensitiveFields()
    environmentCheck.value = null
    form.confirmationAccepted = false
    if (latest) applyWizardEntry(latest)
    else activeStep.value = 0
    ElMessage.error('安装状态已变化或暂时无法确认，请重新检查环境')
    return
  }

  submitting.value = true
  try {
    const latestCheck = await checkInstallationApi()
    environmentCheck.value = latestCheck
    if (!latestCheck.ready) {
      scrubSensitiveFields()
      environmentCheck.value = null
      form.confirmationAccepted = false
      if (latest) applyWizardEntry(latest)
      else activeStep.value = 2
      ElMessage.error('运行环境检查已发生变化，请修复后重新确认')
      return
    }
    const payload: CompleteInstallationPayload = {
      siteName: form.siteName.trim(),
      siteDescription: form.siteDescription.trim(),
      username: form.username.trim(),
      nickname: form.nickname.trim(),
      password: form.password,
      confirmPassword: form.confirmPassword,
    }
    await completeInstallationApi(payload)
    submissionFinished.value = true
    scrubSensitiveFields()
    environmentCheck.value = null
    form.confirmationAccepted = false
    installStore.markInstalled()
    authStore.clearSession()
    await router.replace({ name: 'admin-login', query: { installed: '1' } })
    ElMessage.success('安装完成，请使用新管理员账号登录')
  } catch (error) {
    const statusCode = getHttpStatus(error)
    scrubSensitiveFields()
    form.confirmationAccepted = false
    if (statusCode === 409) {
      await refreshStatus(true)
    } else {
      activeStep.value = 4
    }
    ElMessage.error(installRequestErrorMessage(error, '安装失败，请检查配置后重试'))
  } finally {
    submitting.value = false
  }
}

function handleEnter(event: KeyboardEvent) {
  if (event.isComposing) return
  const target = event.target
  if (!(target instanceof HTMLInputElement)) return
  if (!shouldAdvanceInstallOnEnter({
    tagName: target.tagName,
    inputType: target.type,
    insideCombobox: Boolean(target.closest('[role="combobox"]')),
  })) return
  if (activeStep.value < 5 && primaryActionDisabled.value) return
  if (activeStep.value === 5 && (submitting.value || submissionFinished.value)) return
  if (activeStep.value === 5) void completeInstallation()
  else void nextStep()
}

watch(activeStep, async (step) => {
  await nextTick()
  document.getElementById(STEP_HEADING_IDS[step] ?? '')?.focus()
})

onMounted(() => {
  void refreshStatus(false)
})
onBeforeUnmount(() => {
  restartPollGeneration += 1
  scrubSensitiveFields()
  environmentCheck.value = null
})
</script>

<template>
  <div class="install-page">
    <aside class="install-page__story" aria-label="yunlume 首次部署说明">
      <RouterLink class="install-page__brand" to="/">
        <span>i</span>
        <strong>yunlume</strong>
      </RouterLink>
      <div class="install-page__story-copy">
        <p>FIRST-RUN SETUP</p>
        <h1>几步完成部署，<br />建立你的导航起点。</h1>
        <span>连接外部 PostgreSQL 与 Redis，检查运行环境并创建首位管理员。安装完成后，此入口会自动关闭。</span>
      </div>
      <small>yunlume NAVIGATION SYSTEM · SECURE INSTALLATION</small>
    </aside>

    <main class="install-page__main">
      <section class="install-wizard" aria-labelledby="install-title">
        <header class="install-wizard__heading">
          <div class="install-wizard__mobile-brand">yunlume</div>
          <p>DEPLOYMENT WIZARD</p>
          <h2 id="install-title">首次部署向导</h2>
          <span>安装只允许执行一次，请按步骤完成必要配置。</span>
        </header>

        <el-steps
          class="install-wizard__steps"
          :active="activeStep"
          finish-status="success"
          align-center
          aria-label="安装进度"
        >
          <el-step title="PostgreSQL" />
          <el-step title="Redis" />
          <el-step title="环境" />
          <el-step title="站点" />
          <el-step title="账号" />
          <el-step title="确认" />
        </el-steps>
        <p class="install-wizard__step-progress" aria-live="polite">
          第 {{ activeStep + 1 }} / {{ STEP_LABELS.length }} 步 · {{ activeStepLabel }}
        </p>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          class="install-wizard__form"
          @keyup.enter="handleEnter"
        >
          <section v-show="activeStep === 0" class="install-step" aria-labelledby="install-database-title">
            <div class="install-step__heading">
              <span><Coin /></span>
              <div>
                <h3 id="install-database-title" tabindex="-1">配置 PostgreSQL</h3>
                <p>连接由你维护的外部 PostgreSQL，验证权限、TLS 与数据库结构。</p>
              </div>
            </div>

            <div
              v-if="installStore.error || status?.state === 'UNKNOWN' || status?.state === 'DISABLED'"
              class="install-status"
              aria-live="polite"
            >
              <el-alert
                v-if="installStore.error"
                type="error"
                :closable="false"
                title="无法确认安装状态"
                :description="installStore.error"
                show-icon
              />
              <el-alert
                v-else-if="status?.state === 'UNKNOWN'"
                type="error"
                :closable="false"
                title="暂时无法确认系统安装状态"
                description="为避免误导已有站点，页面不会自动进入安装。请检查服务端和数据库后重新读取状态。"
                show-icon
              />
              <el-alert
                v-else
                type="warning"
                :closable="false"
                title="网页安装未启用"
                description="请在服务器配置中启用网页安装，然后重新检查。"
                show-icon
              />
              <el-button class="install-step__retry" :loading="installStore.loading" @click="refreshStatus(true)">
                <Refresh />重新读取安装状态
              </el-button>
            </div>

            <p class="install-database-guidance">
              请先准备非 superuser 的专用业务账号；数据库可在 1Panel 中预先创建，也可按下方两段 SQL 建立，并授予建表、索引、序列和迁移所需 DDL 权限。
            </p>

            <details class="install-provisioning-guide">
              <summary>1Panel 创建普通用户与业务库指引</summary>
              <div class="install-provisioning-guide__body">
                <el-alert
                  type="warning"
                  :closable="false"
                  title="不要在安装向导中填写 PostgreSQL 超级管理员账号"
                  description="先在 1Panel 中创建专用的普通登录用户，再用数据库管理界面执行下方不含密码的授权 SQL。"
                  show-icon
                />
                <ol>
                  <li>在 1Panel 的 PostgreSQL 用户管理中创建一个新的普通登录用户，不授予 superuser。</li>
                  <li>在本步骤的连接表单中填入目标数据库名和这个普通用户名，向导会安全引用标识符并生成 SQL。</li>
                  <li>先连接 <code>postgres</code> 维护库执行第 1 段，再切换到新建业务库执行第 2 段。</li>
                  <li>最后只把这个普通用户的凭据填入安装向导。</li>
                </ol>
                <div class="install-provisioning-guide__sql">
                  <div>
                    <strong>第 1 段：连接 postgres 维护库执行</strong>
                    <el-button
                      plain
                      :disabled="!postgresProvisioningReady"
                      @click="copyPostgresProvisioningSql(
                        postgresProvisioning?.maintenanceDatabaseSql,
                        '第 1 段 SQL',
                      )"
                    >
                      <CopyDocument />复制第 1 段
                    </el-button>
                  </div>
                  <pre tabindex="0"><code>{{ postgresProvisioning?.maintenanceDatabaseSql || '填写有效的数据库名称和普通业务用户后自动生成。' }}</code></pre>
                </div>
                <div class="install-provisioning-guide__sql">
                  <div>
                    <strong>第 2 段：切换到新业务库后执行</strong>
                    <el-button
                      plain
                      :disabled="!postgresProvisioningReady"
                      @click="copyPostgresProvisioningSql(
                        postgresProvisioning?.applicationDatabaseSql,
                        '第 2 段 SQL',
                      )"
                    >
                      <CopyDocument />复制第 2 段
                    </el-button>
                  </div>
                  <pre tabindex="0"><code>{{ postgresProvisioning?.applicationDatabaseSql || '完成第 1 段并切换数据库后，再执行这里的 SQL。' }}</code></pre>
                </div>
              </div>
            </details>

            <div
              class="install-database-fields"
              :aria-busy="testingDatabase || configuringDatabase"
            >
              <div class="install-database-prerequisites">
                <el-alert
                  type="info"
                  :closable="false"
                  title="连接凭据仅用于一次性测试"
                  description="测试通过后只保留短期票据；数据库密码和 CA 证书不会写入 URL、localStorage 或 sessionStorage。"
                  show-icon
                />
              </div>

              <div class="install-form-grid is-database-address">
                <el-form-item label="数据库主机" prop="database.host">
                  <el-input
                    v-model="form.database.host"
                    :disabled="databaseFieldsLocked"
                    maxlength="253"
                    autocomplete="off"
                    autocapitalize="none"
                    spellcheck="false"
                    placeholder="db.example.com"
                    @input="invalidateDatabaseTest"
                  />
                </el-form-item>
                <el-form-item label="端口" prop="database.port">
                  <el-input
                    v-model.number="form.database.port"
                    :disabled="databaseFieldsLocked"
                    type="number"
                    inputmode="numeric"
                    min="1"
                    max="65535"
                    autocomplete="off"
                    @input="invalidateDatabaseTest"
                  />
                </el-form-item>
              </div>

              <div class="install-form-grid is-two-column">
                <el-form-item label="数据库名称" prop="database.database">
                  <el-input
                    v-model="form.database.database"
                    :disabled="databaseFieldsLocked"
                    maxlength="63"
                    autocomplete="off"
                    autocapitalize="none"
                    spellcheck="false"
                    @input="invalidateDatabaseTest"
                  />
                </el-form-item>
                <el-form-item label="数据库用户名" prop="database.username">
                  <el-input
                    v-model="form.database.username"
                    :disabled="databaseFieldsLocked"
                    maxlength="128"
                    autocomplete="username"
                    autocapitalize="none"
                    spellcheck="false"
                    @input="invalidateDatabaseTest"
                  />
                </el-form-item>
              </div>

              <el-form-item label="数据库密码" prop="database.password">
                <el-input
                  v-model="form.database.password"
                  :disabled="databaseFieldsLocked"
                  type="password"
                  show-password
                  maxlength="1024"
                  autocomplete="new-password"
                  autocapitalize="none"
                  spellcheck="false"
                  placeholder="只保留到连接测试完成"
                  @input="invalidateDatabaseTest"
                >
                  <template #prefix><Lock /></template>
                </el-input>
              </el-form-item>

              <el-form-item label="SSL 模式" prop="database.sslMode">
                <el-select
                  v-model="form.database.sslMode"
                  :disabled="databaseFieldsLocked"
                  class="install-database-ssl-select"
                  @change="handleDatabaseSslModeChange"
                >
                  <el-option
                    v-for="option in SSL_MODE_OPTIONS"
                    :key="option.value"
                    :label="option.label"
                    :value="option.value"
                  />
                </el-select>
                <p class="install-mode-help">{{ databaseSslModeHelp }}</p>
              </el-form-item>

              <div v-if="databaseUsesCaCertificate()" class="install-ca-certificate">
                <el-form-item label="CA 证书 PEM" prop="database.caCertificatePem">
                  <el-input
                    v-model="form.database.caCertificatePem"
                    :disabled="databaseFieldsLocked"
                    type="textarea"
                    :rows="4"
                    maxlength="65536"
                    resize="vertical"
                    autocomplete="off"
                    spellcheck="false"
                    placeholder="-----BEGIN CERTIFICATE-----"
                    @input="invalidateDatabaseTest"
                  />
                </el-form-item>
                <input
                  ref="caFileInput"
                  class="install-ca-file-input"
                  type="file"
                  accept=".pem,.crt,.cer,text/plain,application/x-pem-file,application/pkix-cert"
                  tabindex="-1"
                  aria-hidden="true"
                  @change="handleCaCertificateFile"
                />
                <el-button
                  :disabled="databaseFieldsLocked"
                  class="install-ca-certificate__upload"
                  @click="selectCaCertificateFile"
                >
                  选择 PEM 文件
                </el-button>
                <p>最多 64KiB，仅载入当前页面内存；测试成功、失败或离开页面后立即清除。</p>
              </div>

              <el-form-item
                v-else
                prop="database.acknowledgeUnverifiedTls"
                class="install-database-risk"
              >
                <el-alert
                  type="warning"
                  :closable="false"
                  title="REQUIRE 仅加密传输，不验证证书或主机名"
                  description="该模式不能确认连接的是目标数据库，只有在你理解并接受中间人攻击风险时使用。"
                  show-icon
                />
                <el-checkbox
                  v-model="form.database.acknowledgeUnverifiedTls"
                  :disabled="databaseFieldsLocked"
                  @change="invalidateDatabaseTest"
                >
                  我已理解 REQUIRE 不校验证书与主机名的风险
                </el-checkbox>
              </el-form-item>
            </div>

            <div class="install-database-result" aria-live="polite">
              <el-alert
                v-if="databaseConfigured"
                type="success"
                :closable="false"
                title="数据库配置已应用"
                :description="databaseConfigurationLabel"
                show-icon
              />
              <el-alert
                v-else-if="databaseTestReady"
                :type="databaseTest?.schemaState === 'READY_INSTALLED' ? 'error' : 'success'"
                :closable="false"
                title="连接测试通过"
                :description="`${databaseSchemaLabel}。连接票据约 5 分钟内有效，密码与 CA 证书已清除。`"
                show-icon
              />
              <el-alert
                v-else-if="databaseTest"
                type="warning"
                :closable="false"
                title="连接票据已过期"
                description="请重新输入数据库密码，并再次测试连接。"
                show-icon
              />
            </div>

            <el-form-item
              v-if="databaseTestReady && databaseTest?.requiresInitialization"
              prop="initializeSchema"
              class="install-database-initialize"
            >
              <el-checkbox v-model="form.initializeSchema">
                我确认在这个空数据库中创建 yunlume 系统表、索引和迁移登记。
              </el-checkbox>
            </el-form-item>

            <div class="install-database-actions">
              <el-button
                v-if="!databaseTestReady && !databaseConfigured"
                type="primary"
                :loading="testingDatabase"
                :disabled="configuringDatabase"
                @click="testDatabaseConnection"
              >
                测试数据库连接
              </el-button>
              <el-button
                v-else-if="!databaseConfigured"
                :disabled="configuringDatabase"
                @click="invalidateDatabaseTest"
              >
                修改连接配置
              </el-button>
            </div>
          </section>

          <section v-show="activeStep === 1" class="install-step" aria-labelledby="install-redis-title">
            <div class="install-step__heading">
              <span><Connection /></span>
              <div>
                <h3 id="install-redis-title" tabindex="-1">配置 Redis</h3>
                <p>连接由你维护的外部 Redis，验证 ACL、TLS 与连接可用性。</p>
              </div>
            </div>

            <p class="install-database-guidance">
              建议为 yunlume 创建独立 Redis ACL 用户和逻辑库：仅允许 <code>~nav:*</code> 键及 PING、SELECT、SET、GET、DEL，拒绝 CONFIG、ACL、FLUSH、MODULE、DEBUG 等管理命令。不要使用无密码实例或暴露公网端口。
            </p>

            <div
              class="install-redis-fields"
              :aria-busy="testingRedis || configuringRedis"
            >
              <div class="install-database-prerequisites">
                <el-alert
                  type="info"
                  :closable="false"
                  title="Redis 凭据仅用于一次性连接测试"
                  description="测试通过后只保留短期单次票据；密码和自定义 CA 不会写入 URL、localStorage 或 sessionStorage。"
                  show-icon
                />
              </div>

              <div class="install-form-grid is-database-address">
                <el-form-item label="Redis 主机" prop="redis.host">
                  <el-input
                    v-model="form.redis.host"
                    :disabled="redisFieldsLocked"
                    maxlength="253"
                    autocomplete="off"
                    autocapitalize="none"
                    spellcheck="false"
                    placeholder="redis.example.com"
                    @input="invalidateRedisTest"
                  />
                </el-form-item>
                <el-form-item label="端口" prop="redis.port">
                  <el-input
                    v-model.number="form.redis.port"
                    :disabled="redisFieldsLocked"
                    type="number"
                    inputmode="numeric"
                    min="1"
                    max="65535"
                    autocomplete="off"
                    @input="invalidateRedisTest"
                  />
                </el-form-item>
              </div>

              <div class="install-form-grid is-two-column">
                <el-form-item label="ACL 用户名（可选）" prop="redis.username">
                  <el-input
                    v-model="form.redis.username"
                    :disabled="redisFieldsLocked"
                    maxlength="128"
                    autocomplete="username"
                    autocapitalize="none"
                    spellcheck="false"
                    placeholder="留空则使用 default 用户"
                    @input="invalidateRedisTest"
                  />
                </el-form-item>
                <el-form-item label="逻辑库编号" prop="redis.database">
                  <el-input
                    v-model.number="form.redis.database"
                    :disabled="redisFieldsLocked"
                    type="number"
                    inputmode="numeric"
                    min="0"
                    max="65535"
                    autocomplete="off"
                    @input="invalidateRedisTest"
                  />
                </el-form-item>
              </div>

              <el-form-item label="Redis 密码" prop="redis.password">
                <el-input
                  v-model="form.redis.password"
                  :disabled="redisFieldsLocked"
                  type="password"
                  show-password
                  maxlength="1024"
                  autocomplete="new-password"
                  autocapitalize="none"
                  spellcheck="false"
                  placeholder="只保留到连接测试完成"
                  @input="invalidateRedisTest"
                >
                  <template #prefix><Lock /></template>
                </el-input>
              </el-form-item>

              <el-form-item label="TLS 模式" prop="redis.tlsMode">
                <el-select
                  v-model="form.redis.tlsMode"
                  :disabled="redisFieldsLocked"
                  class="install-database-ssl-select"
                  aria-describedby="redis-tls-mode-help"
                  @change="handleRedisTlsModeChange"
                >
                  <el-option
                    v-for="option in REDIS_TLS_MODE_OPTIONS"
                    :key="option.value"
                    :label="option.label"
                    :value="option.value"
                  />
                </el-select>
                <p id="redis-tls-mode-help" class="install-mode-help">{{ redisTlsModeHelp }}</p>
              </el-form-item>

              <el-alert
                v-if="form.redis.tlsMode === 'SYSTEM'"
                class="install-redis-tls-note"
                type="info"
                :closable="false"
                title="使用后端 Java 系统信任库"
                description="服务器会校验 Redis 证书链和主机名；无需、也不能另外提交 CA 文本。"
                show-icon
              />

              <div v-else-if="redisUsesCustomCaCertificate()" class="install-ca-certificate">
                <el-form-item label="Redis CA 证书链 PEM" prop="redis.caCertificatePem">
                  <el-input
                    v-model="form.redis.caCertificatePem"
                    :disabled="redisFieldsLocked"
                    type="textarea"
                    :rows="4"
                    maxlength="65536"
                    resize="vertical"
                    autocomplete="off"
                    spellcheck="false"
                    placeholder="-----BEGIN CERTIFICATE-----"
                    @input="invalidateRedisTest"
                  />
                </el-form-item>
                <input
                  ref="redisCaFileInput"
                  class="install-ca-file-input"
                  type="file"
                  accept=".pem,.crt,.cer,text/plain,application/x-pem-file,application/pkix-cert"
                  tabindex="-1"
                  aria-hidden="true"
                  @change="handleRedisCaCertificateFile"
                />
                <el-button
                  :disabled="redisFieldsLocked"
                  class="install-ca-certificate__upload"
                  @click="selectRedisCaCertificateFile"
                >
                  选择 PEM 文件
                </el-button>
                <p>最多 64KiB，仅载入当前页面内存；测试成功、失败或离开页面后立即清除。</p>
              </div>

              <el-form-item
                v-else
                prop="redis.acknowledgeInsecureTransport"
                class="install-database-risk"
              >
                <el-alert
                  type="error"
                  :closable="false"
                  title="关闭 TLS 会明文传输 Redis 凭据和数据"
                  description="后端只允许解析结果全部为可信私网地址的目标；若地址变化或包含公网 IP，测试会被拒绝。"
                  show-icon
                />
                <el-checkbox
                  v-model="form.redis.acknowledgeInsecureTransport"
                  :disabled="redisFieldsLocked"
                  @change="invalidateRedisTest"
                >
                  我确认 Redis 仅在完全受信任的私网内，并接受明文传输风险
                </el-checkbox>
              </el-form-item>

              <div class="install-form-grid is-two-column">
                <el-form-item label="建连超时（秒）" prop="redis.connectTimeoutSeconds">
                  <el-input
                    v-model.number="form.redis.connectTimeoutSeconds"
                    :disabled="redisFieldsLocked"
                    type="number"
                    inputmode="numeric"
                    min="1"
                    max="10"
                    autocomplete="off"
                    @input="invalidateRedisTest"
                  />
                </el-form-item>
                <el-form-item label="读写超时（秒）" prop="redis.readTimeoutSeconds">
                  <el-input
                    v-model.number="form.redis.readTimeoutSeconds"
                    :disabled="redisFieldsLocked"
                    type="number"
                    inputmode="numeric"
                    min="1"
                    max="10"
                    autocomplete="off"
                    @input="invalidateRedisTest"
                  />
                </el-form-item>
              </div>
            </div>

            <div class="install-database-result" aria-live="polite">
              <el-alert
                v-if="redisConfigured"
                type="success"
                :closable="false"
                title="Redis 配置已应用"
                :description="redisConfigurationLabel"
                show-icon
              />
              <el-alert
                v-else-if="redisTestReady"
                type="success"
                :closable="false"
                title="Redis 连接测试通过"
                description="连接票据约 5 分钟内有效且只能使用一次，密码与 CA 证书已清除。"
                show-icon
              />
              <el-alert
                v-else-if="redisTest"
                type="warning"
                :closable="false"
                title="Redis 连接票据已过期"
                description="请重新输入 Redis 密码，并再次测试连接。"
                show-icon
              />
            </div>

            <div class="install-database-actions">
              <el-button
                v-if="!redisTestReady && !redisConfigured"
                type="primary"
                :loading="testingRedis"
                :disabled="configuringRedis"
                @click="testRedisConnection"
              >
                测试 Redis 连接
              </el-button>
              <el-button
                v-else-if="!redisConfigured"
                :disabled="configuringRedis"
                @click="invalidateRedisTest"
              >
                修改连接配置
              </el-button>
            </div>
          </section>

          <section v-show="activeStep === 2" class="install-step" aria-labelledby="install-environment-title">
            <div class="install-step__heading">
              <span><Setting /></span>
              <div>
                <h3 id="install-environment-title" tabindex="-1">环境检查</h3>
                <p>检查数据库结构、上传存储与 Redis 实际读写状态。</p>
              </div>
            </div>

            <div class="install-status" aria-live="polite">
              <el-alert
                v-if="environmentReady"
                type="success"
                :closable="false"
                title="运行环境已准备完成"
                description="数据库、缓存与持久化存储检查均已通过。"
                show-icon
              />
              <el-alert
                v-else
                type="warning"
                :closable="false"
                title="运行环境尚未就绪"
                description="请根据下方检查结果修复服务器配置，再重新检查。"
                show-icon
              />
            </div>

            <div class="install-checks">
              <article
                v-for="item in checkItems"
                :key="item.key"
                class="install-check"
                :class="{ 'is-ok': item.check.ok, 'is-error': !item.check.ok }"
              >
                <component :is="item.check.ok ? CircleCheckFilled : CircleCloseFilled" aria-hidden="true" />
                <div>
                  <strong>{{ item.label }}</strong>
                  <p>{{ item.check.message }}</p>
                </div>
              </article>
            </div>
            <el-button class="install-step__retry" :loading="checking" @click="checkEnvironment">
              <Refresh />重新执行安全检查
            </el-button>
          </section>

          <section v-show="activeStep === 3" class="install-step" aria-labelledby="install-site-title">
            <div class="install-step__heading">
              <span><Setting /></span>
              <div>
                <h3 id="install-site-title" tabindex="-1">站点信息</h3>
                <p>这些内容会显示在公开导航首页，稍后仍可在后台修改。</p>
              </div>
            </div>
            <div class="install-form-grid">
              <el-form-item label="站点名称" prop="siteName">
                <el-input v-model="form.siteName" maxlength="50" show-word-limit autocomplete="organization" />
              </el-form-item>
              <el-form-item label="站点简介（可选）" prop="siteDescription">
                <el-input v-model="form.siteDescription" maxlength="255" show-word-limit />
              </el-form-item>
            </div>
          </section>

          <section v-show="activeStep === 4" class="install-step" aria-labelledby="install-admin-title">
            <div class="install-step__heading">
              <span><User /></span>
              <div>
                <h3 id="install-admin-title" tabindex="-1">创建首位管理员</h3>
                <p>管理员账号用于登录后台，请使用唯一且足够强的密码。</p>
              </div>
            </div>
            <div class="install-form-grid is-two-column">
              <el-form-item label="管理员用户名" prop="username">
                <el-input v-model="form.username" maxlength="32" autocomplete="username">
                  <template #prefix><User /></template>
                </el-input>
              </el-form-item>
              <el-form-item label="管理员昵称" prop="nickname">
                <el-input v-model="form.nickname" maxlength="50" autocomplete="nickname" />
              </el-form-item>
              <el-form-item label="管理员密码" prop="password">
                <el-input v-model="form.password" type="password" show-password autocomplete="new-password">
                  <template #prefix><Lock /></template>
                </el-input>
              </el-form-item>
              <el-form-item label="确认管理员密码" prop="confirmPassword">
                <el-input v-model="form.confirmPassword" type="password" show-password autocomplete="new-password">
                  <template #prefix><Lock /></template>
                </el-input>
              </el-form-item>
            </div>

            <div class="install-password-policy" :class="`is-${passwordPolicy.strength}`" aria-live="polite">
              <div class="install-password-policy__summary">
                <span>密码强度</span>
                <strong>{{ strengthLabel }}</strong>
              </div>
              <div class="install-password-policy__track" aria-hidden="true"><i :style="{ width: strengthWidth }" /></div>
              <ul>
                <li :class="{ 'is-valid': passwordPolicy.lengthValid }">至少 {{ PASSWORD_MIN_LENGTH }} 个字符，且不超过 {{ PASSWORD_MAX_LENGTH }} 个 UTF-8 字节</li>
                <li :class="{ 'is-valid': passwordPolicy.whitespaceFree && form.password.length > 0 }">不包含空格或其他空白字符</li>
                <li :class="{ 'is-valid': passwordPolicy.categoriesValid }">大写、小写、数字、符号至少三类（当前 {{ passwordPolicy.categoryCount }}/4）</li>
                <li :class="{ 'is-valid': passwordPolicy.usernameFree && form.password.length > 0 }">不包含管理员用户名</li>
              </ul>
            </div>
          </section>

          <section v-show="activeStep === 5" class="install-step" aria-labelledby="install-confirm-title">
            <div class="install-step__heading">
              <span><Key /></span>
              <div>
                <h3 id="install-confirm-title" tabindex="-1">确认并完成安装</h3>
                <p>核对公开信息与管理员身份，确认后将不可再次运行网页安装。</p>
              </div>
            </div>

            <dl class="install-summary">
              <div><dt>站点名称</dt><dd>{{ form.siteName }}</dd></div>
              <div><dt>站点简介</dt><dd>{{ form.siteDescription || '未填写' }}</dd></div>
              <div><dt>管理员用户名</dt><dd>{{ form.username }}</dd></div>
              <div><dt>管理员昵称</dt><dd>{{ form.nickname }}</dd></div>
              <div><dt>数据库</dt><dd>{{ databaseConfigurationLabel }}</dd></div>
              <div><dt>Redis</dt><dd>{{ redisConfigurationLabel }}</dd></div>
            </dl>

            <el-form-item prop="confirmationAccepted" class="install-confirmation-field">
              <el-checkbox v-model="form.confirmationAccepted">
                我已核对以上信息，并知晓安装成功后网页向导将永久关闭。
              </el-checkbox>
            </el-form-item>
          </section>
        </el-form>

        <footer class="install-wizard__actions">
          <el-button
            v-if="canGoPrevious"
            :disabled="testingDatabase || configuringDatabase || testingRedis || configuringRedis || checking || submitting"
            @click="previousStep"
          >
            上一步
          </el-button>
          <span v-else />
          <el-button
            v-if="activeStep < 5"
            type="primary"
            :loading="
              (activeStep === 0 && configuringDatabase)
                || (activeStep === 1 && configuringRedis)
                || (activeStep === 2 && checking)
            "
            :disabled="primaryActionDisabled"
            @click="nextStep"
          >
            {{
              activeStep === 0 && !databaseConfigured
                ? '应用 PostgreSQL 配置'
                : activeStep === 1 && !redisConfigured
                  ? '应用 Redis 配置'
                  : '下一步'
            }} <Right />
          </el-button>
          <el-button
            v-else
            type="primary"
            :loading="submitting"
            :disabled="submissionFinished"
            @click="completeInstallation"
          >
            完成安装
          </el-button>
        </footer>
      </section>
    </main>
  </div>
</template>
