import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const styleFiles = [
  'admin/_account.scss',
  'admin/_dashboard.scss',
  'admin/_data.scss',
  'admin/_forms.scss',
  'admin/_layout.scss',
  'admin/_login.scss',
  'admin/_install.scss',
  'portal/_card.scss',
  'portal/_layout.scss',
]

const readStyle = (relativePath: string) =>
  readFileSync(resolve(process.cwd(), 'src/styles', relativePath), 'utf8')

describe('typography sizing policy', () => {
  it('does not introduce visible pixel font sizes below 12px outside the preserved sidebar', () => {
    const offenders: string[] = []
    let declarationCount = 0

    styleFiles.forEach((relativePath) => {
      let source = readStyle(relativePath)
      if (relativePath === 'admin/_layout.scss') {
        const sidebarStart = source.indexOf('.admin-sidebar {')
        const contentStart = source.indexOf('.admin-shell__main')
        expect(sidebarStart).toBeGreaterThan(-1)
        expect(contentStart).toBeGreaterThan(sidebarStart)
        source = source.slice(0, sidebarStart) + source.slice(contentStart)
      }
      for (const match of source.matchAll(/font-size:\s*(\d+)px/g)) {
        declarationCount += 1
        const size = Number(match[1])
        if (size < 12) offenders.push(`${relativePath}:${size}px`)
      }
    })

    expect(declarationCount).toBeGreaterThan(150)
    expect(offenders).toEqual([])
  })

  it('preserves the original compact sidebar typography', () => {
    const source = readStyle('admin/_layout.scss')

    expect(source).toMatch(/\.admin-sidebar__brand[\s\S]*?strong\s*{\s*font-size:\s*18px/)
    expect(source).toMatch(/\.admin-sidebar__brand[\s\S]*?small\s*{[\s\S]*?font-size:\s*7px/)
    expect(source).toMatch(/\.admin-sidebar__section-label\s*{[\s\S]*?font-size:\s*10px/)
    expect(source).toMatch(/\.admin-sidebar__link\s*{[\s\S]*?font-size:\s*13px/)
    expect(source).toMatch(/\.admin-sidebar__tip\s*{[\s\S]*?strong\s*{\s*font-size:\s*11px/)
    expect(source).toMatch(/\.admin-sidebar__tip\s*{[\s\S]*?p\s*{[\s\S]*?font-size:\s*9px/)
    expect(source).toMatch(/\.admin-sidebar__portal-link\s*{[\s\S]*?font-size:\s*11px/)
  })

  it('keeps Element Plus typography and controls aligned with the larger scale', () => {
    const source = readStyle('_common.scss')

    expect(source).toContain('--el-font-size-extra-small: 14px')
    expect(source).toContain('--el-font-size-base: 16px')
    expect(source).toContain('--el-component-size-small: 36px')
    expect(source).toContain('--el-component-size: 40px')
  })
})
