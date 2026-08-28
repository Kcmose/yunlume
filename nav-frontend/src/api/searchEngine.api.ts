import request, { unwrapApiData } from './request'
import type {
  AdminSearchEngine,
  SearchEnginePayload,
} from '@/types/searchEngine'
import type { EntityId } from '@/types/common'

export async function getSearchEngines(): Promise<AdminSearchEngine[]> {
  return unwrapApiData(await request.get('/admin/search-engines'))
}

export async function createSearchEngine(payload: SearchEnginePayload): Promise<AdminSearchEngine> {
  return unwrapApiData(await request.post('/admin/search-engines', payload))
}

export async function updateSearchEngine(
  id: EntityId,
  payload: SearchEnginePayload,
): Promise<AdminSearchEngine> {
  return unwrapApiData(await request.put(`/admin/search-engines/${id}`, payload))
}

export async function deleteSearchEngine(id: EntityId): Promise<void> {
  return unwrapApiData(await request.delete(`/admin/search-engines/${id}`))
}

export async function setSearchEngineVisible(
  id: EntityId,
  visible: boolean,
): Promise<AdminSearchEngine> {
  return unwrapApiData(
    await request.put(`/admin/search-engines/${id}/visible`, { visible }),
  )
}

export async function setDefaultSearchEngine(id: EntityId): Promise<AdminSearchEngine> {
  return unwrapApiData(await request.put(`/admin/search-engines/${id}/default`))
}

export async function sortSearchEngines(
  items: Array<{ id: EntityId; sortOrder: number }>,
): Promise<AdminSearchEngine[]> {
  return unwrapApiData(await request.put('/admin/search-engines/sort', items))
}
