import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const styles = readFileSync(resolve(process.cwd(), 'src/styles/portal/_layout.scss'), 'utf8')
const cardStyles = readFileSync(resolve(process.cwd(), 'src/styles/portal/_card.scss'), 'utf8')

describe('portal reference glass effect', () => {
  it('matches the reference card glass recipe on image backgrounds', () => {
    expect(styles).toMatch(/\&\[data-background-type='image'\]\s*\{[\s\S]*?--portal-card-surface:\s*transparent;/)
    expect(styles).toMatch(/\&\[data-background-type='image'\]\s*\{[\s\S]*?--portal-glass-border:\s*rgba\(255,\s*255,\s*255,\s*\.4\);/)
    expect(styles).toMatch(/\&\[data-background-type='image'\]\s*\{[\s\S]*?--portal-glass-filter:\s*blur\(1\.5px\);/)
    expect(styles).toMatch(/\&\[data-background-type='image'\]\s*\{[\s\S]*?--portal-glass-shadow:\s*-1px -1px 5px rgba\(0,\s*0,\s*0,\s*\.3\);/)
    expect(cardStyles).toMatch(/\.category-card__body\s*\{[\s\S]*?border:\s*0;[\s\S]*?border-right:\s*1px solid var\(--portal-glass-border\);[\s\S]*?border-bottom:\s*1px solid var\(--portal-glass-border\);/)
  })

  it('matches the reference search glass recipe', () => {
    expect(styles).toMatch(/\&\[data-background-type='image'\]\s*\{[\s\S]*?--portal-search-surface:\s*rgba\(255,\s*255,\s*255,\s*\.25\);/)
    expect(styles).toMatch(/\&\[data-background-type='image'\]\s*\{[\s\S]*?--portal-glass-filter-strong:\s*blur\(10px\) saturate\(150%\);/)
    expect(styles).toMatch(/--portal-glass-shadow-strong:\s*0 0 10px rgba\(0,\s*0,\s*0,\s*\.2\);/)
    expect(styles).toMatch(/\.portal-search\s*\{[\s\S]*?box-shadow:\s*var\(--portal-glass-shadow-strong\);/)
  })

  it('removes the search outline and deepens only its interactive shadow', () => {
    expect(styles).toMatch(/\&\[data-background-type='image'\]\s*\{[\s\S]*?--portal-search-shadow-active:\s*0 4px 18px rgba\(0,\s*0,\s*0,\s*\.38\);/)
    expect(styles).toMatch(/\.portal-search\s*\{[\s\S]*?border:\s*0;[\s\S]*?&:hover,\s*&:focus-within\s*\{[\s\S]*?box-shadow:\s*var\(--portal-search-shadow-active\);/)
    expect(styles).not.toMatch(/&:focus-within\s*\{[^}]*border-color:/)
  })

  it('keeps the same glass values on mobile image backgrounds', () => {
    expect(styles).toMatch(/\.portal-page\[data-background-type='image'\]\s*\{[\s\S]*?--portal-card-surface:\s*transparent;/)
    expect(styles).toMatch(/\.portal-page\[data-background-type='image'\]\s*\{[\s\S]*?--portal-search-surface:\s*rgba\(255,\s*255,\s*255,\s*\.25\);/)
    expect(styles).toMatch(/\.portal-page\[data-background-type='image'\]\s*\{[\s\S]*?--portal-glass-filter:\s*blur\(1\.5px\);/)
  })
})
