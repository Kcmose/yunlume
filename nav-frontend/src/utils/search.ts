import type { NavigationCategory } from '@/types/category'

function contains(value: string | undefined, keyword: string): boolean {
  return Boolean(value?.toLocaleLowerCase().includes(keyword))
}

export function filterNavigation(
  categories: NavigationCategory[],
  rawKeyword: string,
): NavigationCategory[] {
  const keyword = rawKeyword.trim().toLocaleLowerCase()
  if (!keyword) return categories

  return categories
    .map((category) => {
      const categoryMatches = contains(category.name, keyword)
      const bookmarks = categoryMatches
        ? category.bookmarks
        : category.bookmarks.filter(
            (bookmark) =>
              contains(bookmark.name, keyword) ||
              contains(bookmark.description, keyword) ||
              contains(bookmark.url, keyword),
          )
      return { ...category, bookmarks }
    })
    .filter((category) => category.bookmarks.length > 0)
}

export function firstSearchMatch(categories: NavigationCategory[], keyword: string) {
  return filterNavigation(categories, keyword)[0]?.bookmarks[0] ?? null
}
