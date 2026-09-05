<script setup lang="ts">
import { onBeforeUnmount, ref, useId, watch } from 'vue'
import { ElMessage, type UploadProps, type UploadRequestOptions } from 'element-plus'
import { Delete, Picture, UploadFilled } from '@element-plus/icons-vue'
import { uploadImage } from '@/api/upload.api'

const props = defineProps<{
  modelValue: string
  label: string
  hint: string
  recommendedSize: string
  previewMode?: 'desktop' | 'mobile'
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'uploading-change': [uploading: boolean]
}>()

const uploading = ref(false)
const uploadProgress = ref<number | undefined>(undefined)
const previewFailed = ref(false)
let disposed = false
let uploadRequestVersion = 0
let imageEditVersion = 0
const urlInputId = `background-image-url-${useId()}`
const ABSOLUTE_MAX_FILE_BYTES = 10 * 1024 * 1024
const configuredMaxFileBytes = Number(import.meta.env.VITE_UPLOAD_MAX_BYTES)
const maxFileBytes = Number.isInteger(configuredMaxFileBytes)
  && configuredMaxFileBytes > 0
  && configuredMaxFileBytes <= ABSOLUTE_MAX_FILE_BYTES
  ? configuredMaxFileBytes
  : ABSOLUTE_MAX_FILE_BYTES

function readableBytes(bytes: number) {
  if (bytes % (1024 * 1024) === 0) return `${bytes / (1024 * 1024)}MB`
  if (bytes % 1024 === 0) return `${bytes / 1024}KB`
  return `${bytes} 字节`
}

watch(
  () => props.modelValue,
  () => {
    previewFailed.value = false
    imageEditVersion += 1
  },
  { flush: 'sync' },
)

function updateImage(value: string) {
  imageEditVersion += 1
  emit('update:modelValue', value)
}

function finishUpload() {
  if (!uploading.value) return
  uploading.value = false
  uploadProgress.value = undefined
  emit('uploading-change', false)
}

onBeforeUnmount(() => {
  disposed = true
  uploadRequestVersion += 1
  // 此时事件仍能送达父组件；异步 finally 不再重复释放上传计数。
  finishUpload()
})

const beforeUpload: UploadProps['beforeUpload'] = (file) => {
  if (!['image/jpeg', 'image/png'].includes(file.type)) {
    ElMessage.error('仅支持 JPG、JPEG 和 PNG 图片')
    return false
  }
  if (file.size > maxFileBytes) {
    ElMessage.error(`图片文件不能超过 ${readableBytes(maxFileBytes)}`)
    return false
  }
  return true
}

async function handleUpload(options: UploadRequestOptions) {
  if (disposed || props.disabled || uploading.value) return
  const requestVersion = ++uploadRequestVersion
  const editVersion = imageEditVersion
  const isCurrentUpload = () => !disposed && requestVersion === uploadRequestVersion
  uploading.value = true
  uploadProgress.value = undefined
  emit('uploading-change', true)
  try {
    const result = await uploadImage(options.file, (progress) => {
      if (isCurrentUpload()) uploadProgress.value = progress
    })
    if (isCurrentUpload() && editVersion === imageEditVersion) {
      emit('update:modelValue', result.url)
      ElMessage.success(`图片上传成功（${result.width} × ${result.height}），请点击“保存并应用背景”`)
    }
    return result
  } catch (error) {
    if (isCurrentUpload()) ElMessage.error(error instanceof Error ? error.message : '图片上传失败')
    throw error
  } finally {
    if (isCurrentUpload()) finishUpload()
  }
}
</script>

<template>
  <section class="background-image-field">
    <header>
      <div>
        <strong>{{ label }}</strong>
        <small>{{ hint }} · 建议 {{ recommendedSize }}</small>
      </div>
    </header>

    <div
      class="background-image-field__preview"
      :class="`background-image-field__preview--${previewMode ?? 'desktop'}`"
    >
      <img
        v-if="modelValue && !previewFailed"
        :src="modelValue"
        :alt="`${label}预览`"
        referrerpolicy="no-referrer"
        @error="previewFailed = true"
      />
      <div v-else class="background-image-field__empty">
        <Picture aria-hidden="true" />
        <span>{{ modelValue ? '图片地址暂时无法预览' : '暂未设置图片' }}</span>
      </div>
    </div>

    <div class="background-image-field__actions">
      <el-upload
        action="#"
        accept=".jpg,.jpeg,.png,image/jpeg,image/png"
        :show-file-list="false"
        :disabled="disabled || uploading"
        :before-upload="beforeUpload"
        :http-request="handleUpload"
      >
        <el-button :loading="uploading" :disabled="disabled">
          <UploadFilled /> {{ modelValue ? '重新上传' : '上传图片' }}
        </el-button>
      </el-upload>
      <el-button v-if="modelValue" :icon="Delete" :disabled="disabled" @click="updateImage('')">
        清空
      </el-button>
    </div>

    <el-progress
      v-if="uploading"
      :percentage="uploadProgress ?? 0"
      :indeterminate="uploadProgress === undefined"
      :show-text="uploadProgress !== undefined"
      :stroke-width="8"
      aria-label="图片上传进度"
    />

    <label class="sr-only" :for="urlInputId">{{ label }}图片地址</label>
    <el-input
      :id="urlInputId"
      :model-value="modelValue"
      clearable
      :disabled="disabled"
      placeholder="也可填写 https://... 或 /uploads/..."
      @update:model-value="updateImage"
      @clear="updateImage('')"
    />
  </section>
</template>
