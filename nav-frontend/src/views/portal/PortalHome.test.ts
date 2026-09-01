import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('portal initial rendering', () => {
  it('keeps the configured portal hidden until the first public-data load settles', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/views/portal/PortalHome.vue'), 'utf8')

    expect(source).toContain('const initialPublicDataSettled = ref(false)')
    expect(source).toMatch(/await Promise\.allSettled\([\s\S]*?initialPublicDataSettled\.value = true/)
    expect(source).toContain('<template v-if="initialPublicDataSettled">')
    expect(source).toContain('class="portal-initial-loading"')
  })

  it('snapshots the background height once instead of following keyboard resize events', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/views/portal/PortalHome.vue'), 'utf8')

    expect(source).toContain("const backgroundViewportHeight = ref('100vh')")
    expect(source).toContain('backgroundViewportHeight.value = `${window.innerHeight}px`')
    expect(source).toContain("'--portal-background-viewport-height': backgroundViewportHeight.value")
    expect(source).toContain(':style="portalStyle"')
    expect(source).not.toContain("addEventListener('resize'")
  })
})