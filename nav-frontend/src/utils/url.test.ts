import { describe, expect, it } from 'vitest'
import {
  baiduSearchUrl,
  buildSearchUrl,
  ensureHttpProtocol,
  isSafeHttpUrl,
} from './url'

describe('URL helpers', () => {
  it('adds a protocol to domain-only input', () => {
    expect(ensureHttpProtocol('example.com/docs')).toBe('https://example.com/docs')
  })

  it('keeps valid HTTP URLs unchanged', () => {
    expect(ensureHttpProtocol('http://localhost:8080')).toBe('http://localhost:8080')
  })

  it('rejects non HTTP links', () => {
    expect(isSafeHttpUrl('javascript:alert(1)')).toBe(false)
  })

  it('encodes Baidu search keywords', () => {
    expect(baiduSearchUrl('Vue 导航')).toBe('https://www.baidu.com/s?wd=Vue%20%E5%AF%BC%E8%88%AA')
  })

  it('replaces a configured search template and encodes its keyword', () => {
    expect(buildSearchUrl('https://www.google.com/search?q={keyword}', '导航 站')).toBe(
      'https://www.google.com/search?q=%E5%AF%BC%E8%88%AA%20%E7%AB%99',
    )
  })

  it('adds a query parameter when a search template has no placeholder', () => {
    expect(buildSearchUrl('https://example.com/search?source=nav', 'Vue')).toBe(
      'https://example.com/search?source=nav&q=Vue',
    )
  })

  it('falls back to Baidu for an unsafe search template', () => {
    expect(buildSearchUrl('javascript:alert({keyword})', 'Vue')).toBe(
      'https://www.baidu.com/s?wd=Vue',
    )
  })

})
