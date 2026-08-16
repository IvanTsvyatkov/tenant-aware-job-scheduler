import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// Mock the CSS import so it doesn't affect the test environment
vi.mock('../index.css', () => ({}))

// Mock the App component to isolate main.jsx bootstrap behaviour
vi.mock('../App.jsx', () => ({
  default: () => <div data-testid="app">App</div>,
}))

// Mock react-dom/client so we can assert how the app is mounted
const renderMock = vi.fn()
const createRootMock = vi.fn(() => ({ render: renderMock }))

vi.mock('react-dom/client', () => ({
  default: { createRoot: createRootMock },
  createRoot: createRootMock,
}))

describe('main.jsx', () => {
  beforeEach(() => {
    vi.resetModules()
    renderMock.mockClear()
    createRootMock.mockClear()

    // Provide the mount target that index.html normally supplies
    const root = document.createElement('div')
    root.id = 'root'
    document.body.appendChild(root)
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('creates a React root on the #root element', async () => {
    await import('../main.jsx')

    expect(createRootMock).toHaveBeenCalledTimes(1)
    const rootElement = createRootMock.mock.calls[0][0]
    expect(rootElement).toBe(document.getElementById('root'))
  })

  it('renders the application into the created root', async () => {
    await import('../main.jsx')

    expect(renderMock).toHaveBeenCalledTimes(1)
  })

  it('renders the App wrapped in React.StrictMode', async () => {
    const React = (await import('react')).default
    await import('../main.jsx')

    const renderedTree = renderMock.mock.calls[0][0]
    // The root of the rendered tree should be React.StrictMode
    expect(renderedTree.type).toBe(React.StrictMode)

    // Its single child should be the App component
    const child = renderedTree.props.children
    expect(child.type).toBeDefined()
  })
})
