const DEFAULT_RETRY_DELAYS = [300, 900, 1800] as const
const RETRYABLE_STATUS_CODES = new Set([502, 503, 504])

type ErrorWithHttpContext = {
  status?: unknown
  response?: { status?: unknown }
  isAxiosError?: unknown
}

export interface PublicRequestRetryOptions {
  delays?: readonly number[]
  sleep?: (milliseconds: number) => Promise<void>
}

function httpStatus(error: unknown): number | undefined {
  if (!error || typeof error !== 'object') return undefined
  const candidate = error as ErrorWithHttpContext
  const status = candidate.status ?? candidate.response?.status
  return typeof status === 'number' ? status : undefined
}

export function isRetryablePublicRequestError(error: unknown): boolean {
  const status = httpStatus(error)
  if (status !== undefined) return RETRYABLE_STATUS_CODES.has(status)
  return Boolean(error && typeof error === 'object' && (error as ErrorWithHttpContext).isAxiosError)
}

export async function withPublicRequestRetry<T>(
  request: () => Promise<T>,
  options: PublicRequestRetryOptions = {},
): Promise<T> {
  const delays = options.delays ?? DEFAULT_RETRY_DELAYS
  const sleep = options.sleep ?? ((milliseconds: number) =>
    new Promise<void>((resolve) => globalThis.setTimeout(resolve, milliseconds)))

  for (let attempt = 0; ; attempt += 1) {
    try {
      return await request()
    } catch (error) {
      if (attempt >= delays.length || !isRetryablePublicRequestError(error)) throw error
      await sleep(delays[attempt]!)
    }
  }
}
