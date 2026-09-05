import type { EntityId } from '@/types/common'

interface VisibleEntity {
  id: EntityId
  visible: boolean
}

export async function commitVisibleChange<T extends VisibleEntity>(
  row: T,
  pendingIds: Set<string>,
  request: (id: EntityId, visible: boolean) => Promise<T>,
): Promise<boolean> {
  const key = String(row.id)
  if (pendingIds.has(key)) return false

  const requestedVisible = row.visible
  pendingIds.add(key)
  try {
    const persisted = await request(row.id, requestedVisible)
    row.visible = persisted.visible
    return true
  } catch (error) {
    row.visible = !requestedVisible
    throw error
  } finally {
    pendingIds.delete(key)
  }
}

/** 列表刷新会替换行对象；按 ID 合并显隐结果，并隔离与显隐交错的旧快照。 */
export function createVisibilityTracker<T extends VisibleEntity>(
  getRows: () => readonly T[],
  pendingIds: Set<string>,
) {
  let revision = 0
  const rowRevisions = new Map<string, number>()

  return {
    captureSnapshot: () => revision,
    mergeSnapshot(rows: T[], snapshotRevision: number): T[] {
      const current = new Map(getRows().map((row) => [String(row.id), row]))
      return rows.map((row) => {
        const key = String(row.id)
        const previous = current.get(key)
        const changed = pendingIds.has(key) || (rowRevisions.get(key) ?? 0) > snapshotRevision
        return previous && changed ? { ...row, visible: previous.visible } : row
      })
    },
    async commit(
      row: T,
      request: (id: EntityId, visible: boolean) => Promise<T>,
    ): Promise<boolean> {
      const key = String(row.id)
      if (pendingIds.has(key)) return false
      rowRevisions.set(key, ++revision)
      try {
        return await commitVisibleChange(row, pendingIds, request)
      } finally {
        // 成功或回退都属于新状态，早于它发出的 GET/排序快照不能再覆盖。
        rowRevisions.set(key, ++revision)
        const current = getRows().find((item) => String(item.id) === key)
        if (current) current.visible = row.visible
      }
    },
  }
}
