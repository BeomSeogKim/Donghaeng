import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { expect, it } from 'vitest'
import { App } from '../App'
import type { CreateWeddingRequest } from '../hooks/useCreateWedding'
import type { Headcount } from '../hooks/useHeadcount'
import type { Session } from '../hooks/useSession'
import type { Wedding } from '../hooks/useWeddings'
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
    logout(response: () => Response) {
      return http.post(`${API}/auth/logout`, ({ request }) => {
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
 * `GET /weddings` as the server actually behaves: empty until a create succeeds,
 * and holding the wedding afterwards.
 *
 * IT HAS TO BE STATEFUL NOW THAT THE ROUTE IS GUARDED. This screen reads the
 * list before it renders anything and sends a person who already has a wedding
 * to 원장, so a double that answered "you have one" would redirect the form away
 * before a test could fill it in — and one that answered `[]` forever would
 * bounce the couple straight back here from the ledger they just landed on.
 */
function weddingStore(...stored: Wedding[]) {
  return {
    list: () =>
      http.get(`${API}/weddings`, () => HttpResponse.json<Wedding[]>([...stored])),
    /**
     * 201, echoing back what was stored — which is not always what was sent,
     * since the server trims the name (docs/api-spec.md § POST /weddings).
     *
     * IT CREATES BOTH SEATS, because the server does. The caller's seat gets the
     * name they sent; the partner's gets a side and nothing else, and that is the
     * ordinary state of a new wedding rather than a half-built one. A double that
     * returned one seat would let a header which cannot render the empty half
     * stay green.
     */
    create: (body: unknown) => {
      const { name, side, weddingDate } = body as CreateWeddingRequest
      const wedding: Wedding = {
        id: 12,
        weddingDate,
        // 신랑 먼저, always — the array's order is contract.
        seats: [
          { side: 'GROOM', name: side === 'GROOM' ? name : null },
          { side: 'BRIDE', name: side === 'BRIDE' ? name : null },
        ],
      }
      stored.unshift(wedding)
      return HttpResponse.json<Wedding>(wedding, { status: 201 })
    },
  }
}

/** The list of a person who has not made a wedding yet — the form's own case. */
const noWedding = () => weddingStore().list()

/** 원장's other two reads. An unhandled request is an error in this suite. */
const guests = () =>
  http.get(`${API}/weddings/:weddingId/guests`, () => HttpResponse.json([]))

const headcount = () =>
  http.get(`${API}/weddings/:weddingId/headcount`, () =>
    HttpResponse.json<Headcount>({ mealHeadcount: 0 }),
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

/**
 * Fill the whole form. All three answers are required, so every test needs them.
 *
 * `side` is a CLICK, not a typed value, and passing `''` leaves it as the screen
 * opens: neither side chosen. There is no default to fall back on, deliberately.
 */
async function fillForm({
  date = '2026-10-10',
  side = '신랑',
  name = '김신랑',
}: {
  date?: string
  side?: '신랑' | '신부' | ''
  name?: string
} = {}) {
  // Awaited unconditionally: the session resolves before any screen renders, so
  // a test that leaves a field empty still has to wait for the form to exist.
  const dateField = await screen.findByLabelText('예식일')
  if (date !== '') await userEvent.type(dateField, date)
  if (side !== '')
    await userEvent.click(screen.getByRole('radio', { name: `${side}입니다` }))
  if (name !== '') await userEvent.type(screen.getByLabelText('내 이름'), name)
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

it('sends a person who already has a wedding to 원장 instead of the form', async () => {
  const calls = recording()
  const store = weddingStore({
    id: 12,
    weddingDate: '2026-10-10',
    seats: [
      { side: 'GROOM', name: '김신랑' },
      { side: 'BRIDE', name: '이신부' },
    ],
  })
  server.use(calls.me(signedIn), calls.weddings(store.create), store.list(), guests())

  // A bookmark, a typed URL, or a second tab still parked on the form after the
  // first one submitted. The server refuses a second wedding by the same person
  // — 409 ALREADY_IN_A_WEDDING — so this guard is the fast path rather than the
  // only thing standing there, and a person who already has one is taken to it.
  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })

  expect(await screen.findByRole('region', { name: '인원수' })).toBeVisible()
  expect(screen.queryByLabelText('예식일')).not.toBeInTheDocument()
  expect(calls.created).toHaveLength(0)
})

it('does not offer the form when it could not find out whether they have a wedding', async () => {
  const calls = recording()
  server.use(
    calls.me(signedIn),
    http.get(`${API}/weddings`, () =>
      problem(500, 'INTERNAL_ERROR', 'Internal Server Error'),
    ),
  )

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })

  // The cost is asymmetric: a retry costs a tap, and a form offered to someone
  // who already has a wedding costs them the ledger.
  expect(await screen.findByText('결혼식 정보를 불러오지 못했습니다')).toBeVisible()
  expect(screen.queryByLabelText('예식일')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '다시 시도' })).toBeVisible()
})

it('lets a person with no wedding sign out, because this screen is where they are parked', async () => {
  const calls = recording()
  let signedOut = false
  server.use(
    calls.me(() => (signedOut ? unauthenticated() : signedIn())),
    calls.logout(() => {
      signedOut = true
      return new HttpResponse(null, { status: 204 })
    }),
    noWedding(),
  )

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await screen.findByLabelText('예식일')

  // An empty list is also what a removed membership looks like, so this screen
  // is not only 최초 1회 — it can be where someone lives, and a screen a
  // signed-in person cannot leave is not a screen (docs/api-spec.md § GET /weddings).
  await userEvent.click(screen.getByRole('button', { name: '로그아웃' }))

  expect(await screen.findByRole('link', { name: '구글로 로그인' })).toBeVisible()
})

it('tells an invited partner not to make a wedding here', async () => {
  const calls = recording()
  server.use(calls.me(signedIn), noWedding())

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await screen.findByLabelText('예식일')

  /*
   * THIS IS WHERE THE IN-APP-BROWSER DEAD END LANDS. If the Google round trip
   * left the browser the link was opened in, the tab that comes back is not the
   * tab that stashed the token, `sessionStorage` is empty, and the empty
   * `GET /weddings` of a partner who has not accepted sends them exactly here.
   * The failure is safe right up until they fill this form in, and then it is
   * permanent (`#158`). The link is good for a day, so the recovery is one
   * sentence long.
   */
  expect(screen.getByText(/초대 링크를 받았다면/)).toBeVisible()
})

it("creates a wedding from a date, a side and the caller's own name", async () => {
  const calls = recording()
  const store = weddingStore()
  server.use(calls.me(signedIn), calls.weddings(store.create), store.list(), guests())

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
    side: 'GROOM',
    name: '김신랑',
  })
  // Without it the API answers 415 and nothing is created.
  expect(calls.created[0]?.request.headers.get('Content-Type')).toBe('application/json')
  expect(calls.created[0]?.request.credentials).toBe('include')

  // The couple land on 원장 (`#15`), which is home — and they land ON it rather
  // than being bounced back here by a wedding list the client fetched one
  // request ago and which still says they have none.
  expect(await screen.findByRole('region', { name: '인원수' })).toBeVisible()
})

it('trims the name it sends, because the server measures length before trimming', async () => {
  const calls = recording()
  server.use(calls.me(signedIn), calls.weddings(weddingStore().create), noWedding())

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await fillForm({ name: '  김신랑 ' })
  await submit()

  // 100 characters is measured on what is sent, before the server's own trim,
  // so a value that only passes after trimming is a 400. Trimming here is what
  // makes the two agree (docs/api-spec.md § POST /weddings).
  await waitFor(() => expect(calls.created).toHaveLength(1))
  expect(calls.created[0]?.body).toMatchObject({ name: '김신랑' })
})

it('does not spend a round trip on a name that is only whitespace', async () => {
  const calls = recording()
  server.use(calls.me(signedIn), calls.weddings(weddingStore().create), noWedding())

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await fillForm({ name: '   ' })
  await submit()

  expect(await screen.findByText('이름을 입력해 주세요.')).toBeVisible()
  expect(calls.created).toHaveLength(0)
})

it('starts with neither side chosen, and will not guess one', async () => {
  const calls = recording()
  server.use(calls.me(signedIn), calls.weddings(weddingStore().create), noWedding())

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await screen.findByLabelText('예식일')

  // 신랑인지 신부인지는 이 사람이 우리에게 처음 말해 주는 사실이고, 기본값으로
  // 쓸 만한 쪽이 없다. A preselected side is a wrong answer half the time, and a
  // wrong one here writes the wrong name onto the wrong seat of a ledger.
  for (const label of ['신랑입니다', '신부입니다'])
    expect(screen.getByRole('radio', { name: label })).not.toBeChecked()

  await fillForm({ side: '' })
  await submit()

  expect(await screen.findByText('신랑인지 신부인지 골라 주세요.')).toBeVisible()
  expect(calls.created).toHaveLength(0)
})

it("sends the seat the caller chose, and never their partner's", async () => {
  const calls = recording()
  const store = weddingStore()
  server.use(calls.me(signedIn), calls.weddings(store.create), store.list(), guests())

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await fillForm({ side: '신부', name: '이신부' })
  await submit()

  // There is no way to send a partner's name from this screen, and that is the
  // point: their seat is created empty and an invite fills it (`#9`), so nobody
  // types anybody else's name (docs/api-spec.md § POST /weddings).
  await waitFor(() => expect(calls.created).toHaveLength(1))
  expect(calls.created[0]?.body).toEqual({
    weddingDate: '2026-10-10',
    side: 'BRIDE',
    name: '이신부',
  })

  // And the ledger they land on says so, in as many words.
  expect(await screen.findByText(/신랑 자리 비어 있음 · 이신부/)).toBeVisible()
})

it('names the field that is empty rather than failing the whole form at once', async () => {
  const calls = recording()
  server.use(calls.me(signedIn), calls.weddings(weddingStore().create), noWedding())

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await fillForm({ date: '', side: '신랑', name: '' })
  await submit()

  expect(await screen.findByText('예식일을 입력해 주세요.')).toBeVisible()
  expect(screen.getByText('이름을 입력해 주세요.')).toBeVisible()
  expect(screen.queryByText('신랑인지 신부인지 골라 주세요.')).not.toBeInTheDocument()
  expect(screen.getByLabelText('내 이름')).toHaveAttribute('aria-invalid', 'true')
  expect(calls.created).toHaveLength(0)
})

it('holds a name to the same 100 characters the server does', async () => {
  const calls = recording()
  server.use(calls.me(signedIn), calls.weddings(weddingStore().create), noWedding())

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await fillForm({ name: '김'.repeat(101) })
  await submit()

  expect(await screen.findByText('이름은 100자까지 쓸 수 있습니다.')).toBeVisible()
  expect(calls.created).toHaveLength(0)
})

it('accepts a wedding date in the past, exactly as the API does', async () => {
  const calls = recording()
  server.use(calls.me(signedIn), calls.weddings(weddingStore().create), noWedding())

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
      noWedding(),
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
    expect(screen.getByLabelText('내 이름')).toHaveValue('김신랑')
    expect(screen.getByRole('radio', { name: '신랑입니다' })).toBeChecked()
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
    noWedding(),
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
    noWedding(),
  )

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await fillForm()
  await submit()

  // A 5xx says nothing about what went wrong, by design — so there is nothing
  // to explain and only something to try again.
  expect(
    await screen.findByText('결혼식을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.'),
  ).toBeVisible()
  expect(screen.getByLabelText('예식일')).toHaveValue('2026-10-10')
})

it('creates one wedding when the button is pressed twice', async () => {
  const calls = recording()
  let release = () => {}
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  const store = weddingStore()
  server.use(
    calls.me(signedIn),
    calls.weddings(async (body) => {
      await held
      return store.create(body)
    }),
    store.list(),
    guests(),
  )

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await fillForm()
  await submit()
  await submit()

  // A mutation here is not idempotent and is never retried. The server refuses
  // the second one — 409 ALREADY_IN_A_WEDDING, since `#158` — and the client now
  // answers that by opening the wedding they already have (`#164`); this guard
  // is what keeps an ordinary double press from taking that detour at all.
  expect(calls.created).toHaveLength(1)
  release()
  expect(await screen.findByRole('region', { name: '인원수' })).toBeVisible()
})

it('opens the wedding it turns out they already have', async () => {
  const calls = recording()
  // The other tab won the race. By the time this one submits, the wedding
  // exists and the database refuses the second row, so this caller is told
  // 409 and never a 500 (docs/api-spec.md § POST /weddings).
  const other: Wedding = {
    id: 12,
    weddingDate: '2026-10-10',
    seats: [
      { side: 'GROOM', name: '김신랑' },
      { side: 'BRIDE', name: null },
    ],
  }
  const stored: Wedding[] = []
  server.use(
    calls.me(signedIn),
    calls.weddings(() => {
      stored.push(other)
      return problem(409, 'ALREADY_IN_A_WEDDING', 'Conflict')
    }),
    http.get(`${API}/weddings`, () => HttpResponse.json<Wedding[]>([...stored])),
    guests(),
    headcount(),
  )

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await fillForm()
  await submit()

  /*
   * THE RECOVERY IS NEITHER A RETRY NOR AN ERROR SCREEN. The spec's answer is
   * to call `GET /weddings` and open the one that comes back, and to the couple
   * that is not a failure at all — "이미 있으니 그걸 열었다"
   * (docs/api-spec.md § POST /weddings).
   */
  expect(await screen.findByRole('region', { name: '인원수' })).toBeVisible()
  expect(screen.queryByText(/다시 시도해 주세요/)).not.toBeInTheDocument()
  // Never retried: a second create is a second wedding the moment it succeeds.
  expect(calls.created).toHaveLength(1)
})

it('says so when the list disagrees, rather than handing back the same form', async () => {
  const calls = recording()
  // The 409 says they hold a wedding and `GET /weddings` says they hold none —
  // a replica read, a late cache. Rare, which is the argument FOR words: nobody
  // who lands here can reproduce it or guess at it.
  server.use(
    calls.me(signedIn),
    calls.weddings(() => problem(409, 'ALREADY_IN_A_WEDDING', 'Conflict')),
    noWedding(),
  )

  renderWithProviders(<App />, { initialEntries: ['/weddings/new'] })
  await fillForm()
  await submit()

  // The same sentence 초대 수락 says, from the same code — one 409, one answer.
  expect(await screen.findByText('이미 다른 결혼식에 속해 있습니다')).toBeVisible()
  expect(screen.getByRole('link', { name: '내 하객 명부 열기' })).toBeVisible()
  // 로그아웃 where it can be seen: on a shared phone, being signed in as the
  // partner is one of the ways to arrive here, and then it is the recovery.
  expect(screen.getByRole('button', { name: '로그아웃' })).toBeVisible()
  // A form that can only be refused again is not left on screen.
  expect(screen.queryByLabelText('예식일')).not.toBeInTheDocument()
  expect(calls.created).toHaveLength(1)
})
