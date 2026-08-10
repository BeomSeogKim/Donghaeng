import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { type RenderOptions, render } from '@testing-library/react'
import type { ReactElement, ReactNode } from 'react'

/*
 * Render a component inside the same providers main.tsx gives it.
 *
 * The QueryClient is built here rather than imported from lib/queryClient
 * because tests need two things the app must not have: a fresh cache per test
 * (a cache shared across tests leaks one test's data into the next) and no
 * retries (a retry turns an asserted failure into a multi-second timeout).
 * Everything else about a query — the request, the cache write, the render —
 * stays the real code.
 */
export function renderWithProviders(
  ui: ReactElement,
  options?: Omit<RenderOptions, 'wrapper'>,
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  function Providers({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }

  return { queryClient, ...render(ui, { wrapper: Providers, ...options }) }
}
