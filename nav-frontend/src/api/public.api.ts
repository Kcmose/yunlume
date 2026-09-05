import request, { unwrapApiData } from './request'
import type { NavigationCategory } from '@/types/category'
import type { SearchEngine } from '@/types/searchEngine'
import type { SiteConfig } from '@/types/site'

export async function getPublicSiteConfig(): Promise<SiteConfig> {
  return unwrapApiData(await request.get('/public/site-config', { timeout: 3000 }))
}

export async function getPublicNavigation(): Promise<NavigationCategory[]> {
  return unwrapApiData(await request.get('/public/navigation', { timeout: 3000 }))
}

export async function getPublicSearchEngines(): Promise<SearchEngine[]> {
  return unwrapApiData(await request.get('/public/search-engines', { timeout: 3000 }))
}
