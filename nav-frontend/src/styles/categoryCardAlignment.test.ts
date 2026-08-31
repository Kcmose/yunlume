import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const readSource = (relativePath: string) =>
  readFileSync(resolve(process.cwd(), relativePath), 'utf8')

describe('portal category title alignment', () => {
  it('optically aligns the category icon with the title text', () => {
    const component = readSource('src/components/portal/CategoryCard.vue')
    const styles = readSource('src/styles/portal/_card.scss')

    expect(component).toContain('class="category-card__icon"')
    expect(styles).toMatch(/\.category-card__icon\s*{[\s\S]*?line-height:\s*1;/)
    expect(styles).toMatch(/\.category-card__icon\s*{[\s\S]*?transform:\s*translateY\(-1px\);/)
  })
})
