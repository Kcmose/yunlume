import type { Bookmark } from '@/types/bookmark'
import type { Category } from '@/types/category'

export function countPublicBookmarks(
  categories: Category[],
  bookmarks: Bookmark[],
): number {
  const visibleCategoryIds = new Set(
    categories
      .filter((category) => category.visible)
      .map((category) => String(category.id)),
  )

  return bookmarks.filter((bookmark) => (
    bookmark.visible && visibleCategoryIds.has(String(bookmark.categoryId))
  )).length
}
