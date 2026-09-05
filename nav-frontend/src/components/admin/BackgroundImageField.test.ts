import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('background image upload feedback', () => {
  it('starts indeterminate and becomes determinate only when progress is reported', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/components/admin/BackgroundImageField.vue'), 'utf8')

    expect(source).toContain('const uploadProgress = ref<number | undefined>(undefined)')
    expect(source).toMatch(/uploading\.value = true\s+uploadProgress\.value = undefined\s+emit\('uploading-change', true\)/)
    expect(source).not.toContain('uploadProgress.value = 0')
    expect(source).toContain('uploadImage(options.file, (progress) =>')
    expect(source).toContain('<el-progress')
    expect(source).toContain('v-if="uploading"')
    expect(source).toContain(':percentage="uploadProgress ?? 0"')
    expect(source).toContain(':indeterminate="uploadProgress === undefined"')
    expect(source).toContain(':show-text="uploadProgress !== undefined"')
  })
})
