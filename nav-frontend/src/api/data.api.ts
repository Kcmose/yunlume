import type { AxiosProgressEvent } from 'axios'
import request, { unwrapApiData } from './request'
import type {
  DataExportDownload,
  DataImportConfirmResult,
  DataImportJob,
  DataImportPreview,
  MarkdownBookmarkBackup,
} from '@/types/dataTransfer'
import {
  extractDownloadFilename,
  extractMarkdownDownloadFilename,
  parseDataImportConfirmResult,
  parseDataImportJob,
  parseDataImportPreview,
} from '@/utils/dataTransfer'

const DATA_ARCHIVE_REQUEST_TIMEOUT = 120_000

export async function exportNavigationData(): Promise<DataExportDownload> {
  const response = await request.get<Blob>('/admin/data/export', {
    responseType: 'blob',
    timeout: DATA_ARCHIVE_REQUEST_TIMEOUT,
  })
  if (!(response.data instanceof Blob) || response.data.size === 0) {
    throw Object.assign(new Error('服务端返回的导出文件为空'), { status: 502 })
  }
  const disposition = response.headers['content-disposition']
  return {
    blob: response.data,
    filename: extractDownloadFilename(
      typeof disposition === 'string' ? disposition : undefined,
      'yunlume-backup.zip',
    ),
  }
}

export async function exportBookmarksAsMarkdown(): Promise<MarkdownBookmarkBackup> {
  const response = await request.get<Blob>('/admin/data/bookmarks/markdown', {
    responseType: 'blob',
    timeout: DATA_ARCHIVE_REQUEST_TIMEOUT,
  })
  if (!(response.data instanceof Blob) || response.data.size === 0) {
    throw Object.assign(new Error('服务端返回的 Markdown 文件为空'), { status: 502 })
  }
  const text = await response.data.text()
  if (!text.trim()) {
    throw Object.assign(new Error('服务端返回的 Markdown 内容为空'), { status: 502 })
  }
  const disposition = response.headers['content-disposition']
  return {
    blob: response.data,
    text,
    filename: extractMarkdownDownloadFilename(
      typeof disposition === 'string' ? disposition : undefined,
      'yunlume-bookmarks.md',
    ),
  }
}

export async function previewNavigationDataImport(
  file: File,
  onUploadProgress?: (event: AxiosProgressEvent) => void,
): Promise<DataImportPreview> {
  const data = new FormData()
  data.append('file', file)
  return parseDataImportPreview(unwrapApiData<unknown>(await request.post('/admin/data/import/preview', data, {
    timeout: DATA_ARCHIVE_REQUEST_TIMEOUT,
    onUploadProgress,
  })))
}

export async function confirmNavigationDataImport(
  previewToken: string,
): Promise<DataImportConfirmResult> {
  return parseDataImportConfirmResult(unwrapApiData<unknown>(await request.post(
    `/admin/data/import/${encodeURIComponent(previewToken)}/confirm`,
    undefined,
  )))
}

export async function getNavigationDataImportJob(jobId: string): Promise<DataImportJob> {
  return parseDataImportJob(unwrapApiData<unknown>(await request.get(
    `/admin/data/import/jobs/${encodeURIComponent(jobId)}`,
  )))
}

export async function getCurrentNavigationDataImportJob(): Promise<DataImportJob> {
  return parseDataImportJob(unwrapApiData<unknown>(await request.get(
    '/admin/data/import/jobs/current',
  )))
}

export async function getNavigationDataImportJobByPreviewToken(previewToken: string): Promise<DataImportJob> {
  return parseDataImportJob(unwrapApiData<unknown>(await request.get(
    `/admin/data/import/previews/${encodeURIComponent(previewToken)}/job`,
  )))
}
