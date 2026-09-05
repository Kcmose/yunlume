<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Delete, WarningFilled } from '@element-plus/icons-vue'
import type {
  DataImportIssue,
  DataImportPreview,
  DataTransferResourceKey,
} from '@/types/dataTransfer'
import {
  canConfirmImport,
  DATA_IMPORT_CONFIRMATION_TEXT,
  groupImportIssues,
} from '@/utils/dataTransfer'

const props = defineProps<{
  modelValue: boolean
  preview: DataImportPreview | null
  submitting: boolean
  confirmable: boolean
  requestError?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: []
  expired: []
}>()

const backupConfirmed = ref(false)
const confirmationText = ref('')
const currentTime = ref(Date.now())
let expiryTimer: number | undefined

const resources: Array<{ key: DataTransferResourceKey; label: string }> = [
  { key: 'siteConfigs', label: '站点配置' },
  { key: 'categories', label: '分类' },
  { key: 'bookmarks', label: '书签' },
  { key: 'searchEngines', label: '搜索引擎' },
  { key: 'customLinks', label: '自定义链接' },
  { key: 'assets', label: '引用背景图' },
]

const errorGroups = computed(() => groupImportIssues(props.preview?.errors ?? []))
const warningGroups = computed(() => groupImportIssues(props.preview?.warnings ?? []))
const confirmEnabled = computed(() => props.confirmable && canConfirmImport({
  preview: props.preview,
  backupConfirmed: backupConfirmed.value,
  confirmationText: confirmationText.value,
  submitting: props.submitting,
  now: currentTime.value,
}))
const hasDeletions = computed(() => props.preview
  ? resources.some(({ key }) => props.preview!.diff[key].deleted > 0)
  : false)
const invalidPreviewContract = computed(() => Boolean(
  props.preview
  && !props.preview.errors.length
  && (!props.preview.previewToken || !props.preview.expiresAt),
))
const previewExpired = computed(() => {
  const expiresAt = props.preview?.expiresAt
  if (!expiresAt) return false
  const timestamp = Date.parse(expiresAt)
  return Number.isFinite(timestamp) && timestamp <= currentTime.value
})
const previewUnavailable = computed(() => invalidPreviewContract.value || previewExpired.value)

function clearExpiryTimer() {
  if (expiryTimer !== undefined) {
    window.clearInterval(expiryTimer)
    expiryTimer = undefined
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    clearExpiryTimer()
    if (!visible) return
    currentTime.value = Date.now()
    expiryTimer = window.setInterval(() => {
      currentTime.value = Date.now()
    }, 1_000)
    backupConfirmed.value = false
    confirmationText.value = ''
  },
  { immediate: true },
)

watch(
  [previewExpired, () => props.submitting, () => props.modelValue],
  ([expired, submitting, visible]) => {
    if (expired && visible && !submitting) emit('expired')
  },
)

onBeforeUnmount(clearExpiryTimer)

function updateVisible(visible: boolean) {
  if (!visible && props.submitting) return
  emit('update:modelValue', visible)
}

function issueLabel(issue: DataImportIssue) {
  return issue.path ? `${issue.path}：${issue.message}` : issue.message
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="导入预检与确认"
    width="min(820px, calc(100vw - 24px))"
    class="data-import-preview-dialog"
    :close-on-click-modal="!submitting"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    destroy-on-close
    @update:model-value="updateVisible"
  >
    <div v-if="preview" class="data-import-preview">
      <section class="data-import-package" aria-labelledby="data-package-title">
        <div>
          <strong id="data-package-title">备份包信息</strong>
          <span>格式 {{ preview.packageInfo.formatVersion || '未知' }}</span>
          <span>生成器 {{ preview.packageInfo.generator || '未知' }}</span>
        </div>
        <div>
          <span>导出时间 {{ preview.packageInfo.exportedAt || '未知' }}</span>
          <span class="data-import-package__hash" :title="preview.packageInfo.archiveSha256">
            SHA-256 {{ preview.packageInfo.archiveSha256 }}
          </span>
          <span v-if="preview.expiresAt">预检有效至 {{ preview.expiresAt }}</span>
        </div>
      </section>

      <section aria-labelledby="data-diff-title">
        <div class="data-import-section-heading">
          <div><strong id="data-diff-title">数据变更</strong><small>以服务端预检结果为准</small></div>
          <span v-if="hasDeletions" class="data-import-delete-warning"><Delete /> 包含删除操作</span>
        </div>
        <dl class="data-import-diff-total" aria-label="全部资源变更合计">
          <div><dt>新增合计</dt><dd>{{ preview.diff.total.added }}</dd></div>
          <div><dt>更新合计</dt><dd>{{ preview.diff.total.updated }}</dd></div>
          <div :class="{ 'is-danger': preview.diff.total.deleted > 0 }">
            <dt>删除合计</dt><dd>{{ preview.diff.total.deleted }}</dd>
          </div>
          <div><dt>不变合计</dt><dd>{{ preview.diff.total.unchanged }}</dd></div>
        </dl>
        <div class="data-import-diff-grid">
          <article v-for="resource in resources" :key="resource.key">
            <header>
              <strong>{{ resource.label }}</strong>
              <span>{{ preview.counts.current[resource.key] }} → {{ preview.counts.imported[resource.key] }}</span>
            </header>
            <dl>
              <div><dt>新增</dt><dd>{{ preview.diff[resource.key].added }}</dd></div>
              <div><dt>更新</dt><dd>{{ preview.diff[resource.key].updated }}</dd></div>
              <div :class="{ 'is-danger': preview.diff[resource.key].deleted > 0 }">
                <dt>删除</dt><dd>{{ preview.diff[resource.key].deleted }}</dd>
              </div>
              <div><dt>不变</dt><dd>{{ preview.diff[resource.key].unchanged }}</dd></div>
            </dl>
          </article>
        </div>
      </section>

      <section v-if="errorGroups.length" class="data-import-issues is-error" role="alert" aria-labelledby="import-error-title">
        <header><WarningFilled /><div><strong id="import-error-title">预检未通过</strong><small>修正以下 {{ preview.errors.length }} 个错误后重新预检，当前不能导入。</small></div></header>
        <div v-for="group in errorGroups" :key="group.key" class="data-import-issue-group">
          <strong>{{ group.label }}</strong>
          <ul><li v-for="(issue, index) in group.issues" :key="`${issue.code}-${index}`"><code>{{ issue.code }}</code><span>{{ issueLabel(issue) }}</span></li></ul>
        </div>
      </section>

      <p v-if="invalidPreviewContract" class="data-transfer-error" role="alert">
        <WarningFilled /><span>服务端未返回可用的预检令牌或过期时间，为防止误写入，当前不允许确认导入。</span>
      </p>

      <p v-else-if="previewExpired && !submitting" class="data-transfer-error" role="alert">
        <WarningFilled /><span>预检结果已过期，请关闭窗口后重新上传并预检；当前不能导入。</span>
      </p>

      <section v-if="warningGroups.length" class="data-import-issues is-warning" aria-labelledby="import-warning-title">
        <header><WarningFilled /><div><strong id="import-warning-title">预检警告</strong><small>请确认以下 {{ preview.warnings.length }} 项影响。</small></div></header>
        <div v-for="group in warningGroups" :key="group.key" class="data-import-issue-group">
          <strong>{{ group.label }}</strong>
          <ul><li v-for="(issue, index) in group.issues" :key="`${issue.code}-${index}`"><code>{{ issue.code }}</code><span>{{ issueLabel(issue) }}</span></li></ul>
        </div>
      </section>

      <p v-if="requestError" class="data-transfer-error" role="alert"><WarningFilled /><span>{{ requestError }}</span></p>

      <section v-if="!preview.errors.length && !previewUnavailable" class="data-import-confirmation" aria-labelledby="import-confirm-title">
        <div><strong id="import-confirm-title">最终确认</strong><p>确认后将在服务端事务中完成导入；任一步失败应整体回滚。</p></div>
        <el-checkbox v-model="backupConfirmed">我已导出或确认无需保留当前数据</el-checkbox>
        <label class="data-import-confirmation__phrase" for="data-import-confirm-text">
          <span>输入“{{ DATA_IMPORT_CONFIRMATION_TEXT }}”以继续</span>
          <el-input
            id="data-import-confirm-text"
            v-model="confirmationText"
            :placeholder="DATA_IMPORT_CONFIRMATION_TEXT"
            autocomplete="off"
            :disabled="submitting"
          />
        </label>
      </section>
    </div>

    <template #footer>
      <el-button :disabled="submitting" @click="updateVisible(false)">{{ preview?.errors.length ? '关闭' : '取消' }}</el-button>
      <el-button
        v-if="preview && !preview.errors.length && (!previewUnavailable || submitting)"
        type="danger"
        :loading="submitting"
        :disabled="!confirmEnabled"
        @click="emit('confirm')"
      >
        确认导入
      </el-button>
    </template>
  </el-dialog>
</template>
