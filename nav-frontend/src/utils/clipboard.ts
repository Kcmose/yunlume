export interface ClipboardWriter {
  writeText(text: string): Promise<void>
}

export type LegacyCopy = (text: string) => boolean
export type CopyTextResult = 'clipboard' | 'legacy' | 'failed'

export async function copyTextWithFallback(
  text: string,
  clipboard: ClipboardWriter | null | undefined,
  legacyCopy: LegacyCopy,
): Promise<CopyTextResult> {
  if (clipboard?.writeText) {
    try {
      await clipboard.writeText(text)
      return 'clipboard'
    } catch {
      // HTTP deployments and denied browser permissions commonly reach the legacy path.
    }
  }
  try {
    return legacyCopy(text) ? 'legacy' : 'failed'
  } catch {
    return 'failed'
  }
}
