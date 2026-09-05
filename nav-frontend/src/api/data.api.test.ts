import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('./request', () => ({
  default: requestMocks,
  unwrapApiData: (response: { data: unknown }) => response.data,
}))

import { getCurrentNavigationDataImportJob, getNavigationDataImportJobByPreviewToken } from './data.api'

describe('portable data import API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('can recover the current administrators latest import job', async () => {
    const job = {
      jobId: 'job-1',
      stage: 'PREPARING',
      createdAt: '2026-09-04T00:00:00Z',
      startedAt: null,
      finishedAt: null,
      message: '任务等待执行',
    }
    requestMocks.get.mockResolvedValue({ data: job })

    await expect(getCurrentNavigationDataImportJob()).resolves.toEqual(job)
    expect(requestMocks.get).toHaveBeenCalledWith('/admin/data/import/jobs/current')
  })

  it('looks up a confirmed token without issuing a mutation and accepts terminal results', async () => {
    const job = { jobId: 'job-1', stage: 'COMPLETED', createdAt: '2026-09-05T00:00:00Z', message: '已完成' }
    requestMocks.get.mockResolvedValue({ data: job })
    await expect(getNavigationDataImportJobByPreviewToken('token/value')).resolves.toEqual(job)
    expect(requestMocks.get).toHaveBeenCalledWith('/admin/data/import/previews/token%2Fvalue/job')
    expect(requestMocks.post).not.toHaveBeenCalled()
  })
})
