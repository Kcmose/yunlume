export const SORT_ORDER_ERROR = '排序值必须是 0–2147483647 之间的整数'

// 由业务校验拒绝越界输入；组件的 min/max 会在校验前把 -0.1 等输入改成 0。
export const sortOrderInputProps = { min: -Infinity, max: Infinity }

export function isValidSortOrder(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 2147483647
}
