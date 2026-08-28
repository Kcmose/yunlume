export const PASSWORD_MIN_LENGTH = 12
export const PASSWORD_MAX_LENGTH = 72
export const PASSWORD_REQUIRED_CHARACTER_CLASSES = 3

export interface PasswordPolicyResult {
  valid: boolean
  lengthValid: boolean
  characterCount: number
  byteLength: number
  whitespaceFree: boolean
  categoryCount: number
  categoriesValid: boolean
  usernameFree: boolean
  differsFromCurrent: boolean
  strength: 'empty' | 'weak' | 'medium' | 'strong'
}

const LOWERCASE_PATTERN = /\p{Lowercase}/u
const UPPERCASE_PATTERN = /\p{Uppercase}/u
const DECIMAL_NUMBER_PATTERN = /\p{Decimal_Number}/u
const LETTER_OR_DECIMAL_NUMBER_PATTERN = /[\p{Letter}\p{Decimal_Number}]/u
// Keep this in sync with AuthServiceImpl.isDisallowedWhitespace. Java's
// whitespace/space-character union additionally includes U+001C-U+001F,
// while ECMAScript's \s additionally includes U+FEFF. U+0085 is rejected by
// both sides as an invisible Unicode spacing control.
const DISALLOWED_WHITESPACE_PATTERN = /[\s\u0085\u001c-\u001f]/u

function countCharacterClasses(characters: string[]): number {
  return [
    characters.some(character => LOWERCASE_PATTERN.test(character)),
    characters.some(character => UPPERCASE_PATTERN.test(character)),
    characters.some(character => DECIMAL_NUMBER_PATTERN.test(character)),
    characters.some(character => !LETTER_OR_DECIMAL_NUMBER_PATTERN.test(character)),
  ].filter(Boolean).length
}

export function evaluatePasswordPolicy(
  password: string,
  username = '',
  currentPassword = '',
): PasswordPolicyResult {
  const characters = Array.from(password)
  const characterCount = characters.length
  const categoryCount = countCharacterClasses(characters)
  const byteLength = new TextEncoder().encode(password).length
  const lengthValid = characterCount >= PASSWORD_MIN_LENGTH
    && characterCount <= PASSWORD_MAX_LENGTH
    && byteLength <= PASSWORD_MAX_LENGTH
  const whitespaceFree = !characters.some(character => DISALLOWED_WHITESPACE_PATTERN.test(character))
  const normalizedUsername = username.toLowerCase()
  const usernameFree = !normalizedUsername
    || !password.toLowerCase().includes(normalizedUsername)
  const differsFromCurrent = !currentPassword || password !== currentPassword
  const categoriesValid = categoryCount >= PASSWORD_REQUIRED_CHARACTER_CLASSES
  const valid = Boolean(password)
    && lengthValid
    && whitespaceFree
    && categoriesValid
    && usernameFree
    && differsFromCurrent

  let strength: PasswordPolicyResult['strength'] = 'empty'
  if (password) {
    if (valid && categoryCount === 4 && characterCount >= 16) strength = 'strong'
    else if (valid) strength = 'medium'
    else strength = 'weak'
  }

  return {
    valid,
    lengthValid,
    characterCount,
    byteLength,
    whitespaceFree,
    categoryCount,
    categoriesValid,
    usernameFree,
    differsFromCurrent,
    strength,
  }
}
