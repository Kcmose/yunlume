import request, { unwrapApiData } from './request'

export interface ImageUploadResult {
  url: string
  filename: string
  size: number
  width: number
  height: number
}

export async function uploadImage(file: File): Promise<ImageUploadResult> {
  const data = new FormData()
  data.append('file', file)
  return unwrapApiData(await request.post('/admin/upload/image', data))
}
