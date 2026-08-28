import type { EntityId } from './common'

export interface Bookmark {
  id: EntityId
  categoryId: EntityId
  name: string
  url: string
  icon: string
  description: string
  sortOrder: number
  isRecommend: boolean
  isExternal: boolean
  visible: boolean
  createdAt?: string
  updatedAt?: string
}

export type BookmarkPayload = Omit<Bookmark, 'id' | 'createdAt' | 'updatedAt'>
