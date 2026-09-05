import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMocks = vi.hoisted(() => ({ post: vi.fn() }))

vi.mock('./request', () => ({
  default: requestMocks,
  unwrapApiData: (response: { data: unknown }) => response.data,
}))

import { uploadImage } from './upload.api'

describe('image upload request', () => {
  beforeEach(() => {
    requestMocks.post.mockReset().mockResolvedValue({ data: { url: '/uploads/a.png' } })
  })

  it('uses a dedicated upload timeout and reports progress', async () => {
    const progress = vi.fn()
    const file = new File(['image'], 'a.png', { type: 'image/png' })

    await uploadImage(file, progress)

    const [, data, config] = requestMocks.post.mock.calls[0]
    expect(data).toBeInstanceOf(FormData)
    expect(config.timeout).toBe(120000)
    config.onUploadProgress({ loaded: 5, total: 10 })
    config.onUploadProgress({ loaded: 5 })
    expect(progress.mock.calls).toEqual([[50], [undefined]])
  })
})
