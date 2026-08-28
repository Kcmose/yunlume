import { describe, expect, it, vi } from 'vitest'
import {
  invalidateAdminSession,
  registerAdminSessionInvalidationHandler,
} from './sessionInvalidation'

describe('admin session invalidation', () => {
  it('reports an unhandled request when the app handler is unavailable', async () => {
    expect(await invalidateAdminSession()).toBe(false)
  })

  it('deduplicates simultaneous invalidation responses', async () => {
    let release!: () => void
    const handler = vi.fn(() => new Promise<void>((resolve) => {
      release = resolve
    }))
    const unregister = registerAdminSessionInvalidationHandler(handler)

    const first = invalidateAdminSession()
    const second = invalidateAdminSession()

    expect(first).toBe(second)
    await vi.waitFor(() => expect(handler).toHaveBeenCalledOnce())
    release()

    await expect(first).resolves.toBe(true)
    unregister()
  })
})
