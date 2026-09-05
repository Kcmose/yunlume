<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { AxiosProgressEvent } from 'axios'
import { ElMessageBox } from 'element-plus'
import { Document, UploadFilled, WarningFilled } from '@element-plus/icons-vue'
import {
  confirmNavigationDataImport,
  getCurrentNavigationDataImportJob,
  getNavigationDataImportJob,
  getNavigationDataImportJobByPreviewToken,
  previewNavigationDataImport,
} from '@/api/data.api'
import ImportPreviewDialog from './ImportPreviewDialog.vue'
import ImportProgressDialog from './ImportProgressDialog.vue'
import type { DataImportClientState } from '@/utils/dataTransfer'
import type { DataImportConfirmationSession, DataImportJob, DataImportPreview } from '@/types/dataTransfer'
import {
  clearImportJobSession,
  clearImportConfirmationSession,
  clientStateForJob,
  DATA_IMPORT_MAX_BYTES,
  describeDataTransferError,
  formatBytes,
  isImportJobTerminal,
  previewState,
  readImportJobSession,
  readImportConfirmationSession,
  validateImportFile,
  writeImportJobSession,
  writeImportConfirmationSession,
} from '@/utils/dataTransfer'
import { getHttpStatus } from '@/utils/httpError'

const POLL_INTERVAL_MS = 2_000
const MAX_POLL_RETRY_MS = 10_000

const fileInput = ref<HTMLInputElement | null>(null)
const selectedFile = ref<File | null>(null)
const state = ref<DataImportClientState>('IDLE')
const uploadPercent = ref<number | null>(null)
const preview = ref<DataImportPreview | null>(null)
const previewVisible = ref(false)
const previewRequestError = ref('')
const persistentError = ref('')
const isDragging = ref(false)
const job = ref<DataImportJob | null>(null)
const progressVisible = ref(false)
const jobStatusError = ref('')
const pendingConfirmation = ref<DataImportConfirmationSession | null>(null)
const recoveringConfirmation = ref(false)
const confirmationNotFound = ref(false)
const confirmationRecoveryError = ref('')

let pollTimer: number | undefined
let polling = false
let pollFailureCount = 0
let disposed = false
let restoreRequestVersion = 0
let operationVersion = 0

const busy = computed(() => ['UPLOADING', 'PREVIEWING', 'CONFIRMING', 'RECOVERING', 'RUNNING'].includes(state.value))
const canPreview = computed(() => Boolean(selectedFile.value)
  && validateImportFile(selectedFile.value) === null
  && !busy.value)
const fileDescription = computed(() => selectedFile.value
  ? `${selectedFile.value.name} · ${formatBytes(selectedFile.value.size)}`
  : `仅支持本系统导出的 ZIP，最大 ${formatBytes(DATA_IMPORT_MAX_BYTES)}`)
const statusMessage = computed(() => {
  if (state.value === 'FAILED' && job.value?.error?.code === 'JOB_NOT_FOUND') {
    return '任务状态已丢失，无法确认导入结果，请检查当前数据后再操作'
  }
  return ({
    IDLE: selectedFile.value ? '文件已选择，尚未上传或修改数据' : '等待选择备份文件',
    UPLOADING: '正在上传 ZIP 备份',
    PREVIEWING: '上传完成，服务端正在预检，此时不会写入数据',
    READY: '预检通过，请仔细确认变更后再导入',
    BLOCKED: '预检发现硬错误或已过期，当前备份不能导入',
    CONFIRMING: '正在创建导入任务',
    RECOVERING: '确认结果尚不确定，正在查询服务端；请勿重复导入',
    RUNNING: '导入任务已在服务端执行',
    COMPLETED: '导入任务已完成',
    FAILED: '导入任务失败，服务端应已回滚写入',
  })[state.value]
})

function clearPollTimer() {
  if (pollTimer !== undefined) {
    window.clearTimeout(pollTimer)
    pollTimer = undefined
  }
}

function schedulePoll() {
  clearPollTimer()
  if (disposed) return
  const delay = Math.min(POLL_INTERVAL_MS * 2 ** Math.min(pollFailureCount, 3), MAX_POLL_RETRY_MS)
  pollTimer = window.setTimeout(() => {
    if (state.value === 'RECOVERING') void recoverConfirmation()
    else void pollJob()
  }, delay)
}

function setFile(file: File | null) {
  if (disposed || busy.value) return
  clearPendingConfirmation()
  restoreRequestVersion += 1
  operationVersion += 1
  clearPollTimer()
  polling = false
  selectedFile.value = file
  preview.value = null
  previewRequestError.value = ''
  persistentError.value = file ? (validateImportFile(file) ?? '') : ''
  state.value = 'IDLE'
  uploadPercent.value = null
  previewVisible.value = false
}

function openFilePicker() {
  if (!busy.value) fileInput.value?.click()
}

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  setFile(input.files?.[0] ?? null)
  input.value = ''
}

function handleDrop(event: DragEvent) {
  isDragging.value = false
  if (busy.value) return
  setFile(event.dataTransfer?.files?.[0] ?? null)
}

function clearFile() {
  setFile(null)
}

function handleUploadProgress(event: AxiosProgressEvent) {
  if (!event.total || event.total <= 0) {
    uploadPercent.value = null
    if (event.progress === 1) state.value = 'PREVIEWING'
    return
  }
  uploadPercent.value = Math.min(100, Math.round((event.loaded / event.total) * 100))
  if (event.loaded >= event.total) state.value = 'PREVIEWING'
}

function isCurrentOperation(version: number) {
  return !disposed && version === operationVersion
}

function handlePreviewExpired() {
  // 预检的有效期不能撤销已经提交的确认或服务端任务。
  if (!disposed && state.value === 'READY') state.value = 'BLOCKED'
}

async function runPreview() {
  if (disposed) return
  const file = selectedFile.value
  const fileError = validateImportFile(file)
  if (!file || fileError || busy.value) {
    persistentError.value = fileError ?? '当前不能执行预检'
    return
  }

  persistentError.value = ''
  previewRequestError.value = ''
  uploadPercent.value = null
  state.value = 'UPLOADING'
  const operationId = ++operationVersion
  try {
    const result = await previewNavigationDataImport(file, (event) => {
      if (!isCurrentOperation(operationId) || !['UPLOADING', 'PREVIEWING'].includes(state.value)) return
      handleUploadProgress(event)
    })
    if (!isCurrentOperation(operationId)) return
    preview.value = result
    state.value = previewState(result)
    uploadPercent.value = 100
    previewVisible.value = true
  } catch (error) {
    if (!isCurrentOperation(operationId)) return
    state.value = 'IDLE'
    persistentError.value = describeDataTransferError(error, 'preview')
  }
}

function safeWriteJobSession(jobId: string, startedAt: string) {
  if (disposed) return
  try {
    writeImportJobSession(window.sessionStorage, { jobId, startedAt })
  } catch {
    jobStatusError.value = '浏览器阻止了任务状态保存，刷新页面后可能无法自动恢复进度'
  }
}

function safeClearJobSession(jobId: string) {
  if (disposed) return
  try {
    clearImportJobSession(window.sessionStorage, jobId)
  } catch {
    // 无法写入 sessionStorage 不影响当前页面的终态。
  }
}

function clearPendingConfirmation() {
  const token = pendingConfirmation.value?.previewToken
  if (disposed || !token) return
  try { clearImportConfirmationSession(window.sessionStorage, token) } catch {
    // 仅清理自身记录；存储不可用不改变已确认的服务端结果。
  }
  pendingConfirmation.value = null
}

async function recoverConfirmation() {
  const confirmation = pendingConfirmation.value
  if (disposed || !confirmation || state.value !== 'RECOVERING' || recoveringConfirmation.value) return
  const operationId = operationVersion
  clearPollTimer()
  recoveringConfirmation.value = true
  try {
    const result = await getNavigationDataImportJobByPreviewToken(confirmation.previewToken)
    if (!isCurrentOperation(operationId) || pendingConfirmation.value?.previewToken !== confirmation.previewToken) return
    confirmationRecoveryError.value = ''
    confirmationNotFound.value = false
    previewVisible.value = false
    pollFailureCount = 0
    showRecoveredJob(result)
  } catch (error) {
    if (!isCurrentOperation(operationId) || pendingConfirmation.value?.previewToken !== confirmation.previewToken) return
    confirmationNotFound.value = [404, 410].includes(getHttpStatus(error) ?? 0)
    confirmationRecoveryError.value = confirmationNotFound.value
      ? '暂未查询到这次导入任务。确认请求可能仍在处理，也可能未被接收，请先核对当前数据。'
      : '暂时无法查询导入结果，将继续重试；刷新页面也会恢复查询。'
    pollFailureCount += 1
    schedulePoll()
  } finally {
    if (isCurrentOperation(operationId)) recoveringConfirmation.value = false
  }
}

async function restartAfterUnknownResult() {
  if (disposed || state.value !== 'RECOVERING' || !confirmationNotFound.value) return
  const operationId = operationVersion
  try {
    await ElMessageBox.confirm(
      '请先核对当前站点数据。本操作只结束结果查询，不会取消服务端任务；继续后必须重新预检并确认。',
      '确认已核对当前数据',
      { type: 'warning', confirmButtonText: '已核对，重新预检', cancelButtonText: '继续查询' },
    )
  } catch { return }
  if (!isCurrentOperation(operationId) || state.value !== 'RECOVERING') return
  clearPendingConfirmation()
  state.value = 'IDLE'
  recoveringConfirmation.value = false
  confirmationRecoveryError.value = ''
  confirmationNotFound.value = false
  setFile(null)
}

async function confirmImport() {
  const currentPreview = preview.value
  const token = currentPreview?.previewToken
  if (disposed || !currentPreview || !token || state.value !== 'READY') return
  const expiresAt = Date.parse(currentPreview.expiresAt ?? '')
  if (
    currentPreview.errors.length > 0
    || !currentPreview.expiresAt
    || !Number.isFinite(expiresAt)
    || expiresAt <= Date.now()
  ) {
    state.value = 'BLOCKED'
    previewRequestError.value = '预检结果存在硬错误或已过期，请重新上传并预检'
    return
  }

  const confirmation = { previewToken: token, startedAt: new Date().toISOString() }
  try {
    // 必须先持久化用户已经确认的令牌，响应丢失或离页后才有只读恢复入口。
    writeImportConfirmationSession(window.sessionStorage, confirmation)
  } catch {
    try { clearImportConfirmationSession(window.sessionStorage, token) } catch { /* 保留无法清理的恢复信息 */ }
    previewRequestError.value = '浏览器无法保存导入恢复信息，尚未提交导入。请允许本站使用会话存储后重试。'
    return
  }
  pendingConfirmation.value = confirmation
  state.value = 'CONFIRMING'
  previewRequestError.value = ''
  const operationId = ++operationVersion
  try {
    const result = await confirmNavigationDataImport(token)
    if (!isCurrentOperation(operationId)) return
    const startedAt = confirmation.startedAt
    job.value = {
      jobId: result.jobId,
      stage: 'PREPARING',
      createdAt: startedAt,
      startedAt: null,
      finishedAt: null,
      message: '导入任务已提交，正在等待服务端处理',
    }
    state.value = 'RUNNING'
    previewVisible.value = false
    progressVisible.value = true
    pollFailureCount = 0
    jobStatusError.value = ''
    safeWriteJobSession(result.jobId, startedAt)
    void pollJob()
  } catch (error) {
    if (!isCurrentOperation(operationId)) return
    const status = getHttpStatus(error)
    if (!status || status >= 500) {
      state.value = 'RECOVERING'
      previewVisible.value = false
      previewRequestError.value = ''
      confirmationRecoveryError.value = '未收到可靠的确认结果，正在查询服务端任务。'
      pollFailureCount = 0
      await recoverConfirmation()
      return
    }
    clearPendingConfirmation()
    const conflictMessage = error instanceof Error ? error.message : ''
    const retryableConcurrentConflict = status === 409 && /正在执行|稍后重试/.test(conflictMessage)
    if ([404, 409, 410].includes(status ?? 0) && !retryableConcurrentConflict && preview.value) {
      preview.value = { ...preview.value, previewToken: null, expiresAt: null }
      state.value = 'BLOCKED'
    } else {
      state.value = expiresAt <= Date.now() ? 'BLOCKED' : previewState(currentPreview)
    }
    previewRequestError.value = describeDataTransferError(error, 'confirm')
  }
}

async function pollJob() {
  const jobId = job.value?.jobId
  if (disposed || !jobId || polling) return
  const operationId = operationVersion
  clearPollTimer()
  polling = true
  try {
    const result = await getNavigationDataImportJob(jobId)
    if (!isCurrentOperation(operationId) || job.value?.jobId !== jobId) return
    if (result.jobId !== jobId) {
      throw Object.assign(new Error('服务端返回了不匹配的任务 ID'), { status: 502 })
    }
    job.value = result
    state.value = clientStateForJob(result)
    pollFailureCount = 0
    jobStatusError.value = ''
    if (isImportJobTerminal(result.stage)) {
      safeClearJobSession(jobId)
    } else {
      schedulePoll()
    }
  } catch (error) {
    if (!isCurrentOperation(operationId) || job.value?.jobId !== jobId) return
    const status = getHttpStatus(error)
    if (status === 404 || status === 410) {
      if (pendingConfirmation.value) {
        // 任务索引暂不可见不代表事务失败；保留确认令牌，待本次轮询释放后只读恢复。
        state.value = 'RECOVERING'
        progressVisible.value = false
        jobStatusError.value = ''
        confirmationRecoveryError.value = '任务进度暂不可用，正在按本次确认查询导入结果。'
        pollFailureCount = 0
        schedulePoll()
        return
      }
      const message = '无法恢复该导入任务，服务端已不保留它的状态'
      job.value = {
        ...job.value!,
        stage: 'FAILED',
        message,
        error: { code: 'JOB_NOT_FOUND', message },
        finishedAt: new Date().toISOString(),
      }
      state.value = 'FAILED'
      jobStatusError.value = ''
      safeClearJobSession(jobId)
      return
    }
    pollFailureCount += 1
    jobStatusError.value = describeDataTransferError(error, 'status')
    schedulePoll()
  } finally {
    if (isCurrentOperation(operationId)) polling = false
  }
}

function retryJobStatus() {
  if (polling) return
  pollFailureCount = 0
  clearPollTimer()
  void pollJob()
}

function showRecoveredJob(result: DataImportJob) {
  job.value = result
  state.value = clientStateForJob(result)
  progressVisible.value = true
  if (!isImportJobTerminal(result.stage)) {
    safeWriteJobSession(result.jobId, result.createdAt)
    void pollJob()
  } else {
    safeClearJobSession(result.jobId)
  }
}

async function restoreJob() {
  const restoreRequestId = ++restoreRequestVersion
  let session: ReturnType<typeof readImportJobSession> = null
  try {
    pendingConfirmation.value = readImportConfirmationSession(window.sessionStorage)
    if (pendingConfirmation.value) {
      state.value = 'RECOVERING'
      await recoverConfirmation()
      return
    }
    session = readImportJobSession(window.sessionStorage)
  } catch {
    persistentError.value = '浏览器无法读取上次保存的导入任务状态'
  }
  if (session) {
    try {
      const recovered = await getNavigationDataImportJob(session.jobId)
      if (disposed || restoreRequestId !== restoreRequestVersion || state.value !== 'IDLE') return
      showRecoveredJob(recovered)
      return
    } catch (error) {
      if (disposed || restoreRequestId !== restoreRequestVersion || state.value !== 'IDLE') return
      const status = getHttpStatus(error)
      if (status === 404 || status === 410) {
        safeClearJobSession(session.jobId)
      } else {
        showRecoveredJob({
          jobId: session.jobId,
          stage: 'PREPARING',
          createdAt: session.startedAt,
          startedAt: null,
          finishedAt: null,
          message: '正在恢复上次导入任务的进度',
        })
        return
      }
    }
  }
  try {
    const recovered = await getCurrentNavigationDataImportJob()
    if (disposed || restoreRequestId !== restoreRequestVersion || state.value !== 'IDLE') return
    showRecoveredJob(recovered)
  } catch (error) {
    if (disposed || restoreRequestId !== restoreRequestVersion || state.value !== 'IDLE') return
    if (getHttpStatus(error) !== 404) {
      persistentError.value = '暂时无法查询上次导入任务，请稍后刷新重试'
    }
  }
}

watch(progressVisible, (visible) => {
  if (visible || !job.value || !isImportJobTerminal(job.value.stage)) return
  clearPendingConfirmation()
  job.value = null
  preview.value = null
  previewRequestError.value = ''
  uploadPercent.value = null
  state.value = 'IDLE'
  jobStatusError.value = ''
})

onMounted(() => {
  disposed = false
  void restoreJob()
})
onBeforeUnmount(() => {
  disposed = true
  restoreRequestVersion += 1
  operationVersion += 1
  clearPollTimer()
})
</script>

<template>
  <section class="admin-panel data-transfer-card data-import-card" aria-labelledby="data-import-title">
    <header class="data-transfer-card__header">
      <span aria-hidden="true"><UploadFilled /></span>
      <div>
        <p>VALIDATE &amp; RESTORE</p>
        <h2 id="data-import-title">预检并导入备份</h2>
        <small>选择 ZIP 后先做零写入预检；只有明确确认后才创建导入任务。</small>
      </div>
    </header>

    <div class="data-transfer-card__body">
      <div
        class="data-import-dropzone"
        :class="{ 'is-dragging': isDragging, 'has-file': selectedFile }"
        @dragenter.prevent="isDragging = true"
        @dragover.prevent="isDragging = true"
        @dragleave.prevent="isDragging = false"
        @drop.prevent="handleDrop"
      >
        <input
          ref="fileInput"
          type="file"
          accept=".zip,application/zip,application/x-zip-compressed"
          :disabled="busy"
          @change="handleFileChange"
        />
        <span class="data-import-dropzone__icon" aria-hidden="true"><Document /></span>
        <div>
          <strong>{{ selectedFile ? selectedFile.name : '选择 ZIP 备份文件' }}</strong>
          <p>{{ fileDescription }}</p>
          <small>可拖放到此处；手机端请使用选择文件按钮。</small>
        </div>
        <el-button :disabled="busy" @click="openFilePicker">{{ selectedFile ? '重新选择' : '选择文件' }}</el-button>
      </div>

      <div class="data-import-status" role="status" aria-live="polite">
        <div>
          <span class="data-import-status__dot" :class="`is-${state.toLocaleLowerCase()}`" aria-hidden="true" />
          <span>{{ statusMessage }}</span>
        </div>
        <el-progress
          v-if="state === 'UPLOADING' || state === 'PREVIEWING'"
          :percentage="uploadPercent ?? 0"
          :indeterminate="uploadPercent === null"
          :duration="1.5"
          :show-text="uploadPercent !== null"
        />
      </div>

      <p v-if="persistentError" class="data-transfer-error" role="alert">
        <WarningFilled aria-hidden="true" /><span>{{ persistentError }}</span>
      </p>

      <div v-if="state === 'RECOVERING'" class="data-import-recovery" role="status" aria-live="polite">
        <p>{{ confirmationRecoveryError }}</p>
        <el-button :loading="recoveringConfirmation" @click="recoverConfirmation">重新查询结果</el-button>
        <el-button v-if="confirmationNotFound" @click="restartAfterUnknownResult">核对数据后重新预检</el-button>
      </div>

      <div class="data-import-safety-note">
        <WarningFilled aria-hidden="true" />
        <div><strong>导入可能删除或替换当前业务数据</strong><p>请先导出当前备份。预检不会写库，实际导入开始后不提供伪取消。</p></div>
      </div>

      <div class="data-transfer-card__actions data-import-actions">
        <el-button v-if="selectedFile" :disabled="busy" @click="clearFile">清除文件</el-button>
        <el-button v-if="preview" :disabled="state === 'CONFIRMING'" @click="previewVisible = true">查看预检结果</el-button>
        <el-button type="primary" :icon="UploadFilled" :loading="state === 'UPLOADING' || state === 'PREVIEWING'" :disabled="!canPreview" @click="runPreview">
          {{ state === 'UPLOADING' ? '正在上传' : state === 'PREVIEWING' ? '正在预检' : '上传并预检' }}
        </el-button>
      </div>
    </div>

    <ImportPreviewDialog
      v-model="previewVisible"
      :preview="preview"
      :submitting="state === 'CONFIRMING'"
      :confirmable="state === 'READY'"
      :request-error="previewRequestError"
      @confirm="confirmImport"
      @expired="handlePreviewExpired"
    />
    <ImportProgressDialog
      v-model="progressVisible"
      :job="job"
      :status-error="jobStatusError"
      @retry="retryJobStatus"
    />
  </section>
</template>
