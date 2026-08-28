import { describe, expect, it, vi } from 'vitest'
import { isRetryablePublicRequestError, withPublicRequestRetry } from './publicRequestRetry'

describe('public request retry', () => {
  it('retries network and gateway availability failures with bounded backoff', async () => {
    const request = vi.fn()
      .mockRejectedValueOnce({ isAxiosError: true })
      .mockRejectedValueOnce(Object.assign(new Error('unavailable'), { status: 503 }))
      .mockResolvedValue('ready')
    const sleep = vi.fn(async () => undefined)

    await expect(withPublicRequestRetry(request, { delays: [10, 20], sleep })).resolves.toBe('ready')
    expect(request).toHaveBeenCalledTimes(3)
    expect(sleep.mock.calls).toEqual([[10], [20]])
  })

  it.each([502, 503, 504])('recognizes HTTP %s as retryable', (status) => {
    expect(isRetryablePublicRequestError({ response: { status } })).toBe(true)
  })

  it.each([400, 401, 403, 404, 500])('does not retry HTTP %s', async (status) => {
    const failure = Object.assign(new Error('request failed'), { status })
    const request = vi.fn().mockRejectedValue(failure)

    await expect(withPublicRequestRetry(request, { delays: [0], sleep: vi.fn() })).rejects.toBe(failure)
    expect(request).toHaveBeenCalledTimes(1)
  })

  it('stops after the configured number of retries', async () => {
    const failure = { isAxiosError: true }
    const request = vi.fn().mockRejectedValue(failure)
    const sleep = vi.fn(async () => undefined)

    await expect(withPublicRequestRetry(request, { delays: [10, 20], sleep })).rejects.toBe(failure)
    expect(request).toHaveBeenCalledTimes(3)
    expect(sleep).toHaveBeenCalledTimes(2)
  })
})
