export type DataImportJobStage =
  | 'PREPARING'
  | 'WRITING'
  | 'VERIFYING'
  | 'COMPLETED'
  | 'FAILED'

export type DataTransferResourceKey =
  | 'siteConfigs'
  | 'categories'
  | 'bookmarks'
  | 'searchEngines'
  | 'customLinks'
  | 'assets'

export interface DataTransferResourceCounts {
  siteConfigs: number
  categories: number
  bookmarks: number
  searchEngines: number
  customLinks: number
  assets: number
}

export interface DataImportDiffItem {
  added: number
  updated: number
  deleted: number
  unchanged: number
}

export type DataImportDiff = Record<DataTransferResourceKey | 'total', DataImportDiffItem>

export interface DataImportPackageInfo {
  formatVersion: number
  exportedAt: string | null
  generator: string | null
  archiveSha256: string
}

export interface DataImportIssue {
  code: string
  message: string
  path?: string | null
}

export interface DataImportPreview {
  previewToken: string | null
  expiresAt: string | null
  packageInfo: DataImportPackageInfo
  counts: {
    current: DataTransferResourceCounts
    imported: DataTransferResourceCounts
  }
  diff: DataImportDiff
  errors: DataImportIssue[]
  warnings: DataImportIssue[]
}

export interface DataImportConfirmResult {
  jobId: string
}

export interface DataImportJob {
  jobId: string
  stage: DataImportJobStage
  createdAt: string
  message: string
  error?: DataImportIssue | null
  startedAt?: string | null
  finishedAt?: string | null
}

export interface DataExportDownload {
  blob: Blob
  filename: string
}

export interface MarkdownBookmarkBackup extends DataExportDownload {
  text: string
}

export interface DataImportJobSession {
  jobId: string
  startedAt: string
}
