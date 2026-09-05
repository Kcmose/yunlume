import request, { unwrapApiData } from './request'

export interface ImageUploadResult {
  url: string
  filename: string
  size: number
  width: number
  height: number
}

export async function uploadImage(
  file: File,
  onProgress?: (percentage: number | undefined) => void,
): Promise<ImageUploadResult> {
  const data = new FormData()
  data.append('file', file)
  return unwrapApiData(await request.post('/admin/upload/image', data, {
    timeout: 120000,
    onUploadProgress: ({ loaded, total }) => {
      onProgress?.(total && total > 0
        ? Math.min(100, Math.round((loaded / total) * 100))
        : undefined)
    },
  }))
}
