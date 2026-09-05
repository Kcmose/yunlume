import { describe, expect, it } from 'vitest'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { isValidNavigationIcon } from '@/utils/adminNavigationManage'
import type { NavigationCategory } from '@/types/category'
import CategoryCard from './CategoryCard.vue'

function category(icon: string): NavigationCategory {
  return {
    id: 1, name: '类别', icon, sortOrder: 0, visible: true,
    bookmarks: [{
      id: 1, categoryId: 1, name: '书签', icon, url: 'https://example.com', description: '',
      sortOrder: 0, isRecommend: false, isExternal: true, visible: true,
    }],
  }
}

describe('portal navigation icons', () => {
  it.each(['🇨🇳', '😀', '开发', 'GH'])('renders a mark accepted by the admin form: %s', async (icon) => {
    expect(isValidNavigationIcon(icon)).toBe(true)
    const content = await renderToString(createSSRApp(CategoryCard, { category: category(icon) }))
    expect(content.split(icon).length - 1).toBe(2)
    expect(content).not.toContain('◈')
    expect(content).not.toContain('▱')
  })

  it('keeps the distinct category and bookmark fallbacks for empty icons', async () => {
    const content = await renderToString(createSSRApp(CategoryCard, { category: category('') }))
    expect(content).toContain('◈')
    expect(content).toContain('▱')
  })
})
