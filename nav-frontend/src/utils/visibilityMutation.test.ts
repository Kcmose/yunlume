import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { commitVisibleChange } from './visibilityMutation'

type Row = { id: number; visible: boolean }

describe('serialized visibility mutation', () => {
  it('does not start a second request for the same row while one is pending', async () => {
    let resolve!: (row: Row) => void
    const request = vi.fn(() => new Promise<Row>((done) => { resolve = done }))
    const pending = new Set<string>()
    const row = { id: 7, visible: true }

    const first = commitVisibleChange(row, pending, request)
    const second = commitVisibleChange(row, pending, request)

    expect(request).toHaveBeenCalledTimes(1)
    expect(pending.has('7')).toBe(true)
    await expect(second).resolves.toBe(false)

    resolve({ id: 7, visible: true })
    await expect(first).resolves.toBe(true)
    expect(pending.has('7')).toBe(false)
  })

  it('restores the previous value when the request fails', async () => {
    const failure = new Error('offline')
    const row = { id: 8, visible: false }
    const pending = new Set<string>()

    await expect(commitVisibleChange(row, pending, vi.fn().mockRejectedValue(failure)))
      .rejects.toBe(failure)
    expect(row.visible).toBe(true)
    expect(pending.size).toBe(0)
  })
})

describe('visibility mutation UI exclusion', () => {
  it.each(['CategoryManageView.vue', 'BookmarkManageView.vue'])(
    'disables competing row actions in %s while visibility is pending',
    (view) => {
      const source = readFileSync(resolve(process.cwd(), `src/views/admin/${view}`), 'utf8')
      expect(source.match(/:disabled="visibilityUpdating\(row\)"/g)?.length).toBeGreaterThanOrEqual(3)
    },
  )
})
