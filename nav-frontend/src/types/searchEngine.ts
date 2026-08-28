import type { EntityId } from './common'

export interface SearchEngine {
  id: EntityId
  name: string
  icon?: string | null
  searchUrl: string
  placeholder?: string | null
  isDefault: boolean
  sortOrder: number
}

export interface AdminSearchEngine extends SearchEngine {
  visible: boolean
  createdAt?: string
  updatedAt?: string
}

export interface SearchEnginePayload {
  name: string
  icon: string
  searchUrl: string
  placeholder: string
  sortOrder: number
  visible: boolean
}
