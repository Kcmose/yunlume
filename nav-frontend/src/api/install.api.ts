import request, { unwrapApiData } from './request'
import type {
  CompleteInstallationPayload,
  CompleteInstallationResult,
  ConfigureInstallDatabasePayload,
  ConfigureInstallDatabaseResult,
  ConfigureInstallRedisPayload,
  ConfigureInstallRedisResult,
  InstallCheckResult,
  InstallDatabaseConfig,
  InstallDatabaseTestResult,
  InstallRedisConfig,
  InstallRedisTestResult,
  InstallStatus,
} from '@/types/install'
import {
  normalizeConfigureInstallDatabaseResult,
  normalizeInstallDatabaseTestResult,
} from '@/utils/installDatabase'
import {
  normalizeConfigureInstallRedisResult,
  normalizeInstallRedisTestResult,
} from '@/utils/installRedis'
import { normalizeInstallCheckResult, normalizeInstallStatus } from '@/utils/installState'

export async function getInstallStatusApi(): Promise<InstallStatus> {
  const payload = unwrapApiData<unknown>(await request.get('/install/status', { timeout: 2500 }))
  return normalizeInstallStatus(payload)
}

export async function checkInstallationApi(): Promise<InstallCheckResult> {
  const payload = unwrapApiData<unknown>(await request.post('/install/check', undefined, {
    timeout: 12000,
  }))
  return normalizeInstallCheckResult(payload)
}

export async function testInstallDatabaseApi(
  database: InstallDatabaseConfig,
): Promise<InstallDatabaseTestResult> {
  const payload = unwrapApiData<unknown>(await request.post('/install/database/test', database, {
    timeout: 20000,
  }))
  return normalizeInstallDatabaseTestResult(payload)
}

export async function configureInstallDatabaseApi(
  payload: ConfigureInstallDatabasePayload,
): Promise<ConfigureInstallDatabaseResult> {
  const response = unwrapApiData<unknown>(await request.post('/install/database/configure', payload, {
    timeout: 90000,
  }))
  return normalizeConfigureInstallDatabaseResult(response)
}

export async function testInstallRedisApi(
  redis: InstallRedisConfig,
): Promise<InstallRedisTestResult> {
  const payload = unwrapApiData<unknown>(await request.post('/install/redis/test', redis, {
    timeout: 75000,
  }))
  return normalizeInstallRedisTestResult(payload)
}

export async function configureInstallRedisApi(
  payload: ConfigureInstallRedisPayload,
): Promise<ConfigureInstallRedisResult> {
  const response = unwrapApiData<unknown>(await request.post('/install/redis/configure', payload, {
    timeout: 90000,
  }))
  return normalizeConfigureInstallRedisResult(response)
}

export async function completeInstallationApi(
  payload: CompleteInstallationPayload,
): Promise<CompleteInstallationResult> {
  return unwrapApiData<CompleteInstallationResult>(await request.post('/install/complete', payload, {
    timeout: 20000,
  }))
}
