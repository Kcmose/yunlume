export function ensureHttpProtocol(value: string): string {
  const trimmed = value.trim()
  if (!trimmed) return ''
  if (/^https?:\/\//i.test(trimmed)) return trimmed
  return `https://${trimmed}`
}

export function isSafeHttpUrl(value: string): boolean {
  try {
    const url = new URL(ensureHttpProtocol(value))
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

export function baiduSearchUrl(keyword: string): string {
  return `https://www.baidu.com/s?wd=${encodeURIComponent(keyword.trim())}`
}

export function buildSearchUrl(template: string, keyword: string): string {
  const encodedKeyword = encodeURIComponent(keyword.trim())
  if (!template.trim()) return baiduSearchUrl(keyword)
  const normalizedTemplate = ensureHttpProtocol(template)
  let target: string
  if (normalizedTemplate.includes('{keyword}')) {
    target = normalizedTemplate.split('{keyword}').join(encodedKeyword)
  } else {
    const separator = normalizedTemplate.includes('?') ? '&' : '?'
    target = `${normalizedTemplate}${separator}q=${encodedKeyword}`
  }
  return isSafeHttpUrl(target) ? target : baiduSearchUrl(keyword)
}
