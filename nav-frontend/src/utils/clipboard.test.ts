import { describe, expect, it, vi } from 'vitest'
import { copyTextWithFallback } from './clipboard'

describe('clipboard fallback selection', () => {
  it('uses the modern Clipboard API when it is available', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    const legacyCopy = vi.fn(() => true)

    await expect(copyTextWithFallback('backup', { writeText }, legacyCopy)).resolves.toBe('clipboard')
    expect(writeText).toHaveBeenCalledWith('backup')
    expect(legacyCopy).not.toHaveBeenCalled()
  })

  it('falls back to the legacy copy action when Clipboard API is unavailable or denied', async () => {
    const denied = { writeText: vi.fn().mockRejectedValue(new Error('denied')) }
    const legacyCopy = vi.fn(() => true)

    await expect(copyTextWithFallback('backup', denied, legacyCopy)).resolves.toBe('legacy')
    await expect(copyTextWithFallback('backup', undefined, legacyCopy)).resolves.toBe('legacy')
    expect(legacyCopy).toHaveBeenCalledTimes(2)
  })

  it('reports failure without throwing when both copy methods fail', async () => {
    const denied = { writeText: vi.fn().mockRejectedValue(new Error('denied')) }

    await expect(copyTextWithFallback('backup', denied, () => false)).resolves.toBe('failed')
    await expect(copyTextWithFallback('backup', undefined, () => {
      throw new Error('unsupported')
    })).resolves.toBe('failed')
  })
})
