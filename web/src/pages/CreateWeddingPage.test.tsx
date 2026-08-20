import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { expect, it } from 'vitest'
import { App } from '../App'
import type { Wedding } from '../hooks/useCreateWedding'
import type { Session } from '../hooks/useSession'
import { renderWithProviders } from '../test/render'
import { server } from '../test/server'

/*
 * 웨딩 만들기, end to end inside the app: the route guard, the form, the one
 * mutation, and where the couple lands afterwards. Only the network is faked.
 *
 * WHY THIS SCREEN IS TESTED AT ALL, when a one-off screen is exempt
 * (notes/2026-08-08-decision-frontend-testing-methodology.md): it is a mutation
 * flow, and it branches on the API's error `code`. Both are on the mandatory
 * list. The layout is not what is asserted here — what is sent, what is not
 * sent, and which words a failure produces are.
 */

const API = 'http://localhost:8080'

/** Requests MSW actually received, with the JSON body each carried. */
function recording() {
  const seen: { request: Request; body: unknown }[] = []
  return {
    seen,
    /** Every wedding create the app issued, in order. */
    get created() {
      return seen.filter(({ request }) => request.url === `${API}/weddings`)
    },
    me(response: () => Response) {
      return http.get(`${API}/auth/me`, ({ request }) => {
        seen.push({ request, body: null })
        return response()
      })
    },
    /**
     * Refuses what the API refuses. A state-changing request without
     * `Content-Type: application/json` is answered 415 and never reaches the
     * endpoint (docs/api-spec.md § Every POST, PUT and PATCH must send
     * `Content-Type: application/json`), so this double answers 415 too — a
     * double more permissive than the server is how a screen that cannot
     * create anything stays green.
     */
    weddings(response: (body: unknown) => Response | Promise<Response>) {
      return http.post(`${API}/weddings`, async ({ request }) => {
        const body: unknown = await request.clone().json()
        seen.push({ request, body })
        if (request.headers.get('Content-Type') !== 'application/json')
          return problem(415, 'UNSUPPORTED_MEDIA_TYPE', 'Unsupported Media Type')
        return response(body)
      })
    },
  }
}

const signedIn = () => HttpResponse.json<Session>({ id: 12, name: '김테스터' })

/**
 * 201, echoing back what was stored — which is not always what was sent, since
 * the server trims the names (docs/api-spec.md § POST /weddings).
 */
const created = (body: unknown) =>
  HttpResponse.json<Wedding>(
    { id: 12, ...(body as Omit<Wedding, 'id'>) },
    { status: 201 },
  )

/**
 * A problem document as the API writes it. `detail` is included and deliberately
 * quotes a value back: it is the reason nothing renders `detail`, and a test
 * that omitted it could not assert that nothing does.
 */
const problem = (status: number, code: string, title: string) =>
  HttpResponse.json(
    {
      type: 'about:blank',
      title,
      status,
      detail: "Rejected value: '   '.",
      instance: '/weddings',
      code,
    },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )

/** Fill the whole form. Every field is required, so every test needs all three. */
async function fillForm({
  date = '2026-10-10',
  groom = '김신랑',
  bride = '이신부',
}: {
  date?: string
  groom?: string
  bride?: string
} = {}) {
  // Awaited unconditionally: the session resolves before any screen renders, so
  // a test that leaves a field empty still has to wait for the form to exist.
  const dateField = await screen.findByLabelText('예식일')
  if (date !== '') await userEvent.type(dateField, date)
  if (groom !== '') await userEvent.type(screen.getByLabelText('신랑 이름'), groom)
  if (bride !== '') await userEvent.type(screen.getByLabelText('신부 이름'), bride)
}

const submit = async () =>
  await userEvent.click(screen.getByRole('button', { name: '만들기' }))

const unauthenticated = () => problem(401, 'UNAUTHENTICATED', 'Unauthorized')

it('sends someone without a session to the login screen, not to the form', async () => {
  server.use(recording().me(unauthenticated))

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })

  expect(await screen.findByRole('link', { name: '구글로 로그인' })).toBeVisible()
  expect(screen.queryByLabelText('예식일')).not.toBeInTheDocument()
})

it('creates a wedding from a date and two names, and asks for nothing else', async () => {
  const calls = recording()
  server.use(calls.me(signedIn), calls.weddings(created))

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await fillForm()
  await submit()

  await waitFor(() => expect(calls.created).toHaveLength(1))
  // Asserted as an equality, not as a subset: 보증인원 and meal types are not
  // asked here and must not be sent here either — the venue's number does not
  // exist yet, and the API would ignore it silently if we invented one
  // (docs/api-spec.md § POST /weddings).
  expect(calls.created[0]?.body).toEqual({
    weddingDate: '2026-10-10',
    groomName: '김신랑',
    brideName: '이신부',
  })
  // Without it the API answers 415 and nothing is created.
  expect(calls.created[0]?.request.headers.get('Content-Type')).toBe('application/json')
  expect(calls.created[0]?.request.credentials).toBe('include')

  // The couple land where the ledger will be (#15). Today that is the home
  // screen as it stands.
  expect(await screen.findByText('김테스터 님')).toBeVisible()
})

it('trims the names it sends, because the server measures length before trimming', async () => {
  const calls = recording()
  server.use(calls.me(signedIn), calls.weddings(created))

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await fillForm({ groom: '  김신랑 ', bride: '이신부  ' })
  await submit()

  // 100 characters is measured on what is sent, before the server's own trim,
  // so a value that only passes after trimming is a 400. Trimming here is what
  // makes the two agree (docs/api-spec.md § POST /weddings).
  await waitFor(() => expect(calls.created).toHaveLength(1))
  expect(calls.created[0]?.body).toMatchObject({
    groomName: '김신랑',
    brideName: '이신부',
  })
})

it('does not spend a round trip on a name that is only whitespace', async () => {
  const calls = recording()
  server.use(calls.me(signedIn), calls.weddings(created))

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await fillForm({ groom: '   ' })
  await submit()

  expect(await screen.findByText('신랑 이름을 입력해 주세요.')).toBeVisible()
  expect(calls.created).toHaveLength(0)
})

it('names the field that is empty rather than failing the whole form at once', async () => {
  const calls = recording()
  server.use(calls.me(signedIn), calls.weddings(created))

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await fillForm({ date: '', groom: '김신랑', bride: '' })
  await submit()

  expect(await screen.findByText('예식일을 입력해 주세요.')).toBeVisible()
  expect(screen.getByText('신부 이름을 입력해 주세요.')).toBeVisible()
  expect(screen.queryByText('신랑 이름을 입력해 주세요.')).not.toBeInTheDocument()
  expect(screen.getByLabelText('신부 이름')).toHaveAttribute('aria-invalid', 'true')
  expect(calls.created).toHaveLength(0)
})

it('holds a name to the same 100 characters the server does', async () => {
  const calls = recording()
  server.use(calls.me(signedIn), calls.weddings(created))

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await fillForm({ groom: '김'.repeat(101) })
  await submit()

  expect(await screen.findByText('이름은 100자까지 쓸 수 있습니다.')).toBeVisible()
  expect(calls.created).toHaveLength(0)
})

it('accepts a wedding date in the past, exactly as the API does', async () => {
  const calls = recording()
  server.use(calls.me(signedIn), calls.weddings(created))

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  // A couple building the ledger after the fact is a real case, and there is no
  // product bound on the date at all. A "future only" rule here would refuse
  // people the server accepts (docs/api-spec.md § POST /weddings).
  await fillForm({ date: '2020-01-01' })
  await submit()

  await waitFor(() => expect(calls.created).toHaveLength(1))
  expect(calls.created[0]?.body).toMatchObject({ weddingDate: '2020-01-01' })
})

/*
 * Two codes, one meaning for the user: the request was wrong. They differ
 * because one failure happens while the body is being read and the other after,
 * and the spec is explicit that no different UI is built for them
 * (docs/api-spec.md § POST /weddings).
 */
for (const code of ['VALIDATION_FAILED', 'MALFORMED_REQUEST_BODY']) {
  it(`says the same thing for ${code} as for the other 400`, async () => {
    const calls = recording()
    server.use(
      calls.me(signedIn),
      calls.weddings(() => problem(400, code, 'Bad Request')),
    )

    renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
    await fillForm()
    await submit()

    expect(await screen.findByText('예식일과 이름을 다시 확인해 주세요.')).toBeVisible()
    // `detail` is an English diagnostic that quotes the submitted value back;
    // rendering it would paint attacker-supplied text onto the screen.
    expect(document.body.textContent).not.toContain('Rejected value')
    // What they typed is still there — retyping it is the couple's work, not
    // ours to throw away.
    expect(screen.getByLabelText('신랑 이름')).toHaveValue('김신랑')
  })
}

it('sends an expired session back to log in instead of reporting an error', async () => {
  const calls = recording()
  let signedOut = false
  server.use(
    calls.me(() => (signedOut ? unauthenticated() : signedIn())),
    calls.weddings(() => {
      signedOut = true
      return unauthenticated()
    }),
  )

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await fillForm()
  await submit()

  // A 401 means "log in again" and is never an error state to report
  // (docs/api-spec.md § Authentication).
  expect(await screen.findByRole('link', { name: '구글로 로그인' })).toBeVisible()
  expect(screen.queryByText(/다시 시도해 주세요/)).not.toBeInTheDocument()
})

it('reports a failed create without inventing a reason for it', async () => {
  const calls = recording()
  server.use(
    calls.me(signedIn),
    calls.weddings(() => problem(500, 'INTERNAL_ERROR', 'Internal Server Error')),
  )

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await fillForm()
  await submit()

  // A 5xx says nothing about what went wrong, by design — so there is nothing
  // to explain and only something to try again.
  expect(
    await screen.findByText('웨딩을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.'),
  ).toBeVisible()
  expect(screen.getByLabelText('예식일')).toHaveValue('2026-10-10')
})

it('creates one wedding when the button is pressed twice', async () => {
  const calls = recording()
  let release = () => {}
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  server.use(
    calls.me(signedIn),
    calls.weddings(async (body) => {
      await held
      return created(body)
    }),
  )

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await fillForm()
  await submit()
  await submit()

  // A mutation here is not idempotent and is never retried; a second POST would
  // put a second wedding in the ledger list nobody asked for. The API accepts a
  // second wedding by the same person on purpose, so nothing on the server side
  // would refuse this one.
  expect(calls.created).toHaveLength(1)
  release()
  expect(await screen.findByText('김테스터 님')).toBeVisible()
})
