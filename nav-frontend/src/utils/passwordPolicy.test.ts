import { describe, expect, it } from 'vitest'
import {
  evaluatePasswordPolicy,
  PASSWORD_MAX_LENGTH,
  PASSWORD_MIN_LENGTH,
  PASSWORD_REQUIRED_CHARACTER_CLASSES,
} from './passwordPolicy'

describe('administrator password policy', () => {
  it('accepts a password with valid length and three character classes', () => {
    const result = evaluatePasswordPolicy('SafePassword123', 'admin')

    expect(result.valid).toBe(true)
    expect(result.categoryCount).toBe(3)
    expect(result.strength).toBe('medium')
  })

  it('enforces a 12-character minimum and a 72-byte maximum', () => {
    expect(PASSWORD_MIN_LENGTH).toBe(12)
    expect(PASSWORD_MAX_LENGTH).toBe(72)
    expect(evaluatePasswordPolicy('Aa1!'.padEnd(12, 'x')).lengthValid).toBe(true)
    expect(evaluatePasswordPolicy('Aa1!'.padEnd(72, 'x')).lengthValid).toBe(true)
    expect(evaluatePasswordPolicy('Aa1!short').lengthValid).toBe(false)
    expect(evaluatePasswordPolicy('Aa1!'.padEnd(73, 'x')).lengthValid).toBe(false)
  })

  it('counts Unicode code points rather than UTF-16 code units', () => {
    const result = evaluatePasswordPolicy(`Aa${'😀'.repeat(5)}`)

    expect(result.characterCount).toBe(7)
    expect(result.categoryCount).toBe(3)
    expect(result.lengthValid).toBe(false)
    expect(result.valid).toBe(false)
  })

  it('rejects a multi-byte password that exceeds the BCrypt 72-byte limit', () => {
    const result = evaluatePasswordPolicy(`Aa1!${'密'.repeat(23)}`)

    expect(result.byteLength).toBe(73)
    expect(result.lengthValid).toBe(false)
    expect(result.valid).toBe(false)
  })

  it('rejects all whitespace characters', () => {
    const result = evaluatePasswordPolicy('Safe Pass123!', 'admin')
    const informationSeparator = evaluatePasswordPolicy('Safe\u001cPass123!', 'admin')

    expect(result.whitespaceFree).toBe(false)
    expect(result.valid).toBe(false)
    expect(informationSeparator.whitespaceFree).toBe(false)
    expect(informationSeparator.valid).toBe(false)
  })

  it('requires at least three of lower, upper, digit and symbol classes', () => {
    expect(PASSWORD_REQUIRED_CHARACTER_CLASSES).toBe(3)
    expect(evaluatePasswordPolicy('onlylowercase').categoryCount).toBe(1)
    expect(evaluatePasswordPolicy('lowerandUPPER').categoryCount).toBe(2)
    expect(evaluatePasswordPolicy('lowerUPPER123').categoriesValid).toBe(true)
  })

  it('uses Unicode letter and decimal-number classes consistently with the backend', () => {
    const unicodeLetters = evaluatePasswordPolicy('ÉÉÉééé123456')
    const uncasedLetters = evaluatePasswordPolicy('中文1234567890')

    expect(unicodeLetters.characterCount).toBe(12)
    expect(unicodeLetters.categoryCount).toBe(3)
    expect(unicodeLetters.valid).toBe(true)
    expect(uncasedLetters.categoryCount).toBe(1)
    expect(uncasedLetters.valid).toBe(false)
  })

  it('rejects the username without regard to letter case', () => {
    const result = evaluatePasswordPolicy('PrefixAdMiN123!', 'admin')

    expect(result.usernameFree).toBe(false)
    expect(result.valid).toBe(false)
  })

  it('rejects reusing the current password', () => {
    const password = 'Safe-Password123'
    const result = evaluatePasswordPolicy(password, 'admin', password)

    expect(result.differsFromCurrent).toBe(false)
    expect(result.valid).toBe(false)
  })

  it('marks a longer four-class password as strong', () => {
    expect(evaluatePasswordPolicy('Very-Strong-Secret123!', 'admin').strength).toBe('strong')
  })
})
