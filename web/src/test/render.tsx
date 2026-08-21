import { QueryClientProvider } from '@tanstack/react-query'
import { type RenderOptions, render } from '@testing-library/react'
import type { ReactElement, ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { createQueryClient, queryClientDefaults } from '../lib/queryClient'

/*
 * Render a component inside the same providers main.tsx gives it.
 *
 * The QueryClient is the app's own — `createQueryClient()`, called per test
 * because tests need a fresh cache each time and a shared one leaks a test's
 * data into the next. It is not hand-built beside the app's: a second set
 * drifts, and a test running against a client the app does not have proves
 * nothing about the app. That matters for more than the defaults now — the
 * app's answer to a 401 lives on the client's caches, so a hand-built client
 * would have quietly had no answer at all.
 *
 * Retry is the one override, applied after the fact, because a retry turns an
 * asserted failure into a multi-second timeout. Everything else — staleTime,
 * refetch on focus, and the mutation scope that makes mutations run one at a
 * time (notes/2026-08-21-decision-query-defaults-and-mutation-ordering.md) — is
 * the shipped value, so a test of a mutation flow tests the shipped ordering.
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
  const queryClient = createQueryClient()
  queryClient.setDefaultOptions({
    queries: { ...queryClientDefaults.queries, retry: false },
    mutations: { ...queryClientDefaults.mutations, retry: false },
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
