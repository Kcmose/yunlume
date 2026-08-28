<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument, Document, Download, Refresh, WarningFilled } from '@element-plus/icons-vue'
import { exportBookmarksAsMarkdown } from '@/api/data.api'
import type { MarkdownBookmarkBackup } from '@/types/dataTransfer'
import { copyTextWithFallback } from '@/utils/clipboard'
import { describeDataTransferError, formatBytes } from '@/utils/dataTransfer'

const generating = ref(false)
const backup = ref<MarkdownBookmarkBackup | null>(null)
const errorMessage = ref('')
const previewElement = ref<HTMLElement | null>(null)
const generatedSummary = computed(() => {
  if (!backup.value) return ''
  return `${backup.value.filename} · ${formatBytes(backup.value.blob.size)}`
})

function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.style.display = 'none'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
}

async function generateBackup() {
  if (generating.value) return
  generating.value = true
  errorMessage.value = ''
  try {
    backup.value = await exportBookmarksAsMarkdown()
  } catch (error) {
    errorMessage.value = describeDataTransferError(error, 'markdown')
  } finally {
    generating.value = false
  }
}

async function copyBackup() {
  if (!backup.value) return
  errorMessage.value = ''
  const result = await copyTextWithFallback(
    backup.value.text,
    navigator.clipboard,
    copyWithLegacySelection,
  )
  if (result !== 'failed') {
    ElMessage.success('Markdown 内容已复制')
    return
  }
  errorMessage.value = '自动复制失败，已选中下方预览内容，请按 Ctrl+C；手机端可长按后选择“复制”'
  await nextTick()
  selectPreviewText()
}

function copyWithLegacySelection(text: string): boolean {
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.readOnly = true
  textarea.setAttribute('aria-label', 'Markdown 书签备份复制缓冲区')
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

function selectPreviewText() {
  const element = previewElement.value
  const selection = window.getSelection()
  if (!element || !selection) return
  const range = document.createRange()
  range.selectNodeContents(element)
  selection.removeAllRanges()
  selection.addRange(range)
  element.focus()
}

function downloadBackup() {
  if (!backup.value) return
  saveBlob(backup.value.blob, backup.value.filename)
}
</script>

<template>
  <section
    class="admin-panel data-transfer-card markdown-backup-card"
    aria-labelledby="markdown-backup-title"
  >
    <header class="data-transfer-card__header">
      <span aria-hidden="true"><Document /></span>
      <div>
        <p>MARKDOWN BACKUP</p>
        <h2 id="markdown-backup-title">书签 Markdown 备份</h2>
        <small>按分类生成通用纯文本副本，适合阅读、复制到笔记软件或保存到代码仓库。</small>
      </div>
    </header>

    <div class="data-transfer-card__body">
      <div class="markdown-backup-intro">
        <div>
          <strong>备份内容</strong>
          <p>导出当前全部分类与书签，包括隐藏项目、链接、描述、显示状态和排序信息。</p>
        </div>
        <div>
          <strong>使用方法</strong>
          <p>生成后可预览、复制或下载 UTF-8 <code>.md</code> 文件；系统完整恢复仍请使用上方 ZIP 备份。</p>
        </div>
      </div>

      <div class="markdown-backup-privacy-note">
        <WarningFilled aria-hidden="true" />
        <p><strong>请妥善保管：</strong>文件包含隐藏项目及完整 URL，链接中可能带有私有查询参数，请勿提交到公开仓库。</p>
      </div>

      <p v-if="errorMessage" class="data-transfer-error" role="alert">
        <WarningFilled aria-hidden="true" />
        <span>{{ errorMessage }}</span>
      </p>

      <div class="data-transfer-card__actions markdown-backup-actions">
        <p>{{ backup ? '当前预览来自上次成功生成；重新生成会读取最新书签。' : '此操作只读取数据，不会修改分类或书签。' }}</p>
        <el-button
          type="primary"
          :icon="backup ? Refresh : Document"
          :loading="generating"
          @click="generateBackup"
        >
          {{ generating ? '正在生成' : backup ? '重新生成' : '生成 Markdown 备份' }}
        </el-button>
      </div>

      <section v-if="backup" class="markdown-backup-preview" aria-labelledby="markdown-preview-title">
        <header>
          <div>
            <strong id="markdown-preview-title">内容预览</strong>
            <span role="status" aria-live="polite">已生成 {{ generatedSummary }}</span>
          </div>
          <div>
            <el-button :icon="CopyDocument" @click="copyBackup">复制内容</el-button>
            <el-button type="primary" :icon="Download" @click="downloadBackup">下载 .md</el-button>
          </div>
        </header>
        <pre ref="previewElement" tabindex="0" aria-label="Markdown 书签备份内容">{{ backup.text }}</pre>
      </section>
    </div>
  </section>
</template>
