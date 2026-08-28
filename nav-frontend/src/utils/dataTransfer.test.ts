import { describe, expect, it } from 'vitest'
import type {
  DataImportJob,
  DataImportPreview,
  DataTransferResourceCounts,
} from '@/types/dataTransfer'
import {
  canConfirmImport,
  clearImportJobSession,
  clientStateForJob,
  DATA_IMPORT_CONFIRMATION_TEXT,
  DATA_IMPORT_JOB_SESSION_KEY,
  describeDataTransferError,
  extractDownloadFilename,
  extractMarkdownDownloadFilename,
  formatBytes,
  groupImportIssues,
  isImportJobTerminal,
  parseDataImportConfirmResult,
  parseDataImportJob,
  parseDataImportPreview,
  previewState,
  readImportJobSession,
  validateImportFile,
  writeImportJobSession,
  type StorageLike,
} from './dataTransfer'

const emptyCounts: DataTransferResourceCounts = {
  siteConfigs: 1,
  categories: 0,
  bookmarks: 0,
  searchEngines: 0,
  customLinks: 0,
  assets: 0,
}

function preview(overrides: Partial<DataImportPreview> = {}): DataImportPreview {
  const item = { added: 0, updated: 0, deleted: 0, unchanged: 0 }
  return {
    previewToken: 'preview_abc123',
    expiresAt: '2030-01-01T00:00:00Z',
    packageInfo: {
      formatVersion: 1,
      exportedAt: '2026-08-12T00:00:00Z',
      generator: 'yunlume',
      archiveSha256: 'a'.repeat(64),
    },
    counts: { current: { ...emptyCounts }, imported: { ...emptyCounts } },
    diff: {
      siteConfigs: { ...item },
      categories: { ...item },
      bookmarks: { ...item },
      searchEngines: { ...item },
      customLinks: { ...item },
      assets: { ...item },
      total: { ...item },
    },
    errors: [],
    warnings: [],
    ...overrides,
  }
}

function job(stage: DataImportJob['stage']): DataImportJob {
  return {
    jobId: 'job_123',
    stage,
    createdAt: '2026-08-12T00:00:00Z',
    message: stage,
  }
}

class MemoryStorage implements StorageLike {
  readonly values = new Map<string, string>()
  removed: string[] = []

  getItem(key: string) {
    return this.values.get(key) ?? null
  }

  setItem(key: string, value: string) {
    this.values.set(key, value)
  }

  removeItem(key: string) {
    this.removed.push(key)
    this.values.delete(key)
  }
}

describe('data import file validation', () => {
  it('accepts ordinary ZIP MIME types and case-insensitive extensions', () => {
    expect(validateImportFile({ name: 'Backup.ZIP', size: 1024, type: 'application/zip' }, 2048)).toBeNull()
    expect(validateImportFile({ name: 'backup.zip', size: 1024, type: '' }, 2048)).toBeNull()
    expect(validateImportFile({ name: 'backup.zip', size: 1024, type: 'application/octet-stream' }, 2048)).toBeNull()
  })

  it('rejects a missing file, a non-ZIP extension and an unsupported MIME', () => {
    expect(validateImportFile(null)).toContain('请选择')
    expect(validateImportFile({ name: 'backup.json', size: 1, type: 'application/zip' })).toContain('.zip')
    expect(validateImportFile({ name: 'backup.zip', size: 1, type: 'text/plain' })).toContain('ZIP')
  })

  it('rejects empty, non-finite and oversized archives', () => {
    expect(validateImportFile({ name: 'backup.zip', size: 0 })).toContain('为空')
    expect(validateImportFile({ name: 'backup.zip', size: Number.NaN })).toContain('无效')
    expect(validateImportFile({ name: 'backup.zip', size: 2049 }, 2048)).toContain('2.0 KB')
  })

  it('formats byte limits for user-facing messages', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1024)).toBe('1.0 KB')
    expect(formatBytes(64 * 1024 * 1024)).toBe('64 MB')
  })
})

describe('data import confirmation gate', () => {
  it('allows confirmation only with a valid preview, backup acknowledgement and exact phrase', () => {
    expect(canConfirmImport({
      preview: preview(),
      backupConfirmed: true,
      confirmationText: DATA_IMPORT_CONFIRMATION_TEXT,
      now: Date.parse('2029-12-31T00:00:00Z'),
    })).toBe(true)
  })

  it('blocks missing acknowledgement, wrong phrase and an active submission', () => {
    const input = { preview: preview(), now: Date.parse('2029-12-31T00:00:00Z') }
    expect(canConfirmImport({ ...input, backupConfirmed: false, confirmationText: DATA_IMPORT_CONFIRMATION_TEXT })).toBe(false)
    expect(canConfirmImport({ ...input, backupConfirmed: true, confirmationText: '导入' })).toBe(false)
    expect(canConfirmImport({ ...input, backupConfirmed: true, confirmationText: ` ${DATA_IMPORT_CONFIRMATION_TEXT}` })).toBe(false)
    expect(canConfirmImport({ ...input, backupConfirmed: true, confirmationText: DATA_IMPORT_CONFIRMATION_TEXT, submitting: true })).toBe(false)
  })

  it('blocks hard errors, absent tokens and expired previews', () => {
    const hardError = preview({ errors: [{ code: 'INVALID_URL', path: 'bookmarks[1].url', message: '地址错误' }] })
    const noToken = preview({ previewToken: null })
    const expired = preview({ expiresAt: '2020-01-01T00:00:00Z' })
    const base = { backupConfirmed: true, confirmationText: DATA_IMPORT_CONFIRMATION_TEXT, now: Date.parse('2029-12-31T00:00:00Z') }
    expect(canConfirmImport({ ...base, preview: hardError })).toBe(false)
    expect(canConfirmImport({ ...base, preview: noToken })).toBe(false)
    expect(canConfirmImport({ ...base, preview: expired })).toBe(false)
  })
})

describe('data import response contracts', () => {
  it('accepts the complete preview contract and rejects an incompatible formatVersion', () => {
    const valid = preview()
    expect(parseDataImportPreview(valid)).toBe(valid)
    expect(() => parseDataImportPreview({
      ...valid,
      packageInfo: { ...valid.packageInfo, formatVersion: '1' },
    })).toThrow('预检响应不完整')
  })

  it('preserves semantic preview errors even when manifest metadata is unavailable', () => {
    const semanticFailure = preview({
      previewToken: null,
      expiresAt: null,
      packageInfo: {
        formatVersion: 0,
        exportedAt: null,
        generator: null,
        archiveSha256: 'a'.repeat(64),
      },
      errors: [{ code: 'MANIFEST_REQUIRED', path: 'manifest.json', message: '清单不能为空' }],
    })
    expect(parseDataImportPreview(semanticFailure).errors[0].code).toBe('MANIFEST_REQUIRED')
  })

  it('requires a non-empty job ID after confirmation', () => {
    expect(parseDataImportConfirmResult({ jobId: 'job_123' })).toEqual({ jobId: 'job_123' })
    expect(() => parseDataImportConfirmResult({ jobId: '' })).toThrow('确认响应不完整')
  })

  it('accepts known job stages and rejects an unknown stage', () => {
    expect(parseDataImportJob(job('WRITING')).stage).toBe('WRITING')
    expect(() => parseDataImportJob({ ...job('WRITING'), stage: 'CANCELLED' })).toThrow('任务响应不完整')
  })
})

describe('data import issue grouping', () => {
  it('groups indexed paths by top-level resource and preserves input order', () => {
    const groups = groupImportIssues([
      { code: 'A', path: 'bookmarks[17].url', message: '错误 A' },
      { code: 'B', path: 'categories[1].name', message: '错误 B' },
      { code: 'C', path: 'bookmarks[2].categoryId', message: '错误 C' },
    ])
    expect(groups.map((item) => item.key)).toEqual(['bookmarks', 'categories'])
    expect(groups[0].label).toBe('书签')
    expect(groups[0].issues.map((item) => item.code)).toEqual(['A', 'C'])
  })

  it('places missing and malformed paths in the global group', () => {
    const groups = groupImportIssues([
      { code: 'A', message: '全局错误' },
      { code: 'B', path: '[0]', message: '无效路径' },
    ])
    expect(groups).toHaveLength(1)
    expect(groups[0].key).toBe('global')
    expect(groups[0].issues).toHaveLength(2)
  })
})

describe('Content-Disposition filename handling', () => {
  it('prefers and decodes RFC 5987 filename*', () => {
    expect(extractDownloadFilename(
      "attachment; filename=backup.zip; filename*=UTF-8''%E5%AF%BC%E8%88%AA%E5%A4%87%E4%BB%BD.zip",
    )).toBe('导航备份.zip')
  })

  it('supports quoted and plain legacy filenames', () => {
    expect(extractDownloadFilename('attachment; filename="backup 2026.zip"')).toBe('backup 2026.zip')
    expect(extractDownloadFilename('attachment; filename=backup.zip')).toBe('backup.zip')
  })

  it('drops path segments and unsafe filename characters', () => {
    expect(extractDownloadFilename('attachment; filename="../bad:name?.zip"')).toBe('bad_name_.zip')
  })

  it('rejects non-ZIP names and unsupported filename* encodings', () => {
    expect(extractDownloadFilename('attachment; filename="malware.exe"', 'safe.zip')).toBe('safe.zip')
    expect(extractDownloadFilename("attachment; filename*=ISO-8859-1''backup.zip", 'safe.zip')).toBe('safe.zip')
  })

  it('uses the fallback for absent or unusable headers', () => {
    expect(extractDownloadFilename(undefined, 'safe.zip')).toBe('safe.zip')
    expect(extractDownloadFilename('inline', 'safe.zip')).toBe('safe.zip')
  })

  it('extracts a safe UTF-8 Markdown backup filename independently from ZIP exports', () => {
    expect(extractMarkdownDownloadFilename(
      "attachment; filename=bookmarks.md; filename*=UTF-8''MY%E5%AF%BC%E8%88%AA%E4%B9%A6%E7%AD%BE.md",
    )).toBe('MY导航书签.md')
    expect(extractMarkdownDownloadFilename('attachment; filename="../bad:name?.md"')).toBe('bad_name_.md')
    expect(extractMarkdownDownloadFilename('attachment; filename="backup.zip"')).toBe('yunlume-bookmarks.md')
  })

  it('falls back safely for unsupported Markdown filename encodings and extensions', () => {
    expect(extractMarkdownDownloadFilename(
      "attachment; filename*=ISO-8859-1''bookmarks.md",
      'safe-bookmarks.md',
    )).toBe('safe-bookmarks.md')
    expect(extractMarkdownDownloadFilename(undefined, '../unsafe.md')).toBe('unsafe.md')
    expect(extractMarkdownDownloadFilename(undefined, 'unsafe.txt')).toBe('yunlume-bookmarks.md')
  })
})

describe('import state and polling terminal rules', () => {
  it('marks previews with hard errors as blocked', () => {
    expect(previewState(preview())).toBe('READY')
    expect(previewState(preview({ errors: [{ code: 'X', message: '错误' }] }))).toBe('BLOCKED')
    expect(previewState(preview({ previewToken: null }))).toBe('BLOCKED')
  })

  it('polls all three active stages and stops only at completed or failed', () => {
    expect(isImportJobTerminal('PREPARING')).toBe(false)
    expect(isImportJobTerminal('WRITING')).toBe(false)
    expect(isImportJobTerminal('VERIFYING')).toBe(false)
    expect(isImportJobTerminal('COMPLETED')).toBe(true)
    expect(isImportJobTerminal('FAILED')).toBe(true)
  })

  it('maps server job stages to client terminal states', () => {
    expect(clientStateForJob(job('PREPARING'))).toBe('RUNNING')
    expect(clientStateForJob(job('VERIFYING'))).toBe('RUNNING')
    expect(clientStateForJob(job('COMPLETED'))).toBe('COMPLETED')
    expect(clientStateForJob(job('FAILED'))).toBe('FAILED')
  })
})

describe('import job session recovery', () => {
  it('round-trips a valid job session and clears it explicitly', () => {
    const storage = new MemoryStorage()
    const session = { jobId: 'job_ABC-123', startedAt: '2026-08-12T01:02:03Z' }
    writeImportJobSession(storage, session)
    expect(readImportJobSession(storage)).toEqual(session)
    clearImportJobSession(storage)
    expect(storage.getItem(DATA_IMPORT_JOB_SESSION_KEY)).toBeNull()
  })

  it('removes malformed JSON and invalid job identifiers', () => {
    const storage = new MemoryStorage()
    storage.values.set(DATA_IMPORT_JOB_SESSION_KEY, '{bad json')
    expect(readImportJobSession(storage)).toBeNull()
    expect(storage.removed).toContain(DATA_IMPORT_JOB_SESSION_KEY)

    storage.values.set(DATA_IMPORT_JOB_SESSION_KEY, JSON.stringify({ jobId: '../unsafe', startedAt: '2026-08-12T01:02:03Z' }))
    expect(readImportJobSession(storage)).toBeNull()
  })

  it('removes sessions without a parseable timestamp', () => {
    const storage = new MemoryStorage()
    storage.values.set(DATA_IMPORT_JOB_SESSION_KEY, JSON.stringify({ jobId: 'job_123', startedAt: 'not-a-date' }))
    expect(readImportJobSession(storage)).toBeNull()
  })
})

describe('data transfer error messages', () => {
  it('explains conflict, upload-size, validation and rate-limit statuses', () => {
    expect(describeDataTransferError({ status: 409 }, 'confirm')).toContain('重新选择')
    expect(describeDataTransferError(Object.assign(new Error('已有导入任务正在执行'), { status: 409 }), 'confirm')).toContain('稍后再次确认')
    expect(describeDataTransferError({ status: 404 }, 'confirm')).toContain('已失效')
    expect(describeDataTransferError({ status: 400 }, 'preview')).toContain('ZIP')
    expect(describeDataTransferError({ status: 413 }, 'preview')).toContain('超过')
    expect(describeDataTransferError({ status: 422 }, 'preview')).toContain('校验')
    expect(describeDataTransferError({ status: 429 }, 'export')).toContain('频繁')
    expect(describeDataTransferError({ status: 400 }, 'markdown')).toContain('书签备份')
  })

  it('distinguishes transactional confirm failures from a network failure', () => {
    expect(describeDataTransferError({ status: 500 }, 'confirm')).toContain('回滚')
    expect(describeDataTransferError(new Error('Network Error'), 'status')).toContain('网络')
  })
})
