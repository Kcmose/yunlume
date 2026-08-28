import request, { unwrapApiData } from './request'
import type { SiteConfig, SiteConfigUpdatePayload } from '@/types/site'

export async function getAdminSiteConfig(): Promise<SiteConfig> {
  return unwrapApiData(await request.get('/admin/site-config'))
}

export async function updateSiteConfig(payload: SiteConfigUpdatePayload): Promise<SiteConfig> {
  return unwrapApiData(await request.put('/admin/site-config', payload))
}
