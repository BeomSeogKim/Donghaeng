import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { useLocation, useNavigate } from 'react-router'
import { expect, it } from 'vitest'
import { App } from './App'
import type { Guest } from './hooks/useGuests'
import { guestsQueryKey } from './hooks/useGuests'
import type { Session } from './hooks/useSession'
import type { Wedding } from './hooks/useWeddings'
import { apiError } from './lib/api'
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
    /**
     * Refuses what the API refuses. A state-changing request without
     * `Content-Type: application/json` is answered 415 and never reaches the
     * endpoint (docs/api-spec.md § Every POST, PUT and PATCH must send
     * `Content-Type: application/json`), so this double answers 415 too — a
     * double more permissive than the server is how a sign-out button that
     * cannot sign anyone out stays green.
     */
    logout(response: () => Response) {
      return http.post(`${API}/auth/logout`, ({ request }) => {
        seen.push(request)
        if (request.headers.get('Content-Type') !== 'application/json')
          return unsupportedMediaType()
        return response()
      })
    },
  }
}

/**
 * The two reads 원장 makes on arrival. `/` is the ledger now (`#15`), so every
 * test that signs someone in at `/` needs them — an unhandled request is an
 * error in this suite, deliberately.
 */
const ledger = () => [
  http.get(`${API}/weddings`, () =>
    HttpResponse.json<Wedding[]>([
      {
        id: 12,
        weddingDate: '2026-10-10',
        seats: [
          { side: 'GROOM', name: '김신랑' },
          { side: 'BRIDE', name: '이신부' },
        ],
      },
    ]),
  ),
  http.get(`${API}/weddings/:weddingId/guests`, () => HttpResponse.json<Guest[]>([])),
]

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

/**
 * The success body, typed from the generated document rather than written out.
 * A double is only worth the file it lives in while it answers what the server
 * answers — and a mock the API outgrew stays green forever, which is the exact
 * failure `#39` exists to close. Renaming a field in `MeResponse` now fails
 * here, not in production.
 *
 * The problem documents below are deliberately NOT typed this way: the
 * generated `ProblemDetail` is Spring's own and carries no `code` (`#66`), and
 * `code` is the only member anything branches on. Their shape comes from
 * docs/api-spec.md, which is where it is actually defined.
 */
const signedIn = (name: Session['name']) => HttpResponse.json<Session>({ id: 12, name })

/**
 * The refusal, as the API actually writes it — an ordinary problem document,
 * not a bare status (docs/api-spec.md § Every POST, PUT and PATCH must send
 * `Content-Type: application/json`). The status is the same either way, so a
 * bare 415 would look convincing while disagreeing with the server about
 * `code`, which is the only member anything branches on.
 *
 * `detail` is verbatim from the server, quoting back the header that was sent —
 * kept exactly so, because it is the reason nothing renders `detail`. With no
 * header at all the server writes `'null'`, which is this case.
 */
const unsupportedMediaType = () =>
  HttpResponse.json(
    {
      type: 'about:blank',
      title: 'Unsupported Media Type',
      status: 415,
      detail: "Content-Type 'null' is not supported.",
      instance: '/auth/logout',
      code: 'UNSUPPORTED_MEDIA_TYPE',
    },
    { status: 415, headers: { 'Content-Type': 'application/problem+json' } },
  )

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

it('opens the ledger for a signed-in person, and lets them sign out', async () => {
  const calls = recording()
  let signedOut = false
  server.use(
    calls.me(() => (signedOut ? unauthenticated() : signedIn('김테스터'))),
    calls.logout(() => {
      signedOut = true
      return new HttpResponse(null, { status: 204 })
    }),
    ...ledger(),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  expect(await screen.findByRole('heading', { name: '원장' })).toBeVisible()

  await userEvent.click(screen.getByRole('button', { name: '로그아웃' }))

  expect(await screen.findByRole('link', { name: '구글로 로그인' })).toBeVisible()
  // The row is revoked on the server; deleting the cookie client-side is not
  // logout (docs/api-spec.md § POST /auth/logout).
  const logout = calls.seen.find((request) => request.url === `${API}/auth/logout`)
  expect(logout?.method).toBe('POST')
  // Without it the API answers 415 and the session survives the button.
  expect(logout?.headers.get('Content-Type')).toBe('application/json')
})

it('says the person is still signed in when the sign-out never reached the API', async () => {
  const calls = recording()
  server.use(
    calls.me(() => signedIn('김테스터')),
    // `POST /auth/logout` always answers 204 — no cookie, expired, already
    // revoked, all of them mean "not logged in on this device". So a non-204 is
    // not a refusal: it is the request never arriving, and the session on the
    // server is still live (docs/api-spec.md § POST /auth/logout).
    calls.logout(() => HttpResponse.error()),
    ...ledger(),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  await userEvent.click(await screen.findByRole('button', { name: '로그아웃' }))

  // The couple share a phone. A button that un-disables itself and says nothing
  // tells them they signed out when they did not.
  expect(
    await screen.findByText('로그아웃하지 못했습니다. 연결을 확인하고 다시 눌러 주세요.'),
  ).toBeVisible()
  expect(screen.getByRole('heading', { name: '원장' })).toBeVisible()
  expect(screen.queryByRole('link', { name: '구글로 로그인' })).not.toBeInTheDocument()
})

it('refuses a sign-out that omits the JSON content type, exactly as the API does', async () => {
  const calls = recording()
  server.use(calls.logout(() => new HttpResponse(null, { status: 204 })))

  // The shape the app sent until 2026-08-15. Asserted so the double above stays
  // strict: if it ever goes back to accepting this, the sign-out test above
  // would pass with a request the real server throws away.
  const refused = await fetch(`${API}/auth/logout`, { method: 'POST' })

  expect(refused.status).toBe(415)
  expect(await apiError(refused)).toMatchObject({
    status: 415,
    code: 'UNSUPPORTED_MEDIA_TYPE',
  })
})

it('leaves nothing of the signed-out person in the cache', async () => {
  const calls = recording()
  server.use(
    calls.me(() => signedIn('김테스터')),
    calls.logout(() => new HttpResponse(null, { status: 204 })),
    ...ledger(),
  )

  const { queryClient } = renderWithProviders(<App />, { initialEntries: ['/'] })
  await screen.findByRole('heading', { name: '원장' })
  // The real ledger key, which now exists. Two people share one device here;
  // the next person to sign in must not be shown a frame of the last one's
  // guests.
  await waitFor(() =>
    expect(queryClient.getQueryData(guestsQueryKey(12, {}))).toBeDefined(),
  )

  await userEvent.click(screen.getByRole('button', { name: '로그아웃' }))

  await waitFor(() =>
    expect(queryClient.getQueryData(guestsQueryKey(12, {}))).toBeUndefined(),
  )
})

it('sends every request with credentials, or the API sees an anonymous caller', async () => {
  const calls = recording()
  server.use(
    calls.me(() => signedIn('김테스터')),
    calls.logout(() => new HttpResponse(null, { status: 204 })),
    ...ledger(),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  await userEvent.click(await screen.findByRole('button', { name: '로그아웃' }))

  await waitFor(() => expect(calls.seen.length).toBeGreaterThanOrEqual(2))
  for (const request of calls.seen) {
    expect(request.credentials).toBe('include')
  }
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
  server.use(
    recording().me(() => signedIn('김테스터')),
    ...ledger(),
  )

  renderWithProviders(<App />, { initialEntries: ['/login'] })

  expect(await screen.findByRole('heading', { name: '원장' })).toBeVisible()
})

/*
 * A failed OAuth callback comes back as a browser navigation to /login with a
 * closed code in the fragment (docs/api-spec.md § GET /login/oauth2/code/google,
 * notes/2026-08-13-decision-login-failure-return-path.md). These are the tests
 * the methodology makes mandatory: the branch decides which words a person
 * reads, and a wrong mapping is a silent wrong message rather than a crash.
 *
 * The fragment is fully attacker-controlled — anyone can send a victim to
 * <frontend>/login#e=<anything> without touching our API — so "never render the
 * value" is asserted, not assumed.
 */

/** A probe for the URL itself: the fragment must not survive being handled. */
function LocationHash() {
  return <span data-testid="location-hash">{useLocation().hash}</span>
}

/** A probe for `location.state`, which a redirect-after-login would carry. */
function LocationState() {
  return <span data-testid="location-state">{JSON.stringify(useLocation().state)}</span>
}

/** Stands in for an in-app arrival at a screen that is already on the page. */
function GoTo({ to, state }: { to: string; state?: unknown }) {
  const navigate = useNavigate()
  return (
    <button onClick={() => navigate(to, { state })} type="button">
      이동
    </button>
  )
}

it('treats a refused consent as a normal path, not as an error', async () => {
  server.use(recording().me(unauthenticated))

  renderWithProviders(<App />, { initialEntries: ['/login#e=denied'] })

  expect(await screen.findByText('로그인을 취소했습니다')).toBeVisible()
  // Cancelling is not a failure and must not be told as one — asserted on the
  // words themselves, which is what the person actually reads.
  expect(screen.queryByText(/못했습니다/)).not.toBeInTheDocument()
  // The button stays the obvious next action.
  expect(screen.getByRole('link', { name: '구글로 로그인' })).toBeVisible()
})

it('says a login failed without inventing a reason for it', async () => {
  server.use(recording().me(unauthenticated))

  renderWithProviders(<App />, { initialEntries: ['/login#e=failed'] })

  expect(await screen.findByText('로그인하지 못했습니다')).toBeVisible()
  // The honest half: two codes travel and no detail does, so there is nothing
  // to explain — and claiming otherwise would be an invented cause.
  expect(
    screen.getByText('무엇 때문인지는 알 수 없습니다. 다시 시도해 주세요.'),
  ).toBeVisible()
  expect(screen.getByRole('link', { name: '구글로 로그인' })).toBeVisible()
})

it('reads an unrecognised code as a failure and never renders it', async () => {
  server.use(recording().me(unauthenticated))

  renderWithProviders(<App />, {
    initialEntries: ['/login#e=%3Cimg%20src%3Dx%20onerror%3Dalert(1)%3E'],
  })

  expect(await screen.findByText('로그인하지 못했습니다')).toBeVisible()
  // Asserted on the markup and on the node, not on textContent: an injected
  // element contributes no text, so a textContent assertion would pass while
  // the handler had already fired.
  expect(document.querySelector('img')).toBeNull()
  expect(document.body.innerHTML).not.toContain('onerror')
})

it('clears the fragment once handled, and keeps the message on screen', async () => {
  server.use(recording().me(unauthenticated))

  renderWithProviders(
    <>
      <App />
      <LocationHash />
    </>,
    { initialEntries: ['/login#e=denied'] },
  )

  // A stale fragment would re-announce a cancelled login on every later
  // navigation back to /login, and on a reload.
  await waitFor(() => expect(screen.getByTestId('location-hash')).toHaveTextContent(''))
  expect(screen.getByText('로그인을 취소했습니다')).toBeVisible()
})

it('says nothing when the person simply opened the login screen', async () => {
  server.use(recording().me(unauthenticated))

  renderWithProviders(<App />, { initialEntries: ['/login'] })

  await screen.findByRole('link', { name: '구글로 로그인' })
  expect(screen.queryByText('로그인을 취소했습니다')).not.toBeInTheDocument()
  expect(screen.queryByText('로그인하지 못했습니다')).not.toBeInTheDocument()
})

it('leaves a fragment it did not handle exactly where it found it', async () => {
  server.use(recording().me(unauthenticated))

  renderWithProviders(
    <>
      <App />
      <LocationHash />
    </>,
    { initialEntries: ['/login#top'] },
  )

  // A bare fragment is not a failure code, so there is nothing to say and
  // nothing to consume. Rewriting the URL here would silently break an anchor
  // or a deep link for a message we never showed.
  await screen.findByRole('link', { name: '구글로 로그인' })
  expect(screen.getByTestId('location-hash')).toHaveTextContent('#top')
  expect(screen.queryByText('로그인하지 못했습니다')).not.toBeInTheDocument()
})

it('catches a code that arrives at a screen already on the page', async () => {
  server.use(recording().me(unauthenticated))

  renderWithProviders(
    <>
      <App />
      <GoTo state={{ from: '/' }} to="/login#e=failed" />
      <LocationHash />
      <LocationState />
    </>,
    { initialEntries: ['/login'] },
  )

  await screen.findByRole('link', { name: '구글로 로그인' })
  await userEvent.click(screen.getByRole('button', { name: '이동' }))

  // Reading the code only at mount would show nothing here AND strip the
  // fragment on the way past — destroying the failure rather than missing it.
  expect(await screen.findByText('로그인하지 못했습니다')).toBeVisible()
  await waitFor(() => expect(screen.getByTestId('location-hash')).toHaveTextContent(''))
  // Clearing the fragment must not delete where the person was headed: the
  // obvious redirect-after-login is <Navigate to="/login" state={{ from }} />.
  expect(screen.getByTestId('location-state')).toHaveTextContent('{"from":"/"}')
})
