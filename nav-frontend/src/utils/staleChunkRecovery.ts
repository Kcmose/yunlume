const STALE_CHUNK_RELOAD_KEY = 'yunlume:stale-chunk-reload-attempted'

type RecoveryEventTarget = Pick<EventTarget, 'addEventListener' | 'removeEventListener'>
type RecoveryStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

interface StaleChunkRecoveryOptions {
  target?: RecoveryEventTarget
  storage?: RecoveryStorage
  reload?: () => void
}

export function registerStaleChunkRecovery(options: StaleChunkRecoveryOptions = {}) {
  const target = options.target ?? window
  const reload = options.reload ?? (() => window.location.reload())
  let storage: RecoveryStorage

  try {
    storage = options.storage ?? window.sessionStorage
  } catch {
    return () => undefined
  }

  const handlePreloadError = (event: Event) => {
    try {
      if (storage.getItem(STALE_CHUNK_RELOAD_KEY) === '1') return
      storage.setItem(STALE_CHUNK_RELOAD_KEY, '1')
    } catch {
      return
    }

    event.preventDefault()
    reload()
  }

  target.addEventListener('vite:preloadError', handlePreloadError)
  return () => target.removeEventListener('vite:preloadError', handlePreloadError)
}

export function clearStaleChunkRecoveryMarker(
  storage?: RecoveryStorage,
) {
  try {
    (storage ?? window.sessionStorage).removeItem(STALE_CHUNK_RELOAD_KEY)
  } catch {
    // Storage restrictions must not block application startup.
  }
}
