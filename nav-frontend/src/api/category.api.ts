import request, { unwrapApiData } from './request'
import type { Category, CategoryPayload } from '@/types/category'
import type { EntityId, SortOrderItem } from '@/types/common'

export async function getCategories(): Promise<Category[]> {
  return unwrapApiData(await request.get('/admin/categories'))
}

export async function createCategory(payload: CategoryPayload): Promise<Category> {
  return unwrapApiData(await request.post('/admin/categories', payload))
}

export async function updateCategory(id: EntityId, payload: CategoryPayload): Promise<Category> {
  return unwrapApiData(await request.put(`/admin/categories/${id}`, payload))
}

export async function deleteCategory(id: EntityId): Promise<void> {
  return unwrapApiData(await request.delete(`/admin/categories/${id}`))
}

export async function setCategoryVisible(id: EntityId, visible: boolean): Promise<Category> {
  return unwrapApiData(await request.put(`/admin/categories/${id}/visible`, { visible }))
}

export async function sortCategories(items: SortOrderItem[]): Promise<Category[]> {
  return unwrapApiData(await request.put('/admin/categories/sort', items))
}
