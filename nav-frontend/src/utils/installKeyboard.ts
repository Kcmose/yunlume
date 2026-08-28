const SUBMITTABLE_INPUT_TYPES = new Set(['text', 'password', 'number'])

export interface InstallEnterTarget {
  tagName: string
  inputType?: string
  insideCombobox?: boolean
}

/** Only ordinary form inputs may use Enter as the wizard's next/submit shortcut. */
export function shouldAdvanceInstallOnEnter(target: InstallEnterTarget): boolean {
  return target.tagName.toLowerCase() === 'input'
    && SUBMITTABLE_INPUT_TYPES.has((target.inputType ?? 'text').toLowerCase())
    && target.insideCombobox !== true
}
