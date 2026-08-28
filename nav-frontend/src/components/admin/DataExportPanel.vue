<script setup lang="ts">
import { ref } from 'vue'
import { CircleCheck, Download, WarningFilled } from '@element-plus/icons-vue'
import { exportNavigationData } from '@/api/data.api'
import { describeDataTransferError } from '@/utils/dataTransfer'

const exporting = ref(false)
const errorMessage = ref('')

const includedItems = [
  '站点配置与版本化业务数据',
  '分类、书签与搜索引擎',
  '兼容保留的自定义链接',
  '当前配置引用的 PC / 移动端背景图',
]

const excludedItems = [
  '管理员账号、密码与会话令牌',
  '数据库、Redis 与 JWT 密钥',
  '环境变量、运行日志与系统文件',
]

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

async function downloadExport() {
  if (exporting.value) return
  exporting.value = true
  errorMessage.value = ''
  try {
    const download = await exportNavigationData()
    saveBlob(download.blob, download.filename)
  } catch (error) {
    errorMessage.value = describeDataTransferError(error, 'export')
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <section class="admin-panel data-transfer-card data-export-card" aria-labelledby="data-export-title">
    <header class="data-transfer-card__header">
      <span aria-hidden="true"><Download /></span>
      <div>
        <p>EXPORT BACKUP</p>
        <h2 id="data-export-title">导出当前数据</h2>
        <small>生成可供本系统预检和恢复的版本化 ZIP 备份包。</small>
      </div>
    </header>

    <div class="data-transfer-card__body">
      <div class="data-export-scope">
        <div>
          <strong>备份包含</strong>
          <ul>
            <li v-for="item in includedItems" :key="item"><CircleCheck /> <span>{{ item }}</span></li>
          </ul>
        </div>
        <div>
          <strong>不会导出</strong>
          <ul>
            <li v-for="item in excludedItems" :key="item"><span aria-hidden="true">—</span><span>{{ item }}</span></li>
          </ul>
        </div>
      </div>

      <p v-if="errorMessage" class="data-transfer-error" role="alert">
        <WarningFilled aria-hidden="true" />
        <span>{{ errorMessage }}</span>
      </p>

      <div class="data-transfer-card__actions">
        <p>导出不会修改任何数据，请将下载文件保存在可靠位置。</p>
        <el-button type="primary" :icon="Download" :loading="exporting" @click="downloadExport">
          {{ exporting ? '正在打包' : '下载 ZIP 备份' }}
        </el-button>
      </div>
    </div>
  </section>
</template>

