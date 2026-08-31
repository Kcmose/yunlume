import { describe, expect, it, vi } from 'vitest'
import {
  clearStaleChunkRecoveryMarker,
  registerStaleChunkRecovery,
} from './staleChunkRecovery'

class MemoryStorage {
  private readonly values = new Map<string, string>()

  getItem(key: string) {
    return this.values.get(key) ?? null
  }

  setItem(key: string, value: string) {
    this.values.set(key, value)
  }

  removeItem(key: string) {
    this.values.delete(key)
  }
}

describe('stale dynamic chunk recovery', () => {
  it('cancels the failed preload and reloads once', () => {
    const target = new EventTarget()
    const storage = new MemoryStorage()
    const reload = vi.fn()
    const unregister = registerStaleChunkRecovery({ target, storage, reload })

    const firstFailure = new Event('vite:preloadError', { cancelable: true })
    target.dispatchEvent(firstFailure)

    expect(firstFailure.defaultPrevented).toBe(true)
    expect(reload).toHaveBeenCalledOnce()

    const repeatedFailure = new Event('vite:preloadError', { cancelable: true })
    target.dispatchEvent(repeatedFailure)

    expect(repeatedFailure.defaultPrevented).toBe(false)
    expect(reload).toHaveBeenCalledOnce()
    unregister()
  })

  it('leaves the original preload error untouched when session storage is unavailable', () => {
    const target = new EventTarget()
    const reload = vi.fn()
    const storage = {
      getItem: () => {
        throw new DOMException('Storage is unavailable', 'SecurityError')
      },
      setItem: vi.fn(),
      removeItem: vi.fn(),
    }
    registerStaleChunkRecovery({ target, storage, reload })

    const failure = new Event('vite:preloadError', { cancelable: true })
    target.dispatchEvent(failure)

    expect(failure.defaultPrevented).toBe(false)
    expect(reload).not.toHaveBeenCalled()
  })

  it('allows recovery again after a successful router startup', () => {
    const target = new EventTarget()
    const storage = new MemoryStorage()
    const reload = vi.fn()
    registerStaleChunkRecovery({ target, storage, reload })

    target.dispatchEvent(new Event('vite:preloadError', { cancelable: true }))
    clearStaleChunkRecoveryMarker(storage)
    target.dispatchEvent(new Event('vite:preloadError', { cancelable: true }))

    expect(reload).toHaveBeenCalledTimes(2)
  })
})
