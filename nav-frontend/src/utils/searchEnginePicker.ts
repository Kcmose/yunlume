import type { SearchEngine } from '@/types/searchEngine'

export const SEARCH_ENGINE_STORAGE_KEY = 'ilinks_selected_search_engine'

export interface SearchEngineStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

export function isSearchEngineTextIcon(value: string): boolean {
  const icon = value.trim()
  return Boolean(icon) && [...icon].length <= 3
}

export function searchEngineIconUrl(engine: SearchEngine): string {
  const icon = engine.icon?.trim() || ''
  return /^https?:\/\//i.test(icon) ? icon : ''
}

export function searchEngineMark(engine: SearchEngine): string {
  const icon = engine.icon?.trim() || ''
  if (isSearchEngineTextIcon(icon)) return icon
  return [...engine.name.trim()][0]?.toUpperCase() || '搜'
}

export function isSameSearchEngine(
  left: SearchEngine['id'],
  right: SearchEngine['id'],
): boolean {
  return String(left) === String(right)
}

export function resolveSearchEngineId(
  engines: SearchEngine[],
  currentId: SearchEngine['id'] | null | undefined,
  persistedId: SearchEngine['id'] | null | undefined,
): SearchEngine['id'] | null {
  const current = engines.find((engine) => (
    currentId !== null
    && currentId !== undefined
    && isSameSearchEngine(engine.id, currentId)
  ))
  if (current) return current.id

  const persisted = engines.find((engine) => (
    persistedId !== null
    && persistedId !== undefined
    && isSameSearchEngine(engine.id, persistedId)
  ))
  if (persisted) return persisted.id

  return engines.find((engine) => engine.isDefault)?.id ?? engines[0]?.id ?? null
}

export function readPersistedSearchEngineId(
  storage: SearchEngineStorage | null | undefined,
): string {
  try {
    return storage?.getItem(SEARCH_ENGINE_STORAGE_KEY)?.trim() ?? ''
  } catch {
    return ''
  }
}

export function persistSearchEngineId(
  storage: SearchEngineStorage | null | undefined,
  engineId: SearchEngine['id'],
): void {
  try {
    storage?.setItem(SEARCH_ENGINE_STORAGE_KEY, String(engineId))
  } catch {
    // A private browser context may deny storage. Selection still works in-memory.
  }
}
