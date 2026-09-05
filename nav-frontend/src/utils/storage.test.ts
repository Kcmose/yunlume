import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const LEGACY_TOKEN = 'ilinks_admin_token'
const USER_KEY_FOR_TEST = 'ilinks_admin_user'
const LEGACY_STATE = 'ilinks_admin_token_state'
const ENVELOPE = 'ilinks_admin_auth_envelope'
const BARRIER = 'ilinks_admin_auth_barrier'
const OLD_JOURNAL = '__ilinks_auth_operation_old-generation'
const OLD_GENERATION_TOKEN = 'ilinks_admin_token:old-generation'

const activeEnvelope = (generation: string, token: string, commitment = generation) => JSON.stringify({
  v: 1,
  status: 'active',
  generation,
  commitment,
  token,
})
const removedEnvelope = (generation: string, commitment = generation) => JSON.stringify({
  v: 1,
  status: 'removed',
  generation,
  commitment,
})
const barrier = (status: 'active' | 'removed', generation: string, commitment = generation) => JSON.stringify({
  v: 1,
  status,
  generation,
  commitment,
})

const boundUser = (generation: string, commitment: string, user: unknown) => JSON.stringify({
  v: 1,
  generation,
  commitment,
  user,
})

type MutableStorage = Storage & { values: Map<string, string> }

function storage(values = new Map<string, string>()): MutableStorage {
  return {
    values,
    get length() { return values.size },
    clear: vi.fn(() => values.clear()),
    getItem: vi.fn((key: string) => values.get(key) ?? null),
    key: vi.fn((index: number) => [...values.keys()][index] ?? null),
    removeItem: vi.fn((key: string) => { values.delete(key) }),
    setItem: vi.fn((key: string, value: string) => { values.set(key, value) }),
  }
}

async function load(local: Storage, crypto: Crypto | object = { randomUUID: () => 'generation-one' }) {
  vi.stubGlobal('localStorage', local)
  vi.stubGlobal('crypto', crypto)
  vi.resetModules()
  return import('./storage')
}

describe('bounded canonical auth storage', () => {
  beforeEach(() => vi.stubGlobal('crypto', { randomUUID: vi.fn(() => 'generation-one') }))
  afterEach(() => vi.unstubAllGlobals())

  it('returns empty ordinary JSON data after a storage read failure', async () => {
    const local = storage(new Map([[USER_KEY_FOR_TEST, '{"id":1}']]))
    const { jsonStorage } = await load(local)
    expect(jsonStorage.get(USER_KEY_FOR_TEST)).toEqual({ id: 1 })
    ;(local.getItem as ReturnType<typeof vi.fn>).mockImplementation(() => { throw new DOMException('blocked') })
    expect(jsonStorage.get(USER_KEY_FOR_TEST)).toBeNull()
  })

  it('persists one canonical envelope and one canonical barrier, with token material only in the envelope', async () => {
    const local = storage()
    const { tokenStorage } = await load(local)

    expect(tokenStorage.set('secret-token')).toBe(true)

    expect(local.values).toEqual(new Map([
      [BARRIER, barrier('active', 'generation-one')],
      [ENVELOPE, activeEnvelope('generation-one', 'secret-token')],
    ]))
    expect([...local.values.values()].filter((value) => value.includes('secret-token'))).toHaveLength(1)
    expect(tokenStorage.get()).toBe('secret-token')
  })

  it.each([
    ['duplicate envelope key', '{"v":1,"status":"active","generation":"good","token":"first","token":"second"}', barrier('active', 'good')],
    ['duplicate barrier key', activeEnvelope('good', 'secret'), '{"v":1,"status":"active","generation":"good","generation":"other"}'],
    ['unknown envelope field', '{"v":1,"status":"active","generation":"good","token":"secret","extra":true}', barrier('active', 'good')],
    ['unknown barrier field', activeEnvelope('good', 'secret'), '{"v":1,"status":"active","generation":"good","extra":true}'],
    ['noncanonical envelope order', '{"token":"secret","generation":"good","status":"active","v":1}', barrier('active', 'good')],
    ['noncanonical barrier whitespace', activeEnvelope('good', 'secret'), '{ "v":1,"status":"active","generation":"good"}'],
    ['bad version', '{"v":2,"status":"active","generation":"good","token":"secret"}', barrier('active', 'good')],
    ['bad status', '{"v":1,"status":"pending","generation":"good","token":"secret"}', barrier('active', 'good')],
    ['bad generation', activeEnvelope('bad generation', 'secret'), barrier('active', 'bad generation')],
    ['empty token', activeEnvelope('good', ''), barrier('active', 'good')],
    ['token on removed envelope', '{"v":1,"status":"removed","generation":"good","token":"secret"}', barrier('removed', 'good')],
    ['malformed JSON', '{', barrier('active', 'good')],
    ['generation mismatch', activeEnvelope('good', 'secret'), barrier('active', 'other')],
    ['barrier removed', activeEnvelope('good', 'secret'), barrier('removed', 'good')],
  ])('rejects %s', async (_label, envelopeValue, barrierValue) => {
    const local = storage(new Map([[ENVELOPE, envelopeValue], [BARRIER, barrierValue]]))
    const { tokenStorage } = await load(local)
    expect(tokenStorage.get()).toBe('')
  })

  it.each(['envelope', 'barrier'] as const)('returns empty when the %s read throws', async (failedKey) => {
    const local = storage(new Map([
      [ENVELOPE, activeEnvelope('good', 'secret')],
      [BARRIER, barrier('active', 'good')],
    ]))
    ;(local.getItem as ReturnType<typeof vi.fn>).mockImplementation((key: string) => {
      if (key === (failedKey === 'envelope' ? ENVELOPE : BARRIER)) throw new DOMException('blocked')
      return local.values.get(key) ?? null
    })
    const { tokenStorage } = await load(local)
    expect(tokenStorage.get()).toBe('')
  })

  it.each([
    ['legacy raw token only', [[LEGACY_TOKEN, 'raw-token']]],
    ['raw token beside valid protocol', [[LEGACY_TOKEN, 'raw-token'], [ENVELOPE, activeEnvelope('good', 'secret')]]],
    ['active envelope without barrier', [[ENVELOPE, activeEnvelope('good', 'secret')]]],
    ['active barrier without envelope', [[BARRIER, barrier('active', 'good')]]],
  ] as Array<[string, string[][]]>)('rejects %s', async (_label, entries) => {
    const local = storage(new Map(entries as Array<[string, string]>))
    const { tokenStorage } = await load(local)
    expect(tokenStorage.get()).toBe('')
  })

  it('cleans only the released fixed legacy token without enumerating or deleting unrelated historical-looking keys', async () => {
    const unrelatedMalformed = '__ilinks_auth_operation_bad generation'
    const local = storage(new Map([
      [LEGACY_TOKEN, 'old-secret'],
      [LEGACY_STATE, 'old-state'],
      [OLD_JOURNAL, 'malformed-unreleased-record'],
      [OLD_GENERATION_TOKEN, 'unreleased-secret'],
      [unrelatedMalformed, 'attacker-owned'],
    ]))
    Object.defineProperty(local, 'length', {
      get: vi.fn(() => { throw new Error('storage enumeration is forbidden') }),
    })
    ;(local.key as ReturnType<typeof vi.fn>).mockImplementation(() => {
      throw new Error('storage enumeration is forbidden')
    })
    const removals: string[] = []
    ;(local.removeItem as ReturnType<typeof vi.fn>).mockImplementation((key: string) => {
      expect(local.values.get(BARRIER)).toBe(barrier('removed', 'generation-one'))
      removals.push(key)
      local.values.delete(key)
    })
    const { tokenStorage } = await load(local)

    tokenStorage.set('new-secret')

    expect(removals).toEqual([LEGACY_TOKEN])
    expect(local.key).not.toHaveBeenCalled()
    expect(local.values.get(LEGACY_STATE)).toBe('old-state')
    expect(local.values.get(OLD_JOURNAL)).toBe('malformed-unreleased-record')
    expect(local.values.get(OLD_GENERATION_TOKEN)).toBe('unreleased-secret')
    expect(local.values.get(unrelatedMalformed)).toBe('attacker-owned')
    expect(tokenStorage.get()).toBe('new-secret')
  })

  it('fails explicitly when the legacy raw token cannot be removed or safely blanked', async () => {
    const local = storage(new Map([[LEGACY_TOKEN, 'old-secret']]))
    ;(local.removeItem as ReturnType<typeof vi.fn>).mockImplementation(() => {})
    ;(local.setItem as ReturnType<typeof vi.fn>).mockImplementation((key: string, value: string) => {
      if (key !== LEGACY_TOKEN) local.values.set(key, value)
    })
    const { tokenStorage, TokenPersistenceError } = await load(local)

    expect(() => tokenStorage.set('new-secret')).toThrow(TokenPersistenceError)
    expect(tokenStorage.get()).toBe('')
    expect(local.values.get(BARRIER)).toMatch(/"status":"removed"/)
  })

  it('exposes only sanitized storage error metadata without retaining secret exception data', async () => {
    const local = storage()
    const token = 'secret-token-never-public'
    const original = Object.assign(
      new Error(`quota rejected ${token}`),
      { name: 'QuotaExceededError', code: token, nested: { token } },
    )
    ;(local.setItem as ReturnType<typeof vi.fn>).mockImplementation((key: string, value: string) => {
      if (key === ENVELOPE) throw original
      local.values.set(key, value)
    })
    const { tokenStorage, TokenPersistenceError } = await load(local)

    let failure: unknown
    try {
      tokenStorage.set(token)
    } catch (error) {
      failure = error
    }

    expect(failure).toBeInstanceOf(TokenPersistenceError)
    expect(failure).toMatchObject({
      phase: 'active-envelope-write',
      storageKey: ENVELOPE,
      storageError: { category: 'quota-exceeded' },
    })
    expect(failure).not.toHaveProperty('cause')
    expect(failure).not.toHaveProperty('originalCause')
    const publicValues = [
      String(failure),
      JSON.stringify(failure),
      JSON.stringify(Object.getOwnPropertyDescriptors(failure)),
    ].join('\n')
    expect(publicValues).not.toContain(token)
  })

  it('maps only recognized exception names to closed internal categories and retains no hostile fields', async () => {
    const local = storage()
    const token = 'recursive-secret-token'
    const original = {
      name: 'QuotaExceededError',
      message: `blocked ${token}`,
      code: 22,
      custom: { message: token, code: token, nested: { token } },
    }
    ;(local.setItem as ReturnType<typeof vi.fn>).mockImplementation((key: string, value: string) => {
      if (key === ENVELOPE) throw original
      local.values.set(key, value)
    })
    const { tokenStorage } = await load(local)

    let failure: unknown
    try { tokenStorage.set(token) } catch (error) { failure = error }

    expect(failure).toMatchObject({
      phase: 'active-envelope-write',
      storageKey: ENVELOPE,
      storageError: { category: 'quota-exceeded' },
    })
    expect((failure as { storageError: unknown }).storageError).toEqual({ category: 'quota-exceeded' })
    const inspect = (value: unknown, seen = new Set<unknown>()): string[] => {
      if (value === null || (typeof value !== 'object' && typeof value !== 'function')) return [String(value)]
      if (seen.has(value)) return []
      seen.add(value)
      return Object.getOwnPropertyNames(value).flatMap((key) => [
        key,
        ...inspect(Object.getOwnPropertyDescriptor(value, key)?.value, seen),
      ])
    }
    expect(inspect(failure).join('\n')).not.toContain(token)
    expect(inspect(failure)).not.toContain('22')
  })

  it('retains a non-secret typed cause for a readback mismatch', async () => {
    const local = storage()
    ;(local.getItem as ReturnType<typeof vi.fn>).mockImplementation((key: string) => {
      if (key === ENVELOPE) return 'tampered'
      return local.values.get(key) ?? null
    })
    const { tokenStorage, TokenPersistenceError } = await load(local)

    let failure: unknown
    try {
      tokenStorage.set('secret-token')
    } catch (error) {
      failure = error
    }

    expect(failure).toBeInstanceOf(TokenPersistenceError)
    expect(failure).toMatchObject({
      phase: 'active-envelope-readback',
      storageKey: ENVELOPE,
      storageError: {
        category: 'readback-mismatch',
      },
    })
    expect(String(failure)).not.toContain('secret-token')
  })

  it('retains a final verification read exception with its exact key and phase', async () => {
    const local = storage()
    const original = new DOMException('storage became unavailable', 'SecurityError')
    let envelopeReads = 0
    ;(local.getItem as ReturnType<typeof vi.fn>).mockImplementation((key: string) => {
      if (key === ENVELOPE) {
        envelopeReads += 1
        if (envelopeReads === 3) throw original
      }
      return local.values.get(key) ?? null
    })
    const { tokenStorage, TokenPersistenceError } = await load(local)

    let failure: unknown
    try {
      tokenStorage.set('secret-token')
    } catch (error) {
      failure = error
    }

    expect(failure).toBeInstanceOf(TokenPersistenceError)
    expect(failure).toMatchObject({
      phase: 'final-envelope-readback',
      storageKey: ENVELOPE,
      storageError: { category: 'security' },
    })
    expect(String(failure)).not.toContain('secret-token')
  })

  it('wraps a throwing generation provider as a typed generation failure', async () => {
    const local = storage()
    const module = await load(local)
    const adapter = module.createTokenStorage(local, () => { throw new Error('secret-token-in-generator') })

    expect(() => adapter.set('secret-token')).toThrowError(expect.objectContaining({
      code: 'AUTH_PERSISTENCE_FAILED',
      phase: 'generation',
      storageKey: null,
    }))
    expect(() => adapter.remove()).not.toThrow()
    expect(adapter.get()).toBe('')
  })

  it.each([undefined, null, 7, {}, 'bad generation'])(
    'rejects a non-string or malformed generation provider result (%s)',
    async (candidate) => {
      const local = storage()
      const module = await load(local)
      const adapter = module.createTokenStorage(local, () => candidate as string)

      expect(() => adapter.set('secret-token')).toThrowError(expect.objectContaining({
        code: 'AUTH_PERSISTENCE_FAILED',
        phase: 'generation',
      }))
      expect(() => adapter.remove()).not.toThrow()
      expect(adapter.get()).toBe('')
    },
  )

  it('fails with a typed generation error when no cryptographic generator is available', async () => {
    const local = storage()
    const { tokenStorage } = await load(local, {})

    expect(() => tokenStorage.set('secret-token')).toThrowError(expect.objectContaining({
      code: 'AUTH_PERSISTENCE_FAILED',
      phase: 'generation',
    }))
    expect(() => tokenStorage.remove()).not.toThrow()
    expect(tokenStorage.get()).toBe('')
  })

  it.each([
    'removed barrier write',
    'removed barrier readback',
    'legacy cleanup',
    'active envelope write',
    'active envelope readback',
    'active barrier write',
    'active barrier readback',
  ] as const)('fails login closed with an explicit typed error on %s failure', async (failure) => {
    const local = storage(new Map([[LEGACY_TOKEN, 'old-secret']]))
    let barrierWrites = 0
    let barrierReads = 0
    let envelopeReads = 0
    ;(local.setItem as ReturnType<typeof vi.fn>).mockImplementation((key: string, value: string) => {
      if (key === BARRIER) {
        barrierWrites += 1
        if ((failure === 'removed barrier write' && barrierWrites === 1)
          || (failure === 'active barrier write' && value.includes('"active"'))) throw new DOMException('quota')
      }
      if (key === ENVELOPE && failure === 'active envelope write') throw new DOMException('quota')
      local.values.set(key, value)
    })
    ;(local.getItem as ReturnType<typeof vi.fn>).mockImplementation((key: string) => {
      if (key === BARRIER) {
        barrierReads += 1
        if ((failure === 'removed barrier readback' && barrierReads === 2)
          || (failure === 'active barrier readback' && local.values.get(key)?.includes('"active"'))) return 'tampered'
      }
      if (key === ENVELOPE) {
        envelopeReads += 1
        if (failure === 'active envelope readback' && envelopeReads === 2) return 'tampered'
      }
      return local.values.get(key) ?? null
    })
    ;(local.removeItem as ReturnType<typeof vi.fn>).mockImplementation((key: string) => {
      if (key === LEGACY_TOKEN && failure === 'legacy cleanup') return
      local.values.delete(key)
    })
    if (failure === 'legacy cleanup') {
      const setItem = local.setItem as ReturnType<typeof vi.fn>
      const implementation = setItem.getMockImplementation()!
      setItem.mockImplementation((key: string, value: string) => {
        if (key === LEGACY_TOKEN) return
        implementation(key, value)
      })
    }
    const { tokenStorage, TokenPersistenceError } = await load(local)

    expect(() => tokenStorage.set('new-secret')).toThrow(TokenPersistenceError)
    expect(tokenStorage.get()).toBe('')
    if (failure !== 'active barrier readback') {
      expect(local.values.get(BARRIER) ?? '').not.toContain('"status":"active"')
    }
  })

  it.each(['removed barrier write', 'removed barrier readback', 'removed envelope write', 'removed envelope readback'] as const)(
    'keeps logout nonthrowing and locally empty on %s failure',
    async (failure) => {
      const local = storage(new Map([
        [ENVELOPE, activeEnvelope('old', 'secret')],
        [BARRIER, barrier('active', 'old')],
      ]))
      let removedBarrierReads = 0
      let removedEnvelopeReads = 0
      ;(local.setItem as ReturnType<typeof vi.fn>).mockImplementation((key: string, value: string) => {
        if (failure === 'removed barrier write' && key === BARRIER && value.includes('"removed"')) throw new DOMException('quota')
        if (failure === 'removed envelope write' && key === ENVELOPE && value.includes('"removed"')) throw new DOMException('quota')
        local.values.set(key, value)
      })
      ;(local.getItem as ReturnType<typeof vi.fn>).mockImplementation((key: string) => {
        if (key === BARRIER && local.values.get(key)?.includes('"removed"')) {
          removedBarrierReads += 1
          if (failure === 'removed barrier readback' && removedBarrierReads === 1) return 'tampered'
        }
        if (key === ENVELOPE && local.values.get(key)?.includes('"removed"')) {
          removedEnvelopeReads += 1
          if (failure === 'removed envelope readback' && removedEnvelopeReads === 1) return 'tampered'
        }
        return local.values.get(key) ?? null
      })
      const { tokenStorage } = await load(local, { randomUUID: () => 'logout' })

      expect(() => tokenStorage.remove()).not.toThrow()
      expect(tokenStorage.get()).toBe('')

      const reloaded = await load(local)
      expect(reloaded.tokenStorage.get()).toBe('')
    },
  )

  it('accepts the fundamental boundary when both logout writes fail while still clearing this context', async () => {
    const local = storage(new Map([
      [ENVELOPE, activeEnvelope('old', 'secret')],
      [BARRIER, barrier('active', 'old')],
    ]))
    ;(local.setItem as ReturnType<typeof vi.fn>).mockImplementation(() => { throw new DOMException('blocked') })
    const { tokenStorage } = await load(local)
    tokenStorage.remove()
    expect(tokenStorage.get()).toBe('')

    const reloaded = await load(local)
    expect(reloaded.tokenStorage.get()).toBe('secret')
  })

  it('documents the physical failed-relogin boundary when every persistent write fails', async () => {
    const local = storage(new Map([
      [ENVELOPE, activeEnvelope('old', 'old-token')],
      [BARRIER, barrier('active', 'old')],
    ]))
    const module = await load(local)
    ;(local.setItem as ReturnType<typeof vi.fn>).mockImplementation(() => { throw new DOMException('blocked') })

    expect(() => module.tokenStorage.set('new-token')).toThrow(module.TokenPersistenceError)
    module.tokenStorage.remove()
    expect(module.tokenStorage.get()).toBe('')

    ;(local.setItem as ReturnType<typeof vi.fn>).mockImplementation((key: string, value: string) => {
      local.values.set(key, value)
    })
    const reloaded = await load(local)
    expect(reloaded.tokenStorage.get()).toBe('old-token')
  })

  it('rereads the canonical pair on bounded storage events and never trusts newValue', async () => {
    const local = storage(new Map([
      [ENVELOPE, activeEnvelope('old', 'old-token')],
      [BARRIER, barrier('active', 'old')],
    ]))
    const fakeWindow = new EventTarget()
    const add = vi.spyOn(fakeWindow, 'addEventListener')
    const remove = vi.spyOn(fakeWindow, 'removeEventListener')
    vi.stubGlobal('window', fakeWindow)
    const module = await load(local)
    const snapshots: Array<{ key: string, auth: { token: string, generation: string, commitment: string } | null }> = []
    const unsubscribeA = module.subscribeAuthStorage((snapshot) => snapshots.push(snapshot))
    const unsubscribeB = module.subscribeAuthStorage(() => undefined)

    fakeWindow.dispatchEvent(Object.assign(new Event('storage'), {
      key: ENVELOPE,
      newValue: activeEnvelope('forged', 'forged-token'),
      storageArea: local,
    }))
    expect(snapshots.at(-1)).toEqual({
      key: ENVELOPE,
      auth: { token: 'old-token', generation: 'old', commitment: 'old' },
    })

    local.values.set(BARRIER, barrier('removed', 'logout'))
    fakeWindow.dispatchEvent(Object.assign(new Event('storage'), {
      key: BARRIER,
      newValue: barrier('active', 'forged'),
      storageArea: local,
    }))
    expect(snapshots.at(-1)).toEqual({ key: BARRIER, auth: null })

    fakeWindow.dispatchEvent(Object.assign(new Event('storage'), { key: 'unrelated', storageArea: local }))
    expect(snapshots).toHaveLength(2)
    expect(add).toHaveBeenCalledOnce()
    unsubscribeA()
    unsubscribeB()
    expect(remove).toHaveBeenCalledOnce()
  })

  it('never accepts a raw overwrite and remains correct after module reload', async () => {
    const local = storage()
    let module = await load(local)
    module.tokenStorage.set('secret')
    local.values.set(LEGACY_TOKEN, 'attacker-token')
    expect(module.tokenStorage.get()).toBe('secret')

    module = await load(local)
    expect(module.tokenStorage.get()).toBe('secret')
  })

  it('lets the last fully completed login win across two persistent tab adapters', async () => {
    const local = storage()
    const module = await load(local)
    const tabA = module.createTokenStorage(local, () => 'tab-a', () => 'commitment-a')
    const tabB = module.createTokenStorage(local, () => 'tab-b', () => 'commitment-b')
    const originalSet = local.setItem as ReturnType<typeof vi.fn>
    let runTabB = true
    originalSet.mockImplementation((key: string, value: string) => {
      local.values.set(key, value)
      if (runTabB && key === ENVELOPE && value === activeEnvelope('tab-a', 'token-a', 'commitment-a')) {
        runTabB = false
        tabB.set('token-b')
      }
    })

    expect(() => tabA.set('token-a')).toThrow(module.TokenPersistenceError)

    expect(tabA.get()).toBe('token-b')
    expect(tabB.get()).toBe('token-b')
    expect(local.values.size).toBe(2)
  })

  it('does not let one operation certify another when generations are reused', async () => {
    const local = storage()
    const module = await load(local)
    const tabA = module.createTokenStorage(local, () => 'same-generation', () => 'commitment-a')
    const tabB = module.createTokenStorage(local, () => 'same-generation', () => 'commitment-b')
    const originalSet = local.setItem as ReturnType<typeof vi.fn>
    let injected = false
    originalSet.mockImplementation((key: string, value: string) => {
      local.values.set(key, value)
      if (!injected && key === ENVELOPE && value === activeEnvelope('same-generation', 'token-a', 'commitment-a')) {
        injected = true
        tabB.set('token-b')
      }
    })

    expect(() => tabA.set('token-a')).toThrow(module.TokenPersistenceError)
    expect(tabA.getSnapshot()).toEqual({
      token: 'token-b',
      generation: 'same-generation',
      commitment: 'commitment-b',
    })
    expect(local.values.get(BARRIER)).toBe(barrier('active', 'same-generation', 'commitment-b'))
  })

  it('fails closed when an exact cryptographic operation identity is detectably reused', async () => {
    const local = storage(new Map([
      [ENVELOPE, activeEnvelope('same', 'old-token', 'same-commitment')],
      [BARRIER, barrier('active', 'same', 'same-commitment')],
    ]))
    const module = await load(local)
    const adapter = module.createTokenStorage(local, () => 'same', () => 'same-commitment')

    expect(() => adapter.set('new-token')).toThrowError(expect.objectContaining({ phase: 'operation-identity-collision' }))
    expect(adapter.get()).toBe('old-token')
  })

  it('rejects an ABA barrier replay for the same generation but an earlier commitment', async () => {
    const local = storage(new Map([
      [ENVELOPE, activeEnvelope('reused', 'new-token', 'commitment-b')],
      [BARRIER, barrier('active', 'reused', 'commitment-a')],
    ]))
    const { tokenStorage } = await load(local)
    expect(tokenStorage.getSnapshot()).toBeNull()

    local.values.set(BARRIER, barrier('active', 'reused', 'commitment-b'))
    expect(tokenStorage.getSnapshot()).toEqual({
      token: 'new-token',
      generation: 'reused',
      commitment: 'commitment-b',
    })
  })

  it('accepts bound user metadata only for the exact current auth snapshot', async () => {
    const local = storage(new Map([
      [ENVELOPE, activeEnvelope('current', 'secret', 'commit-current')],
      [BARRIER, barrier('active', 'current', 'commit-current')],
      [USER_KEY_FOR_TEST, boundUser('old', 'commit-old', { id: 1 })],
    ]))
    const { boundUserStorage, tokenStorage } = await load(local)
    const snapshot = tokenStorage.getSnapshot()

    expect(boundUserStorage.get(snapshot)).toBeNull()
    expect(boundUserStorage.set({ id: 2 }, snapshot)).toBe(true)
    expect(boundUserStorage.get(snapshot)).toEqual({ id: 2 })
    expect(local.values.get(USER_KEY_FOR_TEST)).not.toContain('secret')
  })

  it('reloads safely between token and bound-user writes without exposing stale user metadata', async () => {
    const local = storage(new Map([[USER_KEY_FOR_TEST, boundUser('old', 'old-commitment', { id: 1 })]]))
    const writerModule = await load(local)
    const writer = writerModule.createTokenStorage(local, () => 'new', () => 'new-commitment')
    writer.set('new-token')

    const reloaded = await load(local)
    expect(reloaded.boundUserStorage.get(reloaded.tokenStorage.getSnapshot())).toBeNull()
  })

  it.each([
    '{',
    '{"v":1,"generation":"good","generation":"other","commitment":"commit","user":{"id":1}}',
    '{"v":1,"generation":"good","commitment":"commit","user":{"id":1},"extra":true}',
    '{ "v":1,"generation":"good","commitment":"commit","user":{"id":1}}',
  ])('rejects malformed or noncanonical bound user metadata: %s', async (rawUser) => {
    const local = storage(new Map([
      [ENVELOPE, activeEnvelope('good', 'secret', 'commit')],
      [BARRIER, barrier('active', 'good', 'commit')],
      [USER_KEY_FOR_TEST, rawUser],
    ]))
    const { boundUserStorage, tokenStorage } = await load(local)
    expect(boundUserStorage.get(tokenStorage.getSnapshot())).toBeNull()
  })

  it('fails closed on a realistic login/logout mixed state and accepts the later completed logout', async () => {
    const local = storage()
    const module = await load(local)
    let tabACommitment = 0
    const tabA = module.createTokenStorage(local, () => 'tab-a', () => `commitment-a-${++tabACommitment}`)
    const tabB = module.createTokenStorage(local, () => 'tab-b', () => 'commitment-b')
    tabA.set('old-token')
    const originalSet = local.setItem as ReturnType<typeof vi.fn>
    let injected = false
    originalSet.mockImplementation((key: string, value: string) => {
      local.values.set(key, value)
      if (!injected && key === ENVELOPE && value === activeEnvelope('tab-a', 'new-token', 'commitment-a-2')) {
        injected = true
        tabB.remove()
      }
    })

    expect(() => tabA.set('new-token')).toThrow(module.TokenPersistenceError)
    expect(tabA.get()).toBe('')
    expect(tabB.get()).toBe('')
  })
})
