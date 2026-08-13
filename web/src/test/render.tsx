import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { type RenderOptions, render } from '@testing-library/react'
import type { ReactElement, ReactNode } from 'react'
import { MemoryRouter } from 'react-router'

/*
 * Render a component inside the same providers main.tsx gives it.
 *
 * The QueryClient is built here rather than imported from lib/queryClient
 * because tests need two things the app must not have: a fresh cache per test
 * (a cache shared across tests leaks one test's data into the next) and no
 * retries (a retry turns an asserted failure into a multi-second timeout).
 * Everything else about a query — the request, the cache write, the render —
 * stays the real code.
 *
 * The router is a MemoryRouter rather than main.tsx's BrowserRouter for the one
 * reason that matters to a test: the entry URL is an argument. Both are the same
 * <Routes> underneath, so a redirect asserted here is the redirect that ships.
 */
export function renderWithProviders(
  ui: ReactElement,
  {
    initialEntries,
    ...options
  }: Omit<RenderOptions, 'wrapper'> & {
    initialEntries?: string[]
  } = {},
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  function Providers({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={initialEntries}>{children}</MemoryRouter>
      </QueryClientProvider>
    )
  }

  return { queryClient, ...render(ui, { wrapper: Providers, ...options }) }
}
