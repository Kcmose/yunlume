import request, { unwrapApiData } from './request'
import type { Bookmark, BookmarkPayload } from '@/types/bookmark'
import type { EntityId, SortOrderItem } from '@/types/common'

export async function getBookmarks(categoryId?: EntityId): Promise<Bookmark[]> {
  return unwrapApiData(
    await request.get('/admin/bookmarks', {
      params: categoryId ? { categoryId } : undefined,
    }),
  )
}

export async function createBookmark(payload: BookmarkPayload): Promise<Bookmark> {
  return unwrapApiData(await request.post('/admin/bookmarks', payload))
}

export async function updateBookmark(id: EntityId, payload: BookmarkPayload): Promise<Bookmark> {
  return unwrapApiData(await request.put(`/admin/bookmarks/${id}`, payload))
}

export async function deleteBookmark(id: EntityId): Promise<void> {
  return unwrapApiData(await request.delete(`/admin/bookmarks/${id}`))
}

export async function setBookmarkVisible(id: EntityId, visible: boolean): Promise<Bookmark> {
  return unwrapApiData(await request.put(`/admin/bookmarks/${id}/visible`, { visible }))
}

export async function sortBookmarks(items: SortOrderItem[]): Promise<Bookmark[]> {
  return unwrapApiData(await request.put('/admin/bookmarks/sort', items))
}

export async function batchMoveBookmarks(
  ids: EntityId[],
  categoryId: EntityId,
): Promise<Bookmark[]> {
  return unwrapApiData(
    await request.put('/admin/bookmarks/batch-move', { ids, categoryId }),
  )
}
