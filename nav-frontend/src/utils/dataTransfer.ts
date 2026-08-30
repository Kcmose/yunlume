import type {
  DataImportConfirmResult,
  DataImportIssue,
  DataImportJob,
  DataImportJobSession,
  DataImportJobStage,
  DataImportPreview,
  DataTransferResourceKey,
} from '@/types/dataTransfer'
import { getHttpStatus } from './httpError'

export const DATA_IMPORT_CONFIRMATION_TEXT = '确认导入'
export const DATA_IMPORT_MAX_BYTES = 64 * 1024 * 1024
export const DATA_IMPORT_JOB_SESSION_KEY = 'yunlume_data_import_job'

export type DataImportClientState =
  | 'IDLE'
  | 'UPLOADING'
  | 'PREVIEWING'
  | 'READY'
  | 'BLOCKED'
  | 'CONFIRMING'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'

export interface ImportFileLike {
  name: string
  size: number
  type?: string
}

export interface ImportConfirmationInput {
  preview: DataImportPreview | null
  backupConfirmed: boolean
  confirmationText: string
  submitting?: boolean
  now?: number
}

export interface DataImportIssueGroup {
  key: string
  label: string
  issues: DataImportIssue[]
}

export interface StorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

const acceptedZipTypes = new Set([
  '',
  'application/zip',
  'application/x-zip-compressed',
  'application/octet-stream',
])

const resourceKeys: readonly DataTransferResourceKey[] = [
  'siteConfigs',
  'categories',
  'bookmarks',
  'searchEngines',
  'customLinks',
  'assets',
]

const importJobStages = new Set<DataImportJobStage>([
  'PREPARING',
  'WRITING',
  'VERIFYING',
  'COMPLETED',
  'FAILED',
])

const issueScopeLabels: Record<string, string> = {
  packageInfo: '备份清单',
  manifest: '备份清单',
  data: '业务数据',
  siteConfigs: '站点配置',
  categories: '分类',
  bookmarks: '书签',
  searchEngines: '搜索引擎',
  customLinks: '自定义链接',
  assets: '背景图片',
  archive: '备份文件',
  global: '全局',
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}

function isValidIssue(value: unknown): value is DataImportIssue {
  return isRecord(value)
    && typeof value.code === 'string'
    && value.code.length > 0
    && typeof value.message === 'string'
    && value.message.length > 0
    && (value.path === undefined || isNullableString(value.path))
}

function hasResourceCounts(value: unknown): boolean {
  return isRecord(value) && resourceKeys.every((key) => isNonNegativeInteger(value[key]))
}

function hasDiffItem(value: unknown): boolean {
  return isRecord(value)
    && isNonNegativeInteger(value.added)
    && isNonNegativeInteger(value.updated)
    && isNonNegativeInteger(value.deleted)
    && isNonNegativeInteger(value.unchanged)
}

function contractError(subject: string): Error {
  return Object.assign(new Error(`服务端返回的${subject}响应不完整`), { status: 502 })
}

export function parseDataImportPreview(value: unknown): DataImportPreview {
  if (!isRecord(value)) throw contractError('导入预检')

  const packageInfo = value.packageInfo
  const counts = value.counts
  const diff = value.diff
  const validPackageInfo = isRecord(packageInfo)
    && isNonNegativeInteger(packageInfo.formatVersion)
    && isNullableString(packageInfo.exportedAt)
    && isNullableString(packageInfo.generator)
    && typeof packageInfo.archiveSha256 === 'string'
  const validCounts = isRecord(counts)
    && hasResourceCounts(counts.current)
    && hasResourceCounts(counts.imported)
  const validDiff = isRecord(diff)
    && [...resourceKeys, 'total'].every((key) => hasDiffItem(diff[key]))
  const validIssues = Array.isArray(value.errors)
    && value.errors.every(isValidIssue)
    && Array.isArray(value.warnings)
    && value.warnings.every(isValidIssue)

  if (
    !isNullableString(value.previewToken)
    || !isNullableString(value.expiresAt)
    || !validPackageInfo
    || !validCounts
    || !validDiff
    || !validIssues
  ) {
    throw contractError('导入预检')
  }
  return value as unknown as DataImportPreview
}

export function parseDataImportConfirmResult(value: unknown): DataImportConfirmResult {
  if (!isRecord(value) || typeof value.jobId !== 'string' || !value.jobId.trim()) {
    throw contractError('导入确认')
  }
  return { jobId: value.jobId }
}

export function parseDataImportJob(value: unknown): DataImportJob {
  if (!isRecord(value)) throw contractError('导入任务')
  const error = value.error
  const validError = error === undefined || error === null || isValidIssue(error)
  const validTimes = typeof value.createdAt === 'string'
    && (value.startedAt === undefined || isNullableString(value.startedAt))
    && (value.finishedAt === undefined || isNullableString(value.finishedAt))
  if (
    typeof value.jobId !== 'string'
    || !value.jobId.trim()
    || typeof value.stage !== 'string'
    || !importJobStages.has(value.stage as DataImportJobStage)
    || typeof value.message !== 'string'
    || !validTimes
    || !validError
  ) {
    throw contractError('导入任务')
  }
  return value as unknown as DataImportJob
}

export function validateImportFile(
  file: ImportFileLike | null | undefined,
  maxBytes = DATA_IMPORT_MAX_BYTES,
): string | null {
  if (!file) return '请选择 ZIP 备份文件'
  if (!file.name.trim().toLocaleLowerCase().endsWith('.zip')) {
    return '只支持 .zip 备份文件'
  }
  if (!Number.isFinite(file.size) || file.size <= 0) return '备份文件为空或大小无效'
  if (file.size > maxBytes) return `备份文件不能超过 ${formatBytes(maxBytes)}`
  const contentType = file.type?.trim().toLocaleLowerCase() ?? ''
  if (!acceptedZipTypes.has(contentType)) return '文件类型不是可识别的 ZIP 格式'
  return null
}

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '0 B'
  if (bytes < 1024) return `${Math.round(bytes)} B`
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(bytes < 10 * 1024 ? 1 : 0)} KB`
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(bytes < 10 * 1024 ** 2 ? 1 : 0)} MB`
  return `${(bytes / 1024 ** 3).toFixed(1)} GB`
}

function sanitizeDownloadFilename(value: string, fallback: string, extension: string): string {
  const leaf = value.replace(/\\/g, '/').split('/').pop()?.trim() ?? ''
  const safe = leaf
    .replace(/[\u0000-\u001f\u007f\u202a-\u202e\u2066-\u2069<>:"|?*]/g, '_')
    .replace(/^\.+/, '')
    .replace(/[. ]+$/, '')
    .slice(0, 180)
  return safe && safe.toLocaleLowerCase().endsWith(extension) ? safe : fallback
}

function decodeExtendedFilename(value: string): string | null {
  const normalized = value.trim().replace(/^"|"$/g, '')
  const match = normalized.match(/^UTF-8''(.+)$/i)
  if (!match) return null
  try {
    return decodeURIComponent(match[1])
  } catch {
    return null
  }
}

export function extractDownloadFilename(
  contentDisposition: string | null | undefined,
  fallback = 'yunlume-backup.zip',
): string {
  return extractFilenameForExtension(
    contentDisposition,
    fallback,
    'yunlume-backup.zip',
    '.zip',
  )
}

export function extractMarkdownDownloadFilename(
  contentDisposition: string | null | undefined,
  fallback = 'yunlume-bookmarks.md',
): string {
  return extractFilenameForExtension(
    contentDisposition,
    fallback,
    'yunlume-bookmarks.md',
    '.md',
  )
}

function extractFilenameForExtension(
  contentDisposition: string | null | undefined,
  fallback: string,
  defaultFallback: string,
  extension: string,
): string {
  const safeFallback = sanitizeDownloadFilename(fallback, defaultFallback, extension)
  if (!contentDisposition) return safeFallback

  const extendedMatch = contentDisposition.match(/(?:^|;)\s*filename\*\s*=\s*([^;]+)/i)
  const extended = extendedMatch ? decodeExtendedFilename(extendedMatch[1]) : null
  if (extended) return sanitizeDownloadFilename(extended, safeFallback, extension)

  const quotedMatch = contentDisposition.match(/(?:^|;)\s*filename\s*=\s*"([^"]*)"/i)
  if (quotedMatch?.[1]) return sanitizeDownloadFilename(quotedMatch[1], safeFallback, extension)

  const plainMatch = contentDisposition.match(/(?:^|;)\s*filename\s*=\s*([^;]+)/i)
  return plainMatch?.[1]
    ? sanitizeDownloadFilename(plainMatch[1].trim(), safeFallback, extension)
    : safeFallback
}

export function groupImportIssues(issues: readonly DataImportIssue[]): DataImportIssueGroup[] {
  const groups = new Map<string, DataImportIssue[]>()
  issues.forEach((issue) => {
    const scope = issue.path?.trim().match(/^([A-Za-z][A-Za-z0-9]*)/)?.[1] ?? 'global'
    const list = groups.get(scope) ?? []
    list.push(issue)
    groups.set(scope, list)
  })
  return Array.from(groups, ([key, groupedIssues]) => ({
    key,
    label: issueScopeLabels[key] ?? key,
    issues: groupedIssues,
  }))
}

export function previewState(preview: DataImportPreview): DataImportClientState {
  return preview.errors.length > 0 || !preview.previewToken || !preview.expiresAt
    ? 'BLOCKED'
    : 'READY'
}

export function isImportJobTerminal(stage: DataImportJobStage): boolean {
  return stage === 'COMPLETED' || stage === 'FAILED'
}

export function clientStateForJob(job: DataImportJob): DataImportClientState {
  if (job.stage === 'COMPLETED') return 'COMPLETED'
  if (job.stage === 'FAILED') return 'FAILED'
  return 'RUNNING'
}

export function canConfirmImport({
  preview,
  backupConfirmed,
  confirmationText,
  submitting = false,
  now = Date.now(),
}: ImportConfirmationInput): boolean {
  if (!preview || submitting || preview.errors.length > 0 || !backupConfirmed) return false
  if (confirmationText !== DATA_IMPORT_CONFIRMATION_TEXT) return false
  if (!preview.previewToken || !preview.expiresAt) return false
  const expiresAt = Date.parse(preview.expiresAt)
  return Number.isFinite(expiresAt) && expiresAt > now
}

export function writeImportJobSession(storage: StorageLike, session: DataImportJobSession): void {
  storage.setItem(DATA_IMPORT_JOB_SESSION_KEY, JSON.stringify(session))
}

export function readImportJobSession(storage: StorageLike): DataImportJobSession | null {
  const raw = storage.getItem(DATA_IMPORT_JOB_SESSION_KEY)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as Partial<DataImportJobSession>
    if (
      typeof parsed.jobId !== 'string'
      || !/^[A-Za-z0-9_-]{1,128}$/.test(parsed.jobId)
      || typeof parsed.startedAt !== 'string'
      || !Number.isFinite(Date.parse(parsed.startedAt))
    ) {
      storage.removeItem(DATA_IMPORT_JOB_SESSION_KEY)
      return null
    }
    return { jobId: parsed.jobId, startedAt: parsed.startedAt }
  } catch {
    storage.removeItem(DATA_IMPORT_JOB_SESSION_KEY)
    return null
  }
}

export function clearImportJobSession(storage: StorageLike): void {
  storage.removeItem(DATA_IMPORT_JOB_SESSION_KEY)
}

type DataTransferAction = 'export' | 'markdown' | 'preview' | 'confirm' | 'status'

export function describeDataTransferError(error: unknown, action: DataTransferAction): string {
  const status = getHttpStatus(error)
  const serverMessage = error instanceof Error ? error.message.trim() : ''
  const detail = serverMessage && !/^Request failed/i.test(serverMessage) ? `：${serverMessage}` : ''

  if (status === 409 && action === 'confirm' && /正在执行|稍后重试/.test(serverMessage)) {
    return `已有导入任务正在执行，请稍后再次确认${detail}`
  }
  if (status === 409) return `预检结果已过期或数据已变化，请重新选择文件并预检${detail}`
  if ((status === 404 || status === 410) && action === 'confirm') {
    return `预检结果不存在或已失效，请重新选择文件并预检${detail}`
  }
  if (status === 400) {
    return action === 'markdown'
      ? `请求参数不正确，无法生成书签备份${detail}`
      : `ZIP 备份损坏、格式不支持或包含不安全内容${detail}`
  }
  if (status === 413) return `备份文件超过服务端允许的大小${detail}`
  if (status === 422) return `备份内容未通过服务端校验，请修正备份内容后重新预检${detail}`
  if (status === 429) return `操作过于频繁，请稍后重试${detail}`
  if (status && status >= 500) {
    return action === 'confirm'
      ? `导入未完成；任务未创建，或服务端已回滚本次写入${detail}`
      : `服务暂时不可用，请稍后重试${detail}`
  }
  if (status) return `请求失败（HTTP ${status}）${detail}`
  return `无法连接服务，请检查网络后重试${detail}`
}
