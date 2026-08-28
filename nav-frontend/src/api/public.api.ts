import request, { unwrapApiData } from './request'
import type { NavigationCategory } from '@/types/category'
import type { SearchEngine } from '@/types/searchEngine'
import type { SiteConfig } from '@/types/site'

export async function getPublicSiteConfig(): Promise<SiteConfig> {
  return unwrapApiData(await request.get('/public/site-config'))
}

export async function getPublicNavigation(): Promise<NavigationCategory[]> {
  return unwrapApiData(await request.get('/public/navigation'))
}

export async function getPublicSearchEngines(): Promise<SearchEngine[]> {
  return unwrapApiData(await request.get('/public/search-engines'))
}
