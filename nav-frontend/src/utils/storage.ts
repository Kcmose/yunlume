export const AUTH_ENVELOPE_KEY = 'ilinks_admin_auth_envelope'
export const AUTH_BARRIER_KEY = 'ilinks_admin_auth_barrier'
export const USER_KEY = 'ilinks_admin_user'

const LEGACY_TOKEN_KEY = 'ilinks_admin_token'
const GENERATION_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/

/**
 * The bounded two-key protocol assumes independently generated cryptographic
 * generation and commitment values do not both collide. Detectable reuse is
 * rejected, but localStorage has no atomic CAS, and no protocol can revoke an
 * already durable token across reload when every subsequent write fails.
 */
type Status = 'active' | 'removed'
type Barrier = { v: 1, status: Status, generation: string, commitment: string }
type ActiveEnvelope = Barrier & { status: 'active', token: string }
type RemovedEnvelope = Barrier & { status: 'removed' }
type Envelope = ActiveEnvelope | RemovedEnvelope
export type AuthTokenSnapshot = Readonly<{ token: string, generation: string, commitment: string }>

export class TokenPersistenceError extends Error {
  readonly code = 'AUTH_PERSISTENCE_FAILED'
  readonly phase: string
  readonly storageKey: string | null
  readonly storageError: Readonly<{ category: StorageErrorCategory }> | null

  constructor(phase: string, storageKey: string | null, cause?: unknown) {
    super('Unable to persist the authenticated session')
    this.name = 'TokenPersistenceError'
    this.phase = phase
    this.storageKey = storageKey
    this.storageError = sanitizeStorageError(cause)
  }
}

type StorageErrorCategory = 'quota-exceeded' | 'security' | 'invalid-state' | 'not-supported' | 'readback-mismatch'
const STORAGE_ERROR_CATEGORIES: Readonly<Record<string, StorageErrorCategory>> = Object.freeze({
  QuotaExceededError: 'quota-exceeded',
  SecurityError: 'security',
  InvalidStateError: 'invalid-state',
  NotSupportedError: 'not-supported',
  StorageReadbackMismatchError: 'readback-mismatch',
})

function sanitizeStorageError(cause: unknown): Readonly<{ category: StorageErrorCategory }> | null {
  if (!cause || (typeof cause !== 'object' && typeof cause !== 'function')) return null
  try {
    const candidate = (cause as { name?: unknown }).name
    if (typeof candidate !== 'string') return null
    const category = STORAGE_ERROR_CATEGORIES[candidate]
    return category ? Object.freeze({ category }) : null
  } catch {
    return null
  }
}

function browserStorage(): Storage | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    return null
  }
}

function canonicalBarrier(status: Status, generation: string, commitment: string): string {
  return JSON.stringify({ v: 1, status, generation, commitment })
}

function canonicalActiveEnvelope(generation: string, commitment: string, token: string): string {
  return JSON.stringify({ v: 1, status: 'active', generation, commitment, token })
}

function canonicalRemovedEnvelope(generation: string, commitment: string): string {
  return JSON.stringify({ v: 1, status: 'removed', generation, commitment })
}

function parseBarrier(raw: string | null): Barrier | null {
  if (raw === null) return null
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)
      || parsed.v !== 1
      || (parsed.status !== 'active' && parsed.status !== 'removed')
      || typeof parsed.generation !== 'string'
      || !GENERATION_PATTERN.test(parsed.generation)
      || typeof parsed.commitment !== 'string'
      || !GENERATION_PATTERN.test(parsed.commitment)
      || raw !== canonicalBarrier(parsed.status, parsed.generation, parsed.commitment)) return null
    return { v: 1, status: parsed.status, generation: parsed.generation, commitment: parsed.commitment }
  } catch {
    return null
  }
}

function parseEnvelope(raw: string | null): Envelope | null {
  if (raw === null) return null
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)
      || parsed.v !== 1
      || (parsed.status !== 'active' && parsed.status !== 'removed')
      || typeof parsed.generation !== 'string'
      || !GENERATION_PATTERN.test(parsed.generation)
      || typeof parsed.commitment !== 'string'
      || !GENERATION_PATTERN.test(parsed.commitment)) return null

    if (parsed.status === 'active') {
      if (typeof parsed.token !== 'string' || parsed.token.length === 0
        || raw !== canonicalActiveEnvelope(parsed.generation, parsed.commitment, parsed.token)) return null
      return { v: 1, status: 'active', generation: parsed.generation, commitment: parsed.commitment, token: parsed.token }
    }
    if (raw !== canonicalRemovedEnvelope(parsed.generation, parsed.commitment)) return null
    return { v: 1, status: 'removed', generation: parsed.generation, commitment: parsed.commitment }
  } catch {
    return null
  }
}

function readDurableSnapshot(storage: Storage): AuthTokenSnapshot | null {
  try {
    const rawEnvelope = storage.getItem(AUTH_ENVELOPE_KEY)
    const rawBarrier = storage.getItem(AUTH_BARRIER_KEY)
    const envelope = parseEnvelope(rawEnvelope)
    const barrier = parseBarrier(rawBarrier)
    if (!envelope || !barrier
      || envelope.status !== 'active'
      || barrier.status !== 'active'
      || envelope.generation !== barrier.generation
      || envelope.commitment !== barrier.commitment) return null
    return Object.freeze({
      token: envelope.token,
      generation: envelope.generation,
      commitment: envelope.commitment,
    })
  } catch {
    return null
  }
}

function generation(): string {
  const crypto = globalThis.crypto
  if (!crypto) throw new Error('Cryptographic generation is unavailable')
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  if (typeof crypto.getRandomValues !== 'function') throw new Error('Cryptographic generation is unavailable')
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  return `crypto-${Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')}`
}

function acquireIdentifier(nextIdentifier: () => string, phase: 'generation' | 'commitment'): string {
  let candidate: unknown
  try {
    candidate = nextIdentifier()
  } catch (cause) {
    throw new TokenPersistenceError(phase, null, cause)
  }
  if (typeof candidate !== 'string' || !GENERATION_PATTERN.test(candidate)) {
    throw new TokenPersistenceError(phase, null)
  }
  return candidate
}

function readbackMismatch(): Error {
  const error = new Error('Storage readback did not match the requested value')
  error.name = 'StorageReadbackMismatchError'
  return error
}

function setAndVerify(
  storage: Storage,
  key: string,
  value: string,
  writePhase: string,
  readbackPhase: string,
): void {
  try {
    storage.setItem(key, value)
  } catch (cause) {
    throw new TokenPersistenceError(writePhase, key, cause)
  }
  let persisted: string | null
  try {
    persisted = storage.getItem(key)
  } catch (cause) {
    throw new TokenPersistenceError(readbackPhase, key, cause)
  }
  if (persisted !== value) {
    throw new TokenPersistenceError(readbackPhase, key, readbackMismatch())
  }
}

function verifyValue(storage: Storage, key: string, expected: string, phase: string): void {
  let persisted: string | null
  try {
    persisted = storage.getItem(key)
  } catch (cause) {
    throw new TokenPersistenceError(phase, key, cause)
  }
  if (persisted !== expected) {
    throw new TokenPersistenceError(phase, key, readbackMismatch())
  }
}

function removeLegacyToken(storage: Storage): void {
  try {
    storage.removeItem(LEGACY_TOKEN_KEY)
  } catch (cause) {
    throw new TokenPersistenceError('legacy-token-remove', LEGACY_TOKEN_KEY, cause)
  }
  let persisted: string | null
  try {
    persisted = storage.getItem(LEGACY_TOKEN_KEY)
  } catch (cause) {
    throw new TokenPersistenceError('legacy-token-readback', LEGACY_TOKEN_KEY, cause)
  }
  if (persisted === null) return
  try {
    storage.setItem(LEGACY_TOKEN_KEY, '')
  } catch (cause) {
    throw new TokenPersistenceError('legacy-token-blank-write', LEGACY_TOKEN_KEY, cause)
  }
  try {
    persisted = storage.getItem(LEGACY_TOKEN_KEY)
  } catch (cause) {
    throw new TokenPersistenceError('legacy-token-blank-readback', LEGACY_TOKEN_KEY, cause)
  }
  if (persisted !== '') {
    throw new TokenPersistenceError('legacy-token-blank-readback', LEGACY_TOKEN_KEY, readbackMismatch())
  }
}

function bestEffortRemovedBarrier(storage: Storage, operationGeneration: string, operationCommitment: string): void {
  try {
    const current = storage.getItem(AUTH_BARRIER_KEY)
    const ownRemoved = canonicalBarrier('removed', operationGeneration, operationCommitment)
    const ownActive = canonicalBarrier('active', operationGeneration, operationCommitment)
    if (current === ownRemoved || current === ownActive) {
      setAndVerify(
        storage,
        AUTH_BARRIER_KEY,
        ownRemoved,
        'recovery-barrier-write',
        'recovery-barrier-readback',
      )
    }
  } catch {
    // Recovery is best-effort and must not clobber a concurrent operation.
  }
}

export function createTokenStorage(
  storage: Storage | null,
  nextGeneration: () => string = generation,
  nextCommitment: () => string = generation,
) {
  let failedLogoutState: { envelope: string | null, barrier: string | null } | null = null

  return {
    get(): string {
      return this.getSnapshot()?.token ?? ''
    },

    getSnapshot(): AuthTokenSnapshot | null {
      if (!storage) return null
      if (failedLogoutState) {
        try {
          const envelope = storage.getItem(AUTH_ENVELOPE_KEY)
          const barrierValue = storage.getItem(AUTH_BARRIER_KEY)
          if (envelope === failedLogoutState.envelope && barrierValue === failedLogoutState.barrier) return null
          failedLogoutState = null
        } catch {
          return null
        }
      }
      return readDurableSnapshot(storage)
    },

    set(token: string): true {
      this.setSnapshot(token)
      return true
    },

    setSnapshot(token: string): AuthTokenSnapshot {
      if (!storage || typeof token !== 'string' || token.length === 0) {
        throw new TokenPersistenceError('input-validation', null)
      }
      const operationGeneration = acquireIdentifier(nextGeneration, 'generation')
      const operationCommitment = acquireIdentifier(nextCommitment, 'commitment')
      const removedBarrier = canonicalBarrier('removed', operationGeneration, operationCommitment)
      const activeEnvelope = canonicalActiveEnvelope(operationGeneration, operationCommitment, token)
      const activeBarrier = canonicalBarrier('active', operationGeneration, operationCommitment)

      try {
        const existingEnvelope = storage.getItem(AUTH_ENVELOPE_KEY)
        const existingBarrier = storage.getItem(AUTH_BARRIER_KEY)
        const identity = { generation: operationGeneration, commitment: operationCommitment }
        if ([parseEnvelope(existingEnvelope), parseBarrier(existingBarrier)].some((record) => record
          && record.generation === identity.generation
          && record.commitment === identity.commitment)) {
          throw new TokenPersistenceError('operation-identity-collision', null)
        }
        setAndVerify(storage, AUTH_BARRIER_KEY, removedBarrier, 'removed-barrier-write', 'removed-barrier-readback')
        removeLegacyToken(storage)
        setAndVerify(storage, AUTH_ENVELOPE_KEY, activeEnvelope, 'active-envelope-write', 'active-envelope-readback')
        setAndVerify(storage, AUTH_BARRIER_KEY, activeBarrier, 'active-barrier-write', 'active-barrier-readback')
        verifyValue(storage, AUTH_ENVELOPE_KEY, activeEnvelope, 'final-envelope-readback')
        verifyValue(storage, AUTH_BARRIER_KEY, activeBarrier, 'final-barrier-readback')
        failedLogoutState = null
        // 返回本次写入生成的身份，不能把随后另一标签页提交的快照当成本次结果。
        return Object.freeze({ token, generation: operationGeneration, commitment: operationCommitment })
      } catch (error) {
        if (!(error instanceof TokenPersistenceError && error.phase === 'operation-identity-collision')) {
          bestEffortRemovedBarrier(storage, operationGeneration, operationCommitment)
        }
        if (error instanceof TokenPersistenceError) throw error
        throw new TokenPersistenceError('unexpected', null, error)
      }
    },

    remove(): void {
      if (!storage) return
      let beforeEnvelope: string | null = null
      let beforeBarrier: string | null = null
      try {
        beforeEnvelope = storage.getItem(AUTH_ENVELOPE_KEY)
        beforeBarrier = storage.getItem(AUTH_BARRIER_KEY)
      } catch {
        // Local logout remains authoritative for this adapter.
      }
      failedLogoutState = { envelope: beforeEnvelope, barrier: beforeBarrier }

      let operationGeneration: string
      let operationCommitment: string
      try {
        operationGeneration = acquireIdentifier(nextGeneration, 'generation')
        operationCommitment = acquireIdentifier(nextCommitment, 'commitment')
      } catch {
        return
      }
      const removedBarrier = canonicalBarrier('removed', operationGeneration, operationCommitment)
      const removedEnvelope = canonicalRemovedEnvelope(operationGeneration, operationCommitment)
      let barrierDurable = false
      try {
        setAndVerify(storage, AUTH_BARRIER_KEY, removedBarrier, 'logout-barrier-write', 'logout-barrier-readback')
        barrierDurable = true
      } catch {
        // Logout remains nonthrowing and locally authoritative.
      }
      try {
        setAndVerify(storage, AUTH_ENVELOPE_KEY, removedEnvelope, 'logout-envelope-write', 'logout-envelope-readback')
      } catch {
        // Logout remains nonthrowing and locally authoritative.
      }
      if (barrierDurable) {
        try {
          removeLegacyToken(storage)
        } catch {
          // The fixed legacy key never authorizes reads; cleanup is best-effort on logout.
        }
      }
    },
  }
}

const defaultStorage = browserStorage()
const defaultTokenStorage = createTokenStorage(defaultStorage)
export const tokenStorage = defaultTokenStorage

function sameSnapshot(left: AuthTokenSnapshot | null, right: AuthTokenSnapshot | null): boolean {
  return Boolean(left && right
    && left.token === right.token
    && left.generation === right.generation
    && left.commitment === right.commitment)
}

function canonicalBoundUser<T>(snapshot: AuthTokenSnapshot, user: T): string {
  return JSON.stringify({
    v: 1,
    generation: snapshot.generation,
    commitment: snapshot.commitment,
    user,
  })
}

function parseBoundUser<T>(raw: string | null, snapshot: AuthTokenSnapshot | null): T | null {
  if (raw === null || !snapshot) return null
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)
      || parsed.v !== 1
      || parsed.generation !== snapshot.generation
      || parsed.commitment !== snapshot.commitment
      || !Object.prototype.hasOwnProperty.call(parsed, 'user')
      || !parsed.user || typeof parsed.user !== 'object' || Array.isArray(parsed.user)
      || raw !== canonicalBoundUser(snapshot, parsed.user)) return null
    return parsed.user as T
  } catch {
    return null
  }
}

export const boundUserStorage = {
  get<T>(snapshot: AuthTokenSnapshot | null = tokenStorage.getSnapshot()): T | null {
    if (!defaultStorage || !sameSnapshot(snapshot, tokenStorage.getSnapshot())) return null
    try {
      return parseBoundUser<T>(defaultStorage.getItem(USER_KEY), snapshot)
    } catch {
      return null
    }
  },
  set<T>(user: T, snapshot: AuthTokenSnapshot | null = tokenStorage.getSnapshot()): boolean {
    if (!defaultStorage || !snapshot || !sameSnapshot(snapshot, tokenStorage.getSnapshot())) return false
    let value: string
    try {
      value = canonicalBoundUser(snapshot, user)
    } catch {
      return false
    }
    try {
      defaultStorage.setItem(USER_KEY, value)
      if (defaultStorage.getItem(USER_KEY) !== value) return false
      return sameSnapshot(snapshot, tokenStorage.getSnapshot())
    } catch {
      return false
    }
  },
  remove(): void {
    if (!defaultStorage) return
    try { defaultStorage.removeItem(USER_KEY) } catch { /* best-effort metadata cleanup */ }
  },
}

export type AuthStorageSnapshot = { key: string, auth: AuthTokenSnapshot | null }
type AuthStorageListener = (snapshot: AuthStorageSnapshot) => void
const AUTH_EVENT_KEYS = new Set([AUTH_ENVELOPE_KEY, AUTH_BARRIER_KEY, LEGACY_TOKEN_KEY, USER_KEY])
const authStorageListeners = new Set<AuthStorageListener>()
let storageEventsInstalled = false

function handleAuthStorageEvent(event: StorageEvent): void {
  if (typeof event.key !== 'string' || !AUTH_EVENT_KEYS.has(event.key)) return
  const storage = browserStorage()
  if (event.storageArea && storage && event.storageArea !== storage) return
  const snapshot = { key: event.key, auth: tokenStorage.getSnapshot() }
  for (const listener of [...authStorageListeners]) listener(snapshot)
}

function installStorageEvents(): void {
  if (storageEventsInstalled || typeof window === 'undefined') return
  window.addEventListener('storage', handleAuthStorageEvent)
  storageEventsInstalled = true
}

function uninstallStorageEvents(): void {
  if (!storageEventsInstalled || typeof window === 'undefined') return
  window.removeEventListener('storage', handleAuthStorageEvent)
  storageEventsInstalled = false
}

export function subscribeAuthStorage(listener: AuthStorageListener): () => void {
  authStorageListeners.add(listener)
  installStorageEvents()
  let subscribed = true
  return () => {
    if (!subscribed) return
    subscribed = false
    authStorageListeners.delete(listener)
    if (authStorageListeners.size === 0) uninstallStorageEvents()
  }
}

if (import.meta.hot) {
  import.meta.hot.dispose(() => {
    authStorageListeners.clear()
    uninstallStorageEvents()
  })
}

function read(key: string): string | null {
  const storage = browserStorage()
  if (!storage) return null
  try {
    return storage.getItem(key)
  } catch {
    return null
  }
}

function write(key: string, value: string): void {
  const storage = browserStorage()
  if (!storage) return
  try {
    storage.setItem(key, value)
  } catch {
    // Ordinary metadata persistence is best-effort.
  }
}

function remove(key: string): void {
  const storage = browserStorage()
  if (!storage) return
  try {
    storage.removeItem(key)
  } catch {
    // Ordinary metadata cleanup is best-effort.
  }
}

export const jsonStorage = {
  get<T>(key: string): T | null {
    const raw = read(key)
    if (!raw) return null
    try {
      return JSON.parse(raw) as T
    } catch {
      return null
    }
  },
  set<T>(key: string, value: T): void {
    try {
      write(key, JSON.stringify(value))
    } catch {
      // Circular or otherwise non-serializable values are not persisted.
    }
  },
  remove: (key: string): void => remove(key),
}
