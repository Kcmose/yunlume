<script setup lang="ts">
import PageHeading from '@/components/admin/PageHeading.vue'
import DataExportPanel from '@/components/admin/DataExportPanel.vue'
import DataImportPanel from '@/components/admin/DataImportPanel.vue'
import MarkdownBookmarkBackupPanel from '@/components/admin/MarkdownBookmarkBackupPanel.vue'
</script>

<template>
  <div class="admin-page data-manage-page">
    <PageHeading
      title="数据管理"
      description="导出版本化 ZIP、生成可读的 Markdown 书签副本，或在完整预检后恢复站点业务数据。"
      eyebrow="DATA PORTABILITY"
    />
    <div class="data-manage-grid">
      <DataExportPanel />
      <DataImportPanel />
      <MarkdownBookmarkBackupPanel />
    </div>
  </div>
</template>
