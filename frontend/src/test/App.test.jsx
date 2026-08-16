import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import App from '../App'

vi.mock('../services/api', () => ({
  configApi: {
    getConcurrencyCaps: vi.fn(() =>
      Promise.resolve({ data: { globalMax: 5, tenantMax: 2, targetMax: 2 } })
    ),
  },
}))

vi.mock('../components/JobForm', () => ({
  default: ({ tenantId }) => <div data-testid="job-form">JobForm: {tenantId}</div>
}))

vi.mock('../components/JobList', () => ({
  default: ({ tenantId }) => <div data-testid="job-list">JobList: {tenantId}</div>
}))

describe('App', () => {
  it('renders the default tenant and child components', async () => {
    render(<App />)

    expect(screen.getByRole('heading', { name: /tenant job scheduler/i })).toBeInTheDocument()
    expect(screen.getByLabelText(/tenant/i)).toHaveValue('tenant1')
    expect(screen.getByTestId('job-form')).toHaveTextContent('tenant1')
    expect(screen.getByTestId('job-list')).toHaveTextContent('tenant1')

    // Concurrency caps are loaded dynamically from the backend
    await waitFor(() => {
      expect(screen.getByText(/concurrency caps/i)).toBeInTheDocument()
      expect(screen.getByText('Global')).toBeInTheDocument()
    })
  })

  it('updates both child components when the tenant changes', async () => {
    const user = userEvent.setup()
    render(<App />)

    await user.selectOptions(screen.getByLabelText(/tenant/i), 'tenant3')

    expect(screen.getByTestId('job-form')).toHaveTextContent('tenant3')
    expect(screen.getByTestId('job-list')).toHaveTextContent('tenant3')
  })
})
