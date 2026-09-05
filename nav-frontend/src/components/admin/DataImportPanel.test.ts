import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('data import job recovery', () => {
  it('falls back to the server when sessionStorage is unavailable', () => {
    const source = readFileSync(
      resolve(process.cwd(), 'src/components/admin/DataImportPanel.vue'),
      'utf8',
    )

    expect(source).toContain("persistentError.value = '浏览器无法读取上次保存的导入任务状态'")
    expect(source).not.toMatch(/浏览器无法读取上次保存的导入任务状态'\s*\n\s*return/)
    expect(source).toContain('const recovered = await getCurrentNavigationDataImportJob()')
    expect(source).toContain('const restoreRequestId = ++restoreRequestVersion')
    expect(source).toContain("if (disposed || restoreRequestId !== restoreRequestVersion || state.value !== 'IDLE') return")
  })

  it('checks a stored job before falling back to current-job discovery', () => {
    const source = readFileSync(
      resolve(process.cwd(), 'src/components/admin/DataImportPanel.vue'),
      'utf8',
    )

    expect(source).toContain('await getNavigationDataImportJob(session.jobId)')
    expect(source).toMatch(/status === 404 \|\| status === 410[\s\S]*safeClearJobSession\(\)[\s\S]*getCurrentNavigationDataImportJob\(\)/)
    expect(source).toMatch(/if \(!isImportJobTerminal\(result\.stage\)\)[\s\S]*else \{\s*safeClearJobSession\(\)/)
  })
})
