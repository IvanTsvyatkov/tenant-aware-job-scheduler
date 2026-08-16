import { describe, it, expect, vi, beforeEach } from 'vitest'

const { mockAxios } = vi.hoisted(() => ({
  mockAxios: {
    get: vi.fn(),
    post: vi.fn()
  }
}))

vi.mock('axios', () => ({
  default: {
    create: vi.fn(() => mockAxios)
  }
}))

import { jobApi, configApi } from '../services/api'

describe('jobApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('creates a job with the tenant header', () => {
    const request = { targetId: 'target-1', payload: 'test' }

    jobApi.createJob('tenant1', request)

    expect(mockAxios.post).toHaveBeenCalledWith('/jobs', request, {
      headers: { 'X-Tenant-Id': 'tenant1' }
    })
  })

  it('gets all jobs for a tenant', () => {
    jobApi.getJobs('tenant2')

    expect(mockAxios.get).toHaveBeenCalledWith('/jobs', {
      headers: { 'X-Tenant-Id': 'tenant2' }
    })
  })


  it('builds the SSE endpoint URL', () => {
    expect(jobApi.getSSEUrl()).toBe('http://localhost:8080/jobs/stream')
  })
})

describe('configApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('fetches the concurrency caps', () => {
    configApi.getConcurrencyCaps()

    expect(mockAxios.get).toHaveBeenCalledWith('/config/concurrency')
  })

  it('returns the response from the concurrency caps request', async () => {
    const caps = { global: 5, perTenant: 2, perTarget: 2 }
    mockAxios.get.mockResolvedValue({ data: caps })

    const response = await configApi.getConcurrencyCaps()

    expect(response.data).toEqual(caps)
  })
})

