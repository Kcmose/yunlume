import type { EntityId, SortOrderItem } from '@/types/common'
import { isSafeHttpUrl } from './url'

export interface IdentifiedItem {
  id: EntityId
}

export function entityKey(id: EntityId): string {
  return String(id)
}

export function moveItem<T>(items: readonly T[], fromIndex: number, toIndex: number): T[] {
  if (
    fromIndex < 0
    || fromIndex >= items.length
    || toIndex < 0
    || toIndex >= items.length
    || fromIndex === toIndex
  ) {
    return [...items]
  }

  const result = [...items]
  const [item] = result.splice(fromIndex, 1)
  result.splice(toIndex, 0, item)
  return result
}

export function hasSameEntityOrder(
  left: readonly IdentifiedItem[],
  right: readonly IdentifiedItem[],
): boolean {
  return left.length === right.length
    && left.every((item, index) => entityKey(item.id) === entityKey(right[index]!.id))
}

export function buildSequentialSortPayload(
  items: readonly IdentifiedItem[],
  step = 10,
): SortOrderItem[] {
  return items.map((item, index) => ({ id: item.id, sortOrder: index * step }))
}

export function reconcileSelectedKeys(
  selectedKeys: readonly string[],
  availableIds: readonly EntityId[],
): string[] {
  const available = new Set(availableIds.map(entityKey))
  return selectedKeys.filter((key) => available.has(key))
}

export function mergeScopedSelection(
  selectedKeys: readonly string[],
  scopeIds: readonly EntityId[],
  selectedScopeIds: readonly EntityId[],
): string[] {
  const scope = new Set(scopeIds.map(entityKey))
  const selectedScope = selectedScopeIds.map(entityKey)
  const outsideScope = selectedKeys.filter((key) => !scope.has(key))
  return [...new Set([...outsideScope, ...selectedScope])]
}

export function selectionAfterBatchRequest(
  selectedKeys: readonly string[],
  succeeded: boolean,
): string[] {
  return succeeded ? [] : [...selectedKeys]
}

export function isValidNavigationIcon(value: string): boolean {
  const icon = value.trim()
  return !icon
    || [...icon].length <= 3
    || (/^https?:\/\//i.test(icon) && isSafeHttpUrl(icon))
}

export function navigationIconUrl(value: string): string {
  const icon = value.trim()
  return /^https?:\/\//i.test(icon) && isSafeHttpUrl(icon) ? icon : ''
}

export function navigationIconLabel(value: string, fallback: string): string {
  const icon = value.trim()
  if (navigationIconUrl(icon)) return fallback
  const characters = [...icon]
  return characters.length > 0 && characters.length <= 3 ? icon : fallback
}
