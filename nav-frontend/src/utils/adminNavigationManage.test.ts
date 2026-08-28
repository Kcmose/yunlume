import { describe, expect, it } from 'vitest'
import {
  buildSequentialSortPayload,
  hasSameEntityOrder,
  isValidNavigationIcon,
  mergeScopedSelection,
  moveItem,
  navigationIconLabel,
  navigationIconUrl,
  reconcileSelectedKeys,
  selectionAfterBatchRequest,
} from './adminNavigationManage'

describe('admin navigation management helpers', () => {
  it('moves one item without mutating the source list', () => {
    const source = ['a', 'b', 'c']

    expect(moveItem(source, 2, 0)).toEqual(['c', 'a', 'b'])
    expect(source).toEqual(['a', 'b', 'c'])
  })

  it('keeps the list unchanged when the move is outside its bounds', () => {
    expect(moveItem(['a', 'b'], 0, -1)).toEqual(['a', 'b'])
    expect(moveItem(['a', 'b'], 2, 0)).toEqual(['a', 'b'])
  })

  it('compares numeric and string ids as the same stable entity id', () => {
    expect(hasSameEntityOrder([{ id: 1 }, { id: '2' }], [{ id: '1' }, { id: 2 }])).toBe(true)
    expect(hasSameEntityOrder([{ id: 1 }, { id: 2 }], [{ id: 2 }, { id: 1 }])).toBe(false)
  })

  it('builds one normalized sort payload in the current visual order', () => {
    expect(buildSequentialSortPayload([{ id: 9 }, { id: '4' }, { id: 7 }])).toEqual([
      { id: 9, sortOrder: 0 },
      { id: '4', sortOrder: 10 },
      { id: 7, sortOrder: 20 },
    ])
  })

  it('preserves selections outside the current filter while replacing its visible selection', () => {
    expect(mergeScopedSelection(['1', '2', '4'], [1, 2, 3], [2, 3])).toEqual(['4', '2', '3'])
  })

  it('drops selections for rows that no longer exist after a refresh', () => {
    expect(reconcileSelectedKeys(['1', '2', '3'], [1, 3, 4])).toEqual(['1', '3'])
  })

  it('clears a successful batch selection and preserves a failed one', () => {
    expect(selectionAfterBatchRequest(['1', '2'], true)).toEqual([])
    expect(selectionAfterBatchRequest(['1', '2'], false)).toEqual(['1', '2'])
  })

  it('accepts short marks or HTTP(S) icon urls and rejects silent long-text fallbacks', () => {
    expect(isValidNavigationIcon('GH')).toBe(true)
    expect(isValidNavigationIcon('开发')).toBe(true)
    expect(isValidNavigationIcon('https://example.com/icon.png')).toBe(true)
    expect(isValidNavigationIcon('普通长文本图标')).toBe(false)
    expect(isValidNavigationIcon('LONGTEXT')).toBe(false)
    expect(isValidNavigationIcon('javascript:alert(1)')).toBe(false)
  })

  it('renders explicit image urls and handles emoji by Unicode code point', () => {
    expect(navigationIconUrl('https://example.com/icon.png')).toBe('https://example.com/icon.png')
    expect(navigationIconUrl('ordinary-text')).toBe('')
    expect(navigationIconLabel('🇨🇳', '备')).toBe('🇨🇳')
    expect(navigationIconLabel('https://example.com/icon.png', '备')).toBe('备')
    expect(navigationIconLabel('ordinary-text', '备')).toBe('备')
  })
})
