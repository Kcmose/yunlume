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
