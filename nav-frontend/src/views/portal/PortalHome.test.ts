import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('portal initial rendering', () => {
  it('renders bundled content immediately while public data refreshes in the background', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/views/portal/PortalHome.vue'), 'utf8')

    expect(source).not.toContain('const initialPublicDataSettled = ref(false)')
    expect(source).not.toContain('<template v-if="initialPublicDataSettled">')
    expect(source).not.toContain('class="portal-initial-loading"')
    expect(source).toContain('<CategoryGrid')
    expect(source).toContain('void loadPublicData()')
  })

  it('refreshes the background height on width changes but ignores keyboard-only height changes', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/views/portal/PortalHome.vue'), 'utf8')

    expect(source).toContain("const backgroundViewportHeight = ref('100vh')")
    expect(source).toContain('if (window.innerWidth === backgroundViewportWidth) return')
    expect(source).toContain('backgroundViewportWidth = window.innerWidth')
    expect(source).toContain('backgroundViewportHeight.value = `${window.innerHeight}px`')
    expect(source).toContain("window.addEventListener('resize', syncBackgroundViewportHeight)")
    expect(source).toContain("window.removeEventListener('resize', syncBackgroundViewportHeight)")
    expect(source).toContain("'--portal-background-viewport-height': backgroundViewportHeight.value")
    expect(source).toContain(':style="portalStyle"')
  })
})