import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('import confirmation layout', () => {
  it('keeps the checkbox label separate from the phrase input grid', () => {
    const component = readFileSync(
      resolve(process.cwd(), 'src/components/admin/ImportPreviewDialog.vue'),
      'utf8',
    )
    const stylesheet = readFileSync(
      resolve(process.cwd(), 'src/styles/admin/_data.scss'),
      'utf8',
    )
    const confirmationBlock = stylesheet.slice(
      stylesheet.indexOf('.data-import-confirmation {'),
      stylesheet.indexOf('.data-import-progress__hero'),
    )

    expect(component).toContain('class="data-import-confirmation__phrase"')
    expect(confirmationBlock).toMatch(/> \.data-import-confirmation__phrase\s*{[\s\S]*?display:\s*grid/)
    expect(confirmationBlock).toMatch(/> \.el-checkbox\s*{[\s\S]*?display:\s*inline-flex/)
    expect(confirmationBlock).not.toMatch(/>\s*label\b/)
  })
})
