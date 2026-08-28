import { describe, expect, it } from 'vitest'
import type { Bookmark } from '@/types/bookmark'
import type { Category } from '@/types/category'
import { countPublicBookmarks } from './dashboardStats'

const categories: Category[] = [
  { id: 1, name: '公开分类', icon: '', sortOrder: 10, visible: true },
  { id: 2, name: '隐藏分类', icon: '', sortOrder: 20, visible: false },
]

const createBookmark = (
  id: number,
  categoryId: number | string,
  visible: boolean,
): Bookmark => ({
  id,
  categoryId,
  name: `书签 ${id}`,
  url: `https://example.com/${id}`,
  icon: '',
  description: '',
  sortOrder: id * 10,
  isRecommend: false,
  isExternal: true,
  visible,
})

describe('dashboard public bookmark count', () => {
  it('counts only visible bookmarks inside visible categories', () => {
    const bookmarks = [
      createBookmark(1, 1, true),
      createBookmark(2, 1, false),
      createBookmark(3, 2, true),
    ]

    expect(countPublicBookmarks(categories, bookmarks)).toBe(1)
  })

  it('matches equivalent numeric and string ids from the API', () => {
    expect(countPublicBookmarks(categories, [createBookmark(1, '1', true)])).toBe(1)
  })
})
