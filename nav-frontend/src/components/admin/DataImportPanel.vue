<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { AxiosProgressEvent } from 'axios'
import { Document, UploadFilled, WarningFilled } from '@element-plus/icons-vue'
import {
  confirmNavigationDataImport,
  getCurrentNavigationDataImportJob,
  getNavigationDataImportJob,
  previewNavigationDataImport,
} from '@/api/data.api'
import ImportPreviewDialog from './ImportPreviewDialog.vue'
import ImportProgressDialog from './ImportProgressDialog.vue'
import type { DataImportClientState } from '@/utils/dataTransfer'
import type { DataImportJob, DataImportPreview } from '@/types/dataTransfer'
import {
  clearImportJobSession,
  clientStateForJob,
  DATA_IMPORT_MAX_BYTES,
  describeDataTransferError,
  formatBytes,
  isImportJobTerminal,
  previewState,
  readImportJobSession,
  validateImportFile,
  writeImportJobSession,
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

let pollTimer: number | undefined
let polling = false
let pollFailureCount = 0
let disposed = false
let restoreRequestVersion = 0

const busy = computed(() => ['UPLOADING', 'PREVIEWING', 'CONFIRMING', 'RUNNING'].includes(state.value))
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
  pollTimer = window.setTimeout(() => void pollJob(), delay)
}

function setFile(file: File | null) {
  if (busy.value) return
  restoreRequestVersion += 1
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

async function runPreview() {
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
  try {
    const result = await previewNavigationDataImport(file, handleUploadProgress)
    preview.value = result
    state.value = previewState(result)
    uploadPercent.value = 100
    previewVisible.value = true
  } catch (error) {
    state.value = 'IDLE'
    persistentError.value = describeDataTransferError(error, 'preview')
  }
}

function safeWriteJobSession(jobId: string, startedAt: string) {
  try {
    writeImportJobSession(window.sessionStorage, { jobId, startedAt })
  } catch {
    jobStatusError.value = '浏览器阻止了任务状态保存，刷新页面后可能无法自动恢复进度'
  }
}

function safeClearJobSession() {
  try {
    clearImportJobSession(window.sessionStorage)
  } catch {
    // 无法写入 sessionStorage 不影响当前页面的终态。
  }
}

async function confirmImport() {
  const currentPreview = preview.value
  const token = currentPreview?.previewToken
  if (!currentPreview || !token || state.value === 'CONFIRMING') return
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

  state.value = 'CONFIRMING'
  previewRequestError.value = ''
  try {
    const result = await confirmNavigationDataImport(token)
    const startedAt = new Date().toISOString()
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
    const status = getHttpStatus(error)
    const conflictMessage = error instanceof Error ? error.message : ''
    const retryableConcurrentConflict = status === 409 && /正在执行|稍后重试/.test(conflictMessage)
    if ([404, 409, 410].includes(status ?? 0) && !retryableConcurrentConflict && preview.value) {
      preview.value = { ...preview.value, previewToken: null, expiresAt: null }
      state.value = 'BLOCKED'
    } else {
      state.value = preview.value ? previewState(preview.value) : 'IDLE'
    }
    previewRequestError.value = describeDataTransferError(error, 'confirm')
  }
}

async function pollJob() {
  const jobId = job.value?.jobId
  if (!jobId || polling) return
  clearPollTimer()
  polling = true
  try {
    const result = await getNavigationDataImportJob(jobId)
    if (job.value?.jobId !== jobId) return
    if (result.jobId !== jobId) {
      throw Object.assign(new Error('服务端返回了不匹配的任务 ID'), { status: 502 })
    }
    job.value = result
    state.value = clientStateForJob(result)
    pollFailureCount = 0
    jobStatusError.value = ''
    if (isImportJobTerminal(result.stage)) {
      safeClearJobSession()
    } else {
      schedulePoll()
    }
  } catch (error) {
    if (job.value?.jobId !== jobId) return
    const status = getHttpStatus(error)
    if (status === 404 || status === 410) {
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
      safeClearJobSession()
      return
    }
    pollFailureCount += 1
    jobStatusError.value = describeDataTransferError(error, 'status')
    schedulePoll()
  } finally {
    polling = false
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
    safeClearJobSession()
  }
}

async function restoreJob() {
  const restoreRequestId = ++restoreRequestVersion
  let session: ReturnType<typeof readImportJobSession> = null
  try {
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
        safeClearJobSession()
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
      :request-error="previewRequestError"
      @confirm="confirmImport"
      @expired="state = 'BLOCKED'"
    />
    <ImportProgressDialog
      v-model="progressVisible"
      :job="job"
      :status-error="jobStatusError"
      @retry="retryJobStatus"
    />
  </section>
</template>
