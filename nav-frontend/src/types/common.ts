export interface ApiResponse<T> {
  code: number
  message: string
  data?: T
}

export interface PageResult<T> {
  records: T[]
  total: number
  current?: number
  size?: number
}

export type EntityId = number | string

export interface SortOrderItem {
  id: EntityId
  sortOrder: number
}
