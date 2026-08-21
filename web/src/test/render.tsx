import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { type RenderOptions, render } from '@testing-library/react'
import type { ReactElement, ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { queryClientDefaults } from '../lib/queryClient'

/*
 * Render a component inside the same providers main.tsx gives it.
 *
 * The QueryClient is built here rather than imported from lib/queryClient
 * because tests need a fresh cache per test — a cache shared across tests leaks
 * one test's data into the next. It is built ON TOP OF the app's own defaults
 * rather than beside them: a second hand-written set drifts, and a test running
 * against defaults the app does not have proves nothing about the app. Retry is
 * the one override, because a retry turns an asserted failure into a
 * multi-second timeout. Everything else — staleTime, refetch on focus, and the
 * mutation scope that makes mutations run one at a time
 * (notes/2026-08-21-decision-query-defaults-and-mutation-ordering.md) — is the
 * shipped value, so a test of a mutation flow tests the shipped ordering.
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
      queries: { ...queryClientDefaults.queries, retry: false },
      mutations: { ...queryClientDefaults.mutations, retry: false },
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
