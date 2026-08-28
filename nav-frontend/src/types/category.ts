import type { Bookmark } from './bookmark'
import type { EntityId } from './common'

export interface Category {
  id: EntityId
  name: string
  icon: string
  sortOrder: number
  visible: boolean
  bookmarkCount?: number
  createdAt?: string
  updatedAt?: string
}

export interface NavigationCategory extends Category {
  bookmarks: Bookmark[]
}

export type CategoryPayload = Omit<Category, 'id' | 'bookmarkCount' | 'createdAt' | 'updatedAt'>
