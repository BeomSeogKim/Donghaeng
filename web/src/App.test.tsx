import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { expect, it } from 'vitest'
import { App } from './App'
import { renderWithProviders } from './test/render'
import { server } from './test/server'

/*
 * The login round trip, end to end inside the app.
 *
 * Only the network is faked (MSW). Everything else — the router, the guard, the
 * React Query cache, the fetch wrapper — is the real code, because the failures
 * worth catching here live in how those fit together: a guard that shows the
 * ledger to a 401, a sign-out that clears the screen but not the session, a
 * request sent without credentials.
 *
 * WHAT THESE CANNOT COVER. Login itself leaves the app: the button is a browser
 * navigation to the API, which 302s to Google and comes back to the API, which
 * sets the cookie and redirects here. jsdom cannot follow that, and no assertion
 * in this file proves a cookie was issued. What is asserted is the last thing
 * that is still ours — the exact URL the person is sent to. Everything past it
 * is verified by hand in a real browser.
 */

const API = 'http://localhost:8080'

/** Requests MSW actually received, in order — the credentials mode included. */
function recording() {
  const seen: Request[] = []
  return {
    seen,
    /** @param status 200 with a body, or any status the spec lists for this call. */
    me(response: () => Response) {
      return http.get(`${API}/auth/me`, ({ request }) => {
        seen.push(request)
        return response()
      })
    },
    logout(response: () => Response) {
      return http.post(`${API}/auth/logout`, ({ request }) => {
        seen.push(request)
        return response()
      })
    },
  }
}

const unauthenticated = () =>
  HttpResponse.json(
    {
      type: 'about:blank',
      title: 'Unauthorized',
      status: 401,
      instance: '/auth/me',
      code: 'UNAUTHENTICATED',
    },
    { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
  )

const signedIn = (name: string | null) => HttpResponse.json({ id: 12, name })

it('sends an unauthenticated visitor from the ledger to the login screen', async () => {
  server.use(recording().me(unauthenticated))

  renderWithProviders(<App />, { initialEntries: ['/'] })

  // The exact URL is the assertion that matters: it is the last thing still ours
  // before the browser leaves for Google.
  expect(await screen.findByRole('link', { name: '구글로 로그인' })).toHaveAttribute(
    'href',
    `${API}/oauth2/authorization/google`,
  )
  expect(screen.getByRole('heading', { name: '동행' })).toBeVisible()
})

it('starts login as a navigation to the API, never as a fetch', async () => {
  const calls = recording()
  server.use(calls.me(unauthenticated))

  renderWithProviders(<App />, { initialEntries: ['/login'] })

  const start = await screen.findByRole('link', { name: '구글로 로그인' })
  await userEvent.click(start)

  // A real <a href> to another origin: jsdom will not navigate, and the point is
  // that nothing of ours issued an XHR. `GET /oauth2/authorization/google` 302s
  // to Google, which a fetch cannot follow — it would fail CORS at the provider
  // instead of logging anyone in (docs/api-spec.md § Authentication).
  expect(calls.seen.map((request) => request.url)).toEqual([`${API}/auth/me`])
})

it('shows the signed-in person and lets them sign out', async () => {
  const calls = recording()
  let signedOut = false
  server.use(
    calls.me(() => (signedOut ? unauthenticated() : signedIn('김테스터'))),
    calls.logout(() => {
      signedOut = true
      return new HttpResponse(null, { status: 204 })
    }),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  expect(await screen.findByText('김테스터 님')).toBeVisible()

  await userEvent.click(screen.getByRole('button', { name: '로그아웃' }))

  expect(await screen.findByRole('link', { name: '구글로 로그인' })).toBeVisible()
  // The row is revoked on the server; deleting the cookie client-side is not
  // logout (docs/api-spec.md § POST /auth/logout).
  const logout = calls.seen.find((request) => request.url === `${API}/auth/logout`)
  expect(logout?.method).toBe('POST')
})

it('leaves nothing of the signed-out person in the cache', async () => {
  const calls = recording()
  server.use(
    calls.me(() => signedIn('김테스터')),
    calls.logout(() => new HttpResponse(null, { status: 204 })),
  )

  const { queryClient } = renderWithProviders(<App />, { initialEntries: ['/'] })
  await screen.findByText('김테스터 님')
  // Stands in for the ledger, which is cached under its own key from #5 onward.
  // Two people share one device here; the next person to sign in must not be
  // shown a frame of the last one's guests.
  queryClient.setQueryData(['guests'], [{ name: '윤채원' }])

  await userEvent.click(screen.getByRole('button', { name: '로그아웃' }))

  await waitFor(() => expect(queryClient.getQueryData(['guests'])).toBeUndefined())
})

it('sends every request with credentials, or the API sees an anonymous caller', async () => {
  const calls = recording()
  server.use(
    calls.me(() => signedIn('김테스터')),
    calls.logout(() => new HttpResponse(null, { status: 204 })),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  await userEvent.click(await screen.findByRole('button', { name: '로그아웃' }))

  await waitFor(() => expect(calls.seen.length).toBeGreaterThanOrEqual(2))
  for (const request of calls.seen) {
    expect(request.credentials).toBe('include')
  }
})

it('renders a fallback when the provider returned no name', async () => {
  server.use(recording().me(() => signedIn(null)))

  renderWithProviders(<App />, { initialEntries: ['/'] })

  expect(await screen.findByText('이름 없음 님')).toBeVisible()
})

it('does not claim the person is signed out when the server failed', async () => {
  server.use(
    recording().me(() =>
      HttpResponse.json(
        {
          type: 'about:blank',
          title: 'Internal Server Error',
          status: 500,
          detail: 'An unexpected error occurred.',
          instance: '/auth/me',
          code: 'INTERNAL_ERROR',
        },
        { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  expect(await screen.findByText('로그인 상태를 확인하지 못했습니다')).toBeVisible()
  expect(screen.getByRole('button', { name: '다시 시도' })).toBeVisible()
  expect(screen.queryByRole('link', { name: '구글로 로그인' })).not.toBeInTheDocument()
})

it('keeps a signed-in person off the login screen', async () => {
  server.use(recording().me(() => signedIn('김테스터')))

  renderWithProviders(<App />, { initialEntries: ['/login'] })

  expect(await screen.findByText('김테스터 님')).toBeVisible()
})
