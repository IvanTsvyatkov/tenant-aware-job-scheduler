import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import JobForm from '../components/JobForm'
import * as api from '../services/api'

// Mock the API
vi.mock('../services/api', () => ({
  jobApi: {
    createJob: vi.fn()
  }
}))

describe('JobForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders form with all fields', () => {
    render(<JobForm tenantId="tenant1" />)

    expect(screen.getByLabelText(/target id/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/payload/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/idempotency key/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /submit job/i })).toBeInTheDocument()
  })

  it('submits job with auto-generated idempotency key when field is empty', async () => {
    const mockResponse = {
      data: {
        id: '123',
        tenantId: 'tenant1',
        targetId: 'target-1',
        payload: 'test payload',
        status: 'PENDING'
      }
    }

    api.jobApi.createJob.mockResolvedValue(mockResponse)

    const user = userEvent.setup()
    render(<JobForm tenantId="tenant1" />)

    // Fill in payload only
    const payloadInput = screen.getByLabelText(/payload/i)
    await user.type(payloadInput, 'test payload')

    // Submit form
    const submitButton = screen.getByRole('button', { name: /submit job/i })
    await user.click(submitButton)

    await waitFor(() => {
      expect(api.jobApi.createJob).toHaveBeenCalled()
    })

    // Verify idempotency key was auto-generated (not empty)
    const callArgs = api.jobApi.createJob.mock.calls[0]
    expect(callArgs[1].idempotencyKey).toBeTruthy()
    expect(callArgs[1].idempotencyKey.length).toBeGreaterThan(0)
  })

  it('submits job with custom idempotency key when provided', async () => {
    const mockResponse = {
      data: {
        id: '123',
        tenantId: 'tenant1',
        status: 'PENDING'
      }
    }

    api.jobApi.createJob.mockResolvedValue(mockResponse)

    const user = userEvent.setup()
    render(<JobForm tenantId="tenant1" />)

    // Fill in custom idempotency key
    const idempotencyInput = screen.getByLabelText(/idempotency key/i)
    await user.type(idempotencyInput, 'my-custom-key-123')

    // Fill in payload
    const payloadInput = screen.getByLabelText(/payload/i)
    await user.type(payloadInput, 'test')

    // Submit form
    const submitButton = screen.getByRole('button', { name: /submit job/i })
    await user.click(submitButton)

    await waitFor(() => {
      expect(api.jobApi.createJob).toHaveBeenCalledWith(
        'tenant1',
        expect.objectContaining({
          idempotencyKey: 'my-custom-key-123'
        })
      )
    })
  })

  it('clears form after successful submission', async () => {
    const mockResponse = {
      data: { id: '123', status: 'PENDING' }
    }

    api.jobApi.createJob.mockResolvedValue(mockResponse)

    const user = userEvent.setup()
    render(<JobForm tenantId="tenant1" />)

    const payloadInput = screen.getByLabelText(/payload/i)
    const idempotencyInput = screen.getByLabelText(/idempotency key/i)

    await user.type(payloadInput, 'test payload')
    await user.type(idempotencyInput, 'test-key')

    const submitButton = screen.getByRole('button', { name: /submit job/i })
    await user.click(submitButton)

    await waitFor(() => {
      expect(payloadInput.value).toBe('')
      expect(idempotencyInput.value).toBe('')
    })
  })

  it('shows error message on submission failure', async () => {
    api.jobApi.createJob.mockRejectedValue({
      response: {
        data: {
          message: 'Failed to create job'
        }
      }
    })

    const user = userEvent.setup()
    render(<JobForm tenantId="tenant1" />)

    const submitButton = screen.getByRole('button', { name: /submit job/i })
    await user.click(submitButton)

    await waitFor(() => {
      expect(screen.getByText(/failed to create job/i)).toBeInTheDocument()
    })
  })

  it('disables submit button while submitting', async () => {
    let resolveCreate
    const createPromise = new Promise(resolve => {
      resolveCreate = resolve
    })

    api.jobApi.createJob.mockReturnValue(createPromise)

    const user = userEvent.setup()
    render(<JobForm tenantId="tenant1" />)

    const submitButton = screen.getByRole('button', { name: /submit job/i })
    await user.click(submitButton)

    // Button should be disabled during submission
    expect(submitButton).toBeDisabled()
    expect(submitButton).toHaveTextContent(/submitting/i)

    // Complete the submission
    resolveCreate({ data: { id: '123' } })

    await waitFor(() => {
      expect(submitButton).not.toBeDisabled()
      expect(submitButton).toHaveTextContent(/submit job/i)
    })
  })

  it('passes correct tenant ID to API', async () => {
    const mockResponse = {
      data: { id: '123', status: 'PENDING' }
    }

    api.jobApi.createJob.mockResolvedValue(mockResponse)

    const user = userEvent.setup()
    render(<JobForm tenantId="tenant-special" />)

    const submitButton = screen.getByRole('button', { name: /submit job/i })
    await user.click(submitButton)

    await waitFor(() => {
      expect(api.jobApi.createJob).toHaveBeenCalledWith(
        'tenant-special',
        expect.any(Object)
      )
    })
  })

  it('allows selecting different targets', async () => {
    const mockResponse = {
      data: { id: '123', status: 'PENDING' }
    }

    api.jobApi.createJob.mockResolvedValue(mockResponse)

    const user = userEvent.setup()
    render(<JobForm tenantId="tenant1" />)

    const targetSelect = screen.getByLabelText(/target id/i)
    await user.selectOptions(targetSelect, 'target-3')

    const submitButton = screen.getByRole('button', { name: /submit job/i })
    await user.click(submitButton)

    await waitFor(() => {
      expect(api.jobApi.createJob).toHaveBeenCalledWith(
        'tenant1',
        expect.objectContaining({
          targetId: 'target-3'
        })
      )
    })
  })

  it('shows fallback error message when error has no response body', async () => {
    // Error without a response (e.g. network error) should hit the
    // `|| 'Failed to create job'` fallback branch.
    api.jobApi.createJob.mockRejectedValue(new Error('Network Error'))

    const user = userEvent.setup()
    render(<JobForm tenantId="tenant1" />)

    const submitButton = screen.getByRole('button', { name: /submit job/i })
    await user.click(submitButton)

    await waitFor(() => {
      expect(screen.getByText('Failed to create job')).toBeInTheDocument()
    })
  })


  it('renders tenant label and accent color when tenant prop is provided', () => {
    const tenant = { label: 'Acme Corp', color: '#ff0000' }
    render(<JobForm tenantId="tenant1" tenant={tenant} />)

    // The tenant label is shown in the chip and subtitle instead of the raw id
    expect(screen.getAllByText('Acme Corp').length).toBeGreaterThan(0)
  })

  it('falls back to tenantId when tenant prop is missing a label', () => {
    render(<JobForm tenantId="tenant1" tenant={{}} />)

    // With no label, the raw tenantId should be displayed
    expect(screen.getAllByText('tenant1').length).toBeGreaterThan(0)
  })
})
