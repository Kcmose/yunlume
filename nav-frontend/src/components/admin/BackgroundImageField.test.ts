import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { UploadRequestOptions } from 'element-plus'
import type { ImageUploadResult } from '@/api/upload.api'
import { deferred, mountComponent } from '@/test/componentHarness'
import BackgroundImageField from './BackgroundImageField.vue'

const mocks = vi.hoisted(() => ({ upload: vi.fn(), success: vi.fn(), error: vi.fn() }))
vi.mock('@/api/upload.api', () => ({ uploadImage: mocks.upload }))
vi.mock('element-plus', () => ({ ElMessage: { success: mocks.success, error: mocks.error } }))

interface UploadState {
  uploading: boolean
  uploadProgress: number | undefined
  handleUpload(options: UploadRequestOptions): Promise<ImageUploadResult | undefined>
  updateImage(value: string): void
}

const uploaded: ImageUploadResult = {
  url: '/uploads/new.png', filename: 'new.png', size: 4, width: 100, height: 50,
}
const options = () => ({ file: new File(['png'], 'a.png', { type: 'image/png' }) }) as UploadRequestOptions
const cleanup: Array<() => void> = []

function mount(props: Record<string, unknown> = {}) {
  const instance = mountComponent<UploadState>(BackgroundImageField, {
    modelValue: '/uploads/old.png', label: '背景', hint: '', recommendedSize: '', ...props,
  })
  cleanup.push(instance.unmount)
  return instance
}

beforeEach(() => vi.clearAllMocks())
afterEach(() => cleanup.splice(0).forEach((unmount) => unmount()))

describe('background upload lifecycle', () => {
  it('reports progress, applies the completed image and releases its pending state once', async () => {
    const upload = deferred<ImageUploadResult>()
    mocks.upload.mockReturnValueOnce(upload.promise)
    const events = vi.fn()
    const update = vi.fn()
    const { state } = mount({ 'onUploading-change': events, 'onUpdate:modelValue': update })

    const pending = state.handleUpload(options())
    expect(state.uploading).toBe(true)
    expect(state.uploadProgress).toBeUndefined()
    mocks.upload.mock.calls[0][1](40)
    expect(state.uploadProgress).toBe(40)
    upload.resolve(uploaded)
    await pending

    expect(state.uploading).toBe(false)
    expect(state.uploadProgress).toBeUndefined()
    expect(update).toHaveBeenCalledExactlyOnceWith(uploaded.url)
    expect(events.mock.calls).toEqual([[true], [false]])
    expect(mocks.success).toHaveBeenCalledOnce()
  })

  it('releases the parent count before unmount and ignores late progress and success', async () => {
    const upload = deferred<ImageUploadResult>()
    mocks.upload.mockReturnValueOnce(upload.promise)
    const events = vi.fn()
    const update = vi.fn()
    let pendingCount = 0
    const instance = mount({
      'onUploading-change': (value: boolean) => { events(value); pendingCount += value ? 1 : -1 },
      'onUpdate:modelValue': update,
    })

    const pending = instance.state.handleUpload(options())
    expect(pendingCount).toBe(1)
    instance.unmount()
    expect(pendingCount).toBe(0)
    mocks.upload.mock.calls[0][1](95)
    upload.resolve(uploaded)
    await pending

    expect(pendingCount).toBe(0)
    expect(instance.state.uploadProgress).toBeUndefined()
    expect(events.mock.calls).toEqual([[true], [false]])
    expect(update).not.toHaveBeenCalled()
    expect(mocks.success).not.toHaveBeenCalled()
  })

  it.each(['', 'https://example.com/chosen.png'])('keeps an explicit edit made during upload: %s', async (value) => {
    const upload = deferred<ImageUploadResult>()
    mocks.upload.mockReturnValueOnce(upload.promise)
    const update = vi.fn()
    const { state } = mount({ 'onUpdate:modelValue': update })
    const pending = state.handleUpload(options())

    state.updateImage(value)
    upload.resolve(uploaded)
    await pending

    expect(update.mock.calls).toEqual([[value]])
    expect(mocks.success).not.toHaveBeenCalled()
    expect(state.uploading).toBe(false)
  })

  it('does not decrement a replacement component upload when the old request fails', async () => {
    const oldUpload = deferred<ImageUploadResult>()
    const newUpload = deferred<ImageUploadResult>()
    mocks.upload.mockReturnValueOnce(oldUpload.promise).mockReturnValueOnce(newUpload.promise)
    let pendingCount = 0
    const props = { 'onUploading-change': (value: boolean) => { pendingCount += value ? 1 : -1 } }
    const oldInstance = mount(props)
    const oldPending = oldInstance.state.handleUpload(options())
    oldInstance.unmount()
    const nextInstance = mount(props)
    const nextPending = nextInstance.state.handleUpload(options())
    expect(pendingCount).toBe(1)

    const rejected = expect(oldPending).rejects.toThrow('old failed')
    oldUpload.reject(new Error('old failed'))
    await rejected
    expect(pendingCount).toBe(1)
    expect(mocks.error).not.toHaveBeenCalled()
    newUpload.resolve(uploaded)
    await nextPending
    expect(pendingCount).toBe(0)
  })

  it('does not start a duplicate upload and releases a failed active request', async () => {
    const upload = deferred<ImageUploadResult>()
    mocks.upload.mockReturnValueOnce(upload.promise)
    const events = vi.fn()
    const { state } = mount({ 'onUploading-change': events })
    const pending = state.handleUpload(options())
    await state.handleUpload(options())
    expect(mocks.upload).toHaveBeenCalledOnce()
    const rejected = expect(pending).rejects.toThrow('failed')
    upload.reject(new Error('failed'))
    await rejected
    expect(events.mock.calls).toEqual([[true], [false]])
    expect(mocks.error).toHaveBeenCalledExactlyOnceWith('failed')
  })
})
