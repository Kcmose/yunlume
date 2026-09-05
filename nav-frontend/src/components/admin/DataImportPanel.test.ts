import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AxiosProgressEvent } from 'axios'
import { ElMessageBox } from 'element-plus'
import {
  confirmNavigationDataImport,
  getCurrentNavigationDataImportJob,
  getNavigationDataImportJob,
  getNavigationDataImportJobByPreviewToken,
  previewNavigationDataImport,
} from '@/api/data.api'
import { deferred, mountComponent } from '@/test/componentHarness'
import type { DataImportJob, DataImportPreview } from '@/types/dataTransfer'
import {
  DATA_IMPORT_CONFIRMATION_TEXT,
  DATA_IMPORT_JOB_SESSION_KEY,
  readImportJobSession,
  readImportConfirmationSession,
  writeImportConfirmationSession,
  writeImportJobSession,
  type DataImportClientState,
  type StorageLike,
} from '@/utils/dataTransfer'
import DataImportPanel from './DataImportPanel.vue'
import ImportPreviewDialog from './ImportPreviewDialog.vue'

vi.mock('@/api/data.api', () => ({
  confirmNavigationDataImport: vi.fn(),
  getCurrentNavigationDataImportJob: vi.fn(),
  getNavigationDataImportJob: vi.fn(),
  getNavigationDataImportJobByPreviewToken: vi.fn(),
  previewNavigationDataImport: vi.fn(),
}))

vi.mock('element-plus', () => ({ ElMessageBox: { confirm: vi.fn() } }))

interface PanelState {
  state: DataImportClientState
  selectedFile: File | null
  uploadPercent: number | null
  preview: DataImportPreview | null
  previewRequestError: string
  persistentError: string
  jobStatusError: string
  busy: boolean
  canPreview: boolean
  job: DataImportJob | null
  progressVisible: boolean
  pendingConfirmation: { previewToken: string; startedAt: string } | null
  confirmationNotFound: boolean
  confirmationRecoveryError: string
  restartAfterUnknownResult(): Promise<void>
  recoverConfirmation(): Promise<void>
  setFile(file: File | null): void
  runPreview(): Promise<void>
  confirmImport(): Promise<void>
  handlePreviewExpired(): void
}

interface PreviewState {
  backupConfirmed: boolean
  confirmationText: string
  confirmEnabled: boolean
  previewExpired: boolean
  updateVisible(visible: boolean): void
}

const previewApi = vi.mocked(previewNavigationDataImport)
const confirmApi = vi.mocked(confirmNavigationDataImport)
const jobApi = vi.mocked(getNavigationDataImportJob)
const currentJobApi = vi.mocked(getCurrentNavigationDataImportJob)
const tokenJobApi = vi.mocked(getNavigationDataImportJobByPreviewToken)
const cleanups = new Set<() => void>()
let storage: StorageLike

function previewResult(expiresIn = 5_000): DataImportPreview {
  const counts = { siteConfigs: 1, categories: 0, bookmarks: 0, searchEngines: 0, customLinks: 0, assets: 0 }
  const diff = { added: 0, updated: 0, deleted: 0, unchanged: 0 }
  return {
    previewToken: 'preview_A',
    expiresAt: new Date(Date.now() + expiresIn).toISOString(),
    packageInfo: { formatVersion: 1, exportedAt: null, generator: 'fixture', archiveSha256: 'a'.repeat(64) },
    counts: { current: { ...counts }, imported: { ...counts } },
    diff: {
      siteConfigs: { ...diff }, categories: { ...diff }, bookmarks: { ...diff },
      searchEngines: { ...diff }, customLinks: { ...diff }, assets: { ...diff }, total: { ...diff },
    },
    errors: [],
    warnings: [],
  }
}

function jobResult(jobId = 'job_A', stage: DataImportJob['stage'] = 'WRITING'): DataImportJob {
  return { jobId, stage, createdAt: new Date().toISOString(), message: stage }
}

function file(name = 'first.zip'): File {
  // API 边界被 mock，只需要真实组件文件校验所消费的 File 字段。
  return { name, size: 1_024, type: 'application/zip' } as File
}

function track<T extends { unmount(): void }>(mounted: T): T {
  const unmount = () => {
    if (!cleanups.delete(unmount)) return
    mounted.unmount()
  }
  cleanups.add(unmount)
  return { ...mounted, unmount }
}

function mountPanel() {
  return track(mountComponent<PanelState>(DataImportPanel))
}

function mountPreview(props: Record<string, unknown> = {}) {
  return track(mountComponent<PreviewState>(ImportPreviewDialog, {
    modelValue: true, preview: previewResult(), submitting: false, confirmable: true, ...props,
  }))
}

async function settle() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}

async function prepare(panel: ReturnType<typeof mountPanel>, name?: string) {
  panel.state.setFile(file(name))
  await panel.state.runPreview()
  await nextTick()
  expect(panel.state.state).toBe('READY')
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2030-01-01T00:00:00Z'))
  const values = new Map<string, string>()
  storage = {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => { values.set(key, value) },
    removeItem: (key) => { values.delete(key) },
  }
  vi.stubGlobal('window', {
    sessionStorage: storage,
    setTimeout: globalThis.setTimeout, clearTimeout: globalThis.clearTimeout,
    setInterval: globalThis.setInterval, clearInterval: globalThis.clearInterval,
  })
  currentJobApi.mockRejectedValue({ status: 404 })
  tokenJobApi.mockRejectedValue({ status: 404 })
  jobApi.mockImplementation(() => new Promise<DataImportJob>(() => {}))
  previewApi.mockImplementation(async () => previewResult())
  confirmApi.mockResolvedValue({ jobId: 'job_B' })
})

afterEach(() => {
  for (const unmount of [...cleanups].reverse()) unmount()
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('import preview expiry and submission ownership', () => {
  it('ignores upload progress from a previous preview while a newer preview is pending', async () => {
    const panel = mountPanel()
    await settle()
    await prepare(panel)
    const oldProgress = previewApi.mock.calls[0][1]!
    const pending = deferred<DataImportPreview>()
    previewApi.mockReturnValueOnce(pending.promise)
    const request = panel.state.runPreview()
    const currentProgress = previewApi.mock.calls[1][1]!

    currentProgress({ loaded: 256, total: 1_024 } as AxiosProgressEvent)
    oldProgress({ loaded: 1_024, total: 1_024 } as AxiosProgressEvent)
    expect(panel.state.state).toBe('UPLOADING')
    expect(panel.state.uploadPercent).toBe(25)
    pending.resolve({ ...previewResult(), previewToken: 'preview_B' })
    await request
    oldProgress({ loaded: 1_024, total: 1_024 } as AxiosProgressEvent)
    expect(panel.state.state).toBe('READY')
    expect(panel.state.preview?.previewToken).toBe('preview_B')
  })

  it('keeps confirmation locked when the real preview clock expires, then observes the accepted job', async () => {
    const pending = deferred<{ jobId: string }>()
    confirmApi.mockReturnValueOnce(pending.promise)
    const panel = mountPanel()
    await settle()
    await prepare(panel)
    const confirmation = panel.state.confirmImport()
    const expired = vi.fn(() => panel.state.handlePreviewExpired())
    const dialog = mountPreview({ preview: panel.state.preview, submitting: true, confirmable: false, onExpired: expired })

    await vi.advanceTimersByTimeAsync(6_000)
    expect(dialog.state.previewExpired).toBe(true)
    expect(expired).not.toHaveBeenCalled()
    // 即使一个已排队的过期事件抵达父组件，也不能解除提交互斥。
    panel.state.handlePreviewExpired()
    panel.state.setFile(file('replacement.zip'))
    await panel.state.confirmImport()
    expect(panel.state.state).toBe('CONFIRMING')
    expect(panel.state.busy).toBe(true)
    expect(panel.state.canPreview).toBe(false)
    expect(panel.state.selectedFile?.name).toBe('first.zip')
    expect(confirmApi).toHaveBeenCalledTimes(1)

    pending.resolve({ jobId: 'job_A' })
    await confirmation
    expect(panel.state.state).toBe('RUNNING')
    expect(panel.state.job?.jobId).toBe('job_A')
    expect(readImportJobSession(storage)?.jobId).toBe('job_A')
    expect(jobApi).toHaveBeenCalledWith('job_A')
  })

  it('does not unlock or resubmit a running job when its old preview expires', async () => {
    const panel = mountPanel()
    await settle()
    await prepare(panel)
    await panel.state.confirmImport()
    const expired = vi.fn(() => panel.state.handlePreviewExpired())
    const dialog = mountPreview({ preview: panel.state.preview, confirmable: false, onExpired: expired })
    dialog.state.backupConfirmed = true
    dialog.state.confirmationText = DATA_IMPORT_CONFIRMATION_TEXT
    expect(dialog.state.confirmEnabled).toBe(false)

    await vi.advanceTimersByTimeAsync(6_000)
    expect(expired).toHaveBeenCalledOnce()
    await panel.state.confirmImport()
    panel.state.setFile(file('replacement.zip'))
    expect(panel.state.state).toBe('RUNNING')
    expect(panel.state.busy).toBe(true)
    expect(panel.state.selectedFile?.name).toBe('first.zip')
    expect(confirmApi).toHaveBeenCalledOnce()
    expect(readImportJobSession(storage)?.jobId).toBe('job_B')
  })

  it.each([false, true])('releases a rejected confirmation with the correct expiry state (expired=%s)', async (expired) => {
    const pending = deferred<{ jobId: string }>()
    confirmApi.mockReturnValueOnce(pending.promise)
    const panel = mountPanel()
    await settle()
    await prepare(panel)
    const confirmation = panel.state.confirmImport()
    if (expired) await vi.advanceTimersByTimeAsync(6_000)
    panel.state.handlePreviewExpired()
    expect(panel.state.state).toBe('CONFIRMING')
    pending.reject({ status: 400 })
    await confirmation
    expect(panel.state.state).toBe(expired ? 'BLOCKED' : 'READY')
    expect(panel.state.busy).toBe(false)
    expect(panel.state.previewRequestError).toContain('ZIP 备份')
    expect(panel.state.job).toBeNull()
    expect(readImportJobSession(storage)).toBeNull()
  })
})

describe('unmounted import requests', () => {
  it.each(['resolve', 'reject'] as const)('ignores late preview progress and %s after a new panel starts a job', async (outcome) => {
    const pending = deferred<DataImportPreview>()
    previewApi.mockReturnValueOnce(pending.promise)
    const old = mountPanel()
    await settle()
    old.state.setFile(file())
    const request = old.state.runPreview()
    const progress = previewApi.mock.calls[0][1]!
    old.unmount()

    const current = mountPanel()
    await settle()
    await prepare(current, 'new.zip')
    await current.state.confirmImport()
    const saved = storage.getItem(DATA_IMPORT_JOB_SESSION_KEY)
    progress({ loaded: 1_024, total: 1_024 } as AxiosProgressEvent)
    if (outcome === 'resolve') pending.resolve(previewResult())
    else pending.reject({ status: 413 })
    await request
    expect(old.state.state).toBe('UPLOADING')
    expect(old.state.preview).toBeNull()
    expect(old.state.uploadPercent).toBeNull()
    expect(old.state.persistentError).toBe('')
    expect(current.state.state).toBe('RUNNING')
    expect(current.state.job?.jobId).toBe('job_B')
    expect(storage.getItem(DATA_IMPORT_JOB_SESSION_KEY)).toBe(saved)
  })

  it.each(['resolve', 'reject'] as const)('ignores late confirmation %s without overwriting a new recovered job', async (outcome) => {
    const pending = deferred<{ jobId: string }>()
    confirmApi.mockReturnValueOnce(pending.promise)
    const old = mountPanel()
    await settle()
    await prepare(old)
    const request = old.state.confirmImport()
    old.unmount()

    tokenJobApi.mockResolvedValueOnce(jobResult('job_B'))
    const current = mountPanel()
    await settle()
    const saved = storage.getItem(DATA_IMPORT_JOB_SESSION_KEY)
    if (outcome === 'resolve') pending.resolve({ jobId: 'job_A' })
    else pending.reject({ status: 410 })
    await request
    expect(old.state.state).toBe('CONFIRMING')
    expect(old.state.previewRequestError).toBe('')
    expect(old.state.job).toBeNull()
    expect(current.state.job?.jobId).toBe('job_B')
    expect(current.state.state).toBe('RUNNING')
    expect(storage.getItem(DATA_IMPORT_JOB_SESSION_KEY)).toBe(saved)
    expect(jobApi).toHaveBeenCalledTimes(1)
    expect(jobApi).toHaveBeenCalledWith('job_B')
  })

  it.each(['COMPLETED', 404, 410, 503] as const)('ignores old poll outcome %s after remount, completion and a new import', async (outcome) => {
    const pending = deferred<DataImportJob>()
    writeImportJobSession(storage, { jobId: 'job_A', startedAt: new Date().toISOString() })
    jobApi.mockResolvedValueOnce(jobResult())
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce(jobResult('job_A', 'COMPLETED'))
    const old = mountPanel()
    await settle()
    expect(old.state.state).toBe('RUNNING')
    old.unmount()

    const current = mountPanel()
    await settle()
    expect(current.state.state).toBe('COMPLETED')
    current.state.progressVisible = false
    await nextTick()
    await prepare(current, 'new.zip')
    await current.state.confirmImport()
    const saved = storage.getItem(DATA_IMPORT_JOB_SESSION_KEY)
    if (outcome === 'COMPLETED') pending.resolve(jobResult('job_A', 'COMPLETED'))
    else pending.reject({ status: outcome })
    await settle()
    expect(old.state.state).toBe('RUNNING')
    expect(old.state.job?.stage).toBe('WRITING')
    expect(old.state.jobStatusError).toBe('')
    expect(current.state.state).toBe('RUNNING')
    expect(current.state.job?.jobId).toBe('job_B')
    expect(storage.getItem(DATA_IMPORT_JOB_SESSION_KEY)).toBe(saved)
    expect(jobApi).toHaveBeenCalledTimes(4)
    expect(vi.getTimerCount()).toBe(0)
  })
})

describe('import job recovery ownership', () => {
  it.each(['COMPLETED', 404] as const)('does not clear a different stored job after stored-job recovery returns %s', async (outcome) => {
    const pending = deferred<DataImportJob>()
    writeImportJobSession(storage, { jobId: 'job_A', startedAt: new Date().toISOString() })
    jobApi.mockReturnValueOnce(pending.promise)
    const panel = mountPanel()
    const newer = { jobId: 'job_B', startedAt: new Date().toISOString() }
    writeImportJobSession(storage, newer)
    if (outcome === 'COMPLETED') pending.resolve(jobResult('job_A', 'COMPLETED'))
    else pending.reject({ status: outcome })
    await settle()
    expect(readImportJobSession(storage)).toEqual(newer)
    expect(panel.state.state).toBe(outcome === 'COMPLETED' ? 'COMPLETED' : 'IDLE')
  })

  it('falls back from a missing stored job to the current server job', async () => {
    writeImportJobSession(storage, { jobId: 'job_A', startedAt: new Date().toISOString() })
    jobApi.mockRejectedValueOnce({ status: 404 })
    currentJobApi.mockResolvedValueOnce(jobResult('job_B'))
    const panel = mountPanel()
    await settle()
    expect(currentJobApi).toHaveBeenCalledOnce()
    expect(panel.state.state).toBe('RUNNING')
    expect(panel.state.job?.jobId).toBe('job_B')
    expect(readImportJobSession(storage)?.jobId).toBe('job_B')
  })

  it('still observes the server job when sessionStorage reads and writes are blocked', async () => {
    Object.defineProperty(window, 'sessionStorage', { get: () => { throw new Error('storage denied') } })
    currentJobApi.mockResolvedValueOnce(jobResult('job_B'))
    const panel = mountPanel()
    await settle()
    expect(panel.state.persistentError).toContain('浏览器无法读取')
    expect(panel.state.jobStatusError).toContain('浏览器阻止了任务状态保存')
    expect(panel.state.state).toBe('RUNNING')
    expect(panel.state.job?.jobId).toBe('job_B')
    expect(jobApi).toHaveBeenCalledWith('job_B')
  })

  it('ignores delayed current-job discovery after the user starts a preview', async () => {
    const pending = deferred<DataImportJob>()
    currentJobApi.mockReturnValueOnce(pending.promise)
    const panel = mountPanel()
    await prepare(panel)
    pending.resolve(jobResult())
    await settle()
    expect(panel.state.state).toBe('READY')
    expect(panel.state.job).toBeNull()
    expect(readImportJobSession(storage)).toBeNull()
  })
})

describe('preview dialog lifecycle', () => {
  it('defers expiry notification and rejects closing until pending confirmation finishes', async () => {
    const expired = vi.fn()
    const visible = vi.fn()
    const dialog = mountPreview({ submitting: true, onExpired: expired, 'onUpdate:modelValue': visible })
    await vi.advanceTimersByTimeAsync(6_000)
    dialog.state.updateVisible(false)
    expect(expired).not.toHaveBeenCalled()
    expect(visible).not.toHaveBeenCalled()

    // 更新真实响应式 props，触发 Vue watch，等价于父组件提交结束的更新。
    Object.assign(dialog.vm.$.props, { submitting: false })
    await nextTick()
    expect(expired).toHaveBeenCalledOnce()
    dialog.state.updateVisible(false)
    expect(visible).toHaveBeenCalledWith(false)
  })

  it('stops its real expiry timer and emits nothing after unmount', async () => {
    const expired = vi.fn()
    const dialog = mountPreview({ onExpired: expired })
    expect(vi.getTimerCount()).toBe(1)
    dialog.unmount()
    await vi.advanceTimersByTimeAsync(6_000)
    expect(vi.getTimerCount()).toBe(0)
    expect(expired).not.toHaveBeenCalled()
  })
})

describe('confirmation outcome recovery', () => {
  it('keeps a known job recoverable when its index disappears and resumes polling after token lookup', async () => {
    const panel = mountPanel()
    await settle()
    await prepare(panel)
    jobApi.mockRejectedValueOnce({ status: 404 })
    await panel.state.confirmImport()
    await settle()
    expect(panel.state.state).toBe('RECOVERING')
    expect(panel.state.busy).toBe(true)
    expect(readImportConfirmationSession(storage)?.previewToken).toBe('preview_A')

    tokenJobApi.mockResolvedValueOnce(jobResult('job_B'))
    jobApi.mockResolvedValueOnce(jobResult('job_B', 'COMPLETED'))
    await vi.advanceTimersByTimeAsync(2_000)
    expect(tokenJobApi).toHaveBeenCalledWith('preview_A')
    expect(jobApi).toHaveBeenCalledTimes(2)
    expect(panel.state.state).toBe('COMPLETED')
    expect(confirmApi).toHaveBeenCalledOnce()
    expect(readImportConfirmationSession(storage)?.previewToken).toBe('preview_A')
  })

  it.each([undefined, 502, 503])('persists the confirmed token before POST and discovers a completed job after %s', async (status) => {
    const panel = mountPanel()
    await settle()
    await prepare(panel)
    confirmApi.mockImplementationOnce(async (token) => {
      expect(readImportConfirmationSession(storage)?.previewToken).toBe(token)
      throw { status }
    })
    tokenJobApi.mockResolvedValueOnce(jobResult('job_A', 'COMPLETED'))
    await panel.state.confirmImport()

    expect(confirmApi).toHaveBeenCalledOnce()
    expect(tokenJobApi).toHaveBeenCalledWith('preview_A')
    expect(panel.state.state).toBe('COMPLETED')
    expect(panel.state.job?.jobId).toBe('job_A')
    expect(panel.state.previewRequestError).toBe('')
    // 终态在用户关闭进度框前仍能跨刷新恢复。
    expect(readImportConfirmationSession(storage)?.previewToken).toBe('preview_A')
    panel.state.progressVisible = false
    await nextTick()
    expect(readImportConfirmationSession(storage)).toBeNull()
  })

  it('recovers a finished import after leaving before its confirmation response arrives', async () => {
    const pending = deferred<{ jobId: string }>()
    confirmApi.mockReturnValueOnce(pending.promise)
    const old = mountPanel()
    await settle()
    await prepare(old)
    const confirmation = old.state.confirmImport()
    old.unmount()
    tokenJobApi.mockResolvedValueOnce(jobResult('job_A', 'COMPLETED'))
    const current = mountPanel()
    await settle()

    expect(current.state.state).toBe('COMPLETED')
    expect(current.state.job?.jobId).toBe('job_A')
    const saved = readImportConfirmationSession(storage)
    pending.resolve({ jobId: 'job_A' })
    await confirmation
    expect(current.state.state).toBe('COMPLETED')
    expect(readImportConfirmationSession(storage)).toEqual(saved)
    expect(confirmApi).toHaveBeenCalledOnce()
    expect(jobApi).not.toHaveBeenCalled()
  })

  it('does not submit when durable confirmation recovery information cannot be saved', async () => {
    const panel = mountPanel()
    await settle()
    await prepare(panel)
    vi.spyOn(storage, 'setItem').mockImplementation(() => { throw new Error('quota') })
    await panel.state.confirmImport()
    expect(confirmApi).not.toHaveBeenCalled()
    expect(panel.state.state).toBe('READY')
    expect(panel.state.previewRequestError).toContain('尚未提交导入')
  })

  it('keeps an unknown result locked across expiry and retries only its read endpoint', async () => {
    const panel = mountPanel()
    await settle()
    await prepare(panel)
    confirmApi.mockRejectedValueOnce(new Error('timeout'))
    await panel.state.confirmImport()
    expect(panel.state.state).toBe('RECOVERING')
    expect(panel.state.confirmationNotFound).toBe(true)
    expect(panel.state.busy).toBe(true)
    const saved = readImportConfirmationSession(storage)
    panel.state.handlePreviewExpired()
    panel.state.setFile(file('replacement.zip'))
    await panel.state.confirmImport()
    expect(panel.state.selectedFile?.name).toBe('first.zip')
    expect(confirmApi).toHaveBeenCalledOnce()

    tokenJobApi.mockResolvedValueOnce(jobResult('job_A', 'FAILED'))
    await vi.advanceTimersByTimeAsync(6_000)
    expect(tokenJobApi).toHaveBeenCalledTimes(2)
    expect(panel.state.state).toBe('FAILED')
    expect(readImportConfirmationSession(storage)).toEqual(saved)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('ignores an unmounted result query without clearing a replacement confirmation record', async () => {
    writeImportConfirmationSession(storage, { previewToken: 'preview_A', startedAt: new Date().toISOString() })
    const pending = deferred<DataImportJob>()
    tokenJobApi.mockReturnValueOnce(pending.promise)
    const old = mountPanel()
    old.unmount()
    const newer = { previewToken: 'preview_B', startedAt: new Date().toISOString() }
    writeImportConfirmationSession(storage, newer)
    tokenJobApi.mockResolvedValueOnce(jobResult('job_B', 'COMPLETED'))
    const current = mountPanel()
    await settle()
    pending.resolve(jobResult('job_A', 'COMPLETED'))
    await settle()
    expect(current.state.job?.jobId).toBe('job_B')
    expect(old.state.state).toBe('RECOVERING')
    expect(readImportConfirmationSession(storage)).toEqual(newer)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('requires explicit acknowledgement before discarding an unknown outcome, and starts no import', async () => {
    writeImportConfirmationSession(storage, { previewToken: 'preview_A', startedAt: new Date().toISOString() })
    const panel = mountPanel()
    await settle()
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
    await panel.state.restartAfterUnknownResult()
    expect(panel.state.state).toBe('RECOVERING')
    expect(readImportConfirmationSession(storage)).not.toBeNull()
    vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm')
    await panel.state.restartAfterUnknownResult()
    expect(panel.state.state).toBe('IDLE')
    expect(panel.state.selectedFile).toBeNull()
    expect(readImportConfirmationSession(storage)).toBeNull()
    expect(confirmApi).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('does not discard a recovered result when an earlier reset acknowledgement arrives', async () => {
    writeImportConfirmationSession(storage, { previewToken: 'preview_A', startedAt: new Date().toISOString() })
    const panel = mountPanel()
    await settle()
    const acknowledgement = deferred<'confirm'>()
    vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(acknowledgement.promise)
    const reset = panel.state.restartAfterUnknownResult()
    tokenJobApi.mockResolvedValueOnce(jobResult('job_A', 'COMPLETED'))
    await panel.state.recoverConfirmation()
    acknowledgement.resolve('confirm')
    await reset
    expect(panel.state.state).toBe('COMPLETED')
    expect(readImportConfirmationSession(storage)?.previewToken).toBe('preview_A')
  })

  it('ignores a late query after acknowledged reset and a new preview', async () => {
    writeImportConfirmationSession(storage, { previewToken: 'preview_A', startedAt: new Date().toISOString() })
    const panel = mountPanel()
    await settle()
    const pending = deferred<DataImportJob>()
    tokenJobApi.mockReturnValueOnce(pending.promise)
    const query = panel.state.recoverConfirmation()
    vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm')
    await panel.state.restartAfterUnknownResult()
    await prepare(panel, 'new.zip')
    pending.resolve(jobResult('job_A', 'COMPLETED'))
    await query
    expect(panel.state.state).toBe('READY')
    expect(panel.state.selectedFile?.name).toBe('new.zip')
    expect(panel.state.job).toBeNull()
    expect(readImportConfirmationSession(storage)).toBeNull()
  })
})
