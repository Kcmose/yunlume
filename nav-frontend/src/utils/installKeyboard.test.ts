import { describe, expect, it } from 'vitest'
import { shouldAdvanceInstallOnEnter } from './installKeyboard'

describe('install wizard Enter shortcut', () => {
  it.each(['summary', 'pre', 'button', 'textarea', 'a'])('%s never advances the wizard', (tagName) => {
    expect(shouldAdvanceInstallOnEnter({ tagName })).toBe(false)
  })

  it('does not treat Element Plus combobox internals as a submit input', () => {
    expect(shouldAdvanceInstallOnEnter({
      tagName: 'input',
      inputType: 'text',
      insideCombobox: true,
    })).toBe(false)
  })

  it.each(['text', 'password', 'number'])('allows Enter from an ordinary %s input', (inputType) => {
    expect(shouldAdvanceInstallOnEnter({ tagName: 'input', inputType })).toBe(true)
  })
})
