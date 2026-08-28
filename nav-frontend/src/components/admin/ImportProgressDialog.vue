<script setup lang="ts">
import { computed } from 'vue'
import { CircleCheck, Loading, WarningFilled } from '@element-plus/icons-vue'
import type { DataImportJob, DataImportJobStage } from '@/types/dataTransfer'
import { isImportJobTerminal } from '@/utils/dataTransfer'

const props = defineProps<{
  modelValue: boolean
  job: DataImportJob | null
  statusError?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  retry: []
}>()

const terminal = computed(() => props.job ? isImportJobTerminal(props.job.stage) : false)
const failed = computed(() => props.job?.stage === 'FAILED')
const completed = computed(() => props.job?.stage === 'COMPLETED')
const outcomeUnknown = computed(() => failed.value && props.job?.error?.code === 'JOB_NOT_FOUND')
const stageStep: Record<DataImportJobStage, number> = {
  PREPARING: 0,
  WRITING: 1,
  VERIFYING: 2,
  COMPLETED: 3,
  FAILED: 3,
}
const activeStep = computed(() => stageStep[props.job?.stage ?? 'PREPARING'])

function updateVisible(visible: boolean) {
  if (!visible && !terminal.value) return
  emit('update:modelValue', visible)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="数据导入进度"
    width="min(650px, calc(100vw - 24px))"
    class="data-import-progress-dialog"
    :close-on-click-modal="terminal"
    :close-on-press-escape="terminal"
    :show-close="terminal"
    @update:model-value="updateVisible"
  >
    <div v-if="job" class="data-import-progress" role="status" aria-live="polite">
      <div class="data-import-progress__hero" :class="{ 'is-failed': failed, 'is-completed': completed }">
        <CircleCheck v-if="completed" />
        <WarningFilled v-else-if="failed" />
        <Loading v-else class="is-spinning" />
        <div>
          <strong v-if="completed">导入已完成</strong>
          <strong v-else-if="outcomeUnknown">无法确认导入结果</strong>
          <strong v-else-if="failed">导入失败，写入已回滚</strong>
          <strong v-else>导入任务正在运行</strong>
          <p>{{ job.message }}</p>
          <small>任务 ID：{{ job.jobId }}</small>
        </div>
      </div>

      <el-steps
        :active="activeStep"
        align-center
        finish-status="success"
        :process-status="failed ? 'error' : 'process'"
        aria-label="导入任务阶段"
      >
        <el-step title="准备" description="校验预检与锁定数据" />
        <el-step title="写入" description="事务内替换业务数据" />
        <el-step title="复核" description="验证引用与资源完整性" />
      </el-steps>

      <p v-if="!terminal" class="data-import-progress__notice">
        任务已在服务端执行，此阶段不提供虚假取消。可离开页面，再次进入时会恢复查询。
      </p>

      <p v-if="job.error" class="data-transfer-error" role="alert">
        <WarningFilled /><span><code>{{ job.error.code }}</code> {{ job.error.path ? `${job.error.path}：` : '' }}{{ job.error.message }}</span>
      </p>
      <p v-if="statusError" class="data-transfer-error is-retryable" role="alert">
        <WarningFilled /><span>{{ statusError }}，页面会自动重试查询。</span>
        <el-button size="small" @click="emit('retry')">立即重试</el-button>
      </p>
    </div>

    <template v-if="terminal" #footer>
      <el-button type="primary" @click="updateVisible(false)">关闭</el-button>
    </template>
  </el-dialog>
</template>
