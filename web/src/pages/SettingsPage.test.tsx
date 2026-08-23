import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { expect, it } from 'vitest'
import { App } from '../App'
import { type Headcount, headcountQueryKey } from '../hooks/useHeadcount'
import type { Session } from '../hooks/useSession'
import { type Wedding, weddingsQueryKey } from '../hooks/useWeddings'
import type { paths } from '../lib/api-types.gen'
import { renderWithProviders } from '../test/render'
import { server } from '../test/server'

/*
 * 설정 · 웨딩 정보 — the screen 보증인원 enters the product through, end to end
 * inside the app, with only the network faked.
 *
 * THESE ARE MANDATORY TESTS. A mutation flow and the headcount display are both
 * named in notes/2026-08-08-decision-frontend-testing-methodology.md, and this
 * screen writes the one number the couple did not get from us: 보증인원 is what
 * they agreed with their venue.
 *
 * WHAT IS ASSERTED IS THE BODY THAT REACHED THE SERVER. This is the product's
 * first partial update and its rules are invisible from the screen: an omitted
 * member is left alone, `null` clears, and `""` is a 400 that used to be a 200
 * that silently erased the venue's number
 * (notes/2026-08-22-decision-partial-update-shape.md). None of that shows up in
 * rendered text, so none of it is asserted there.
 */

const API = 'http://localhost:8080'

type UpdateWedding = paths['/weddings/{weddingId}']['patch']
type UpdateWeddingRequest = UpdateWedding['requestBody']['content']['application/json']
/** `{wedding, headcount}` — the wedding as it now stands, and the recomputed 인원수. */
type WeddingMutation = UpdateWedding['responses'][200]['content']['*/*']

const SEATS: Wedding['seats'] = [
  { side: 'GROOM', name: '김신랑' },
  { side: 'BRIDE', name: '이신부' },
]

/**
 * The wedding as the server keeps it, and the 인원수 read against it.
 *
 * IT APPLIES THE PARTIAL-UPDATE RULES THE API APPLIES, including the refusals:
 * a `""` where a number belongs is a 400 `MALFORMED_REQUEST_BODY` and not a
 * clear, and a `weddingDate` sent as `null` is a 400 rather than a wedding with
 * no date (docs/api-spec.md § Partial updates). A double that accepted either
 * would let the exact bug this screen was written against stay green.
 *
 * 보증인원 IS PUBLISHED ONLY BY THE HEADCOUNT, never by the wedding — one number
 * may not be spelled twice in one response — so the wedding this double returns
 * has no member for it to be prefilled from.
 */
function weddingApi(
  initial: {
    weddingDate?: string
    guaranteedHeadcount?: number
    mealHeadcount?: number
  } = {},
) {
  let weddingDate = initial.weddingDate ?? '2026-10-10'
  let guaranteed: number | null = initial.guaranteedHeadcount ?? null
  const mealHeadcount = initial.mealHeadcount ?? 128

  const saved: { request: Request; body: UpdateWeddingRequest; raw: string }[] = []
  const headcountRequests: URL[] = []
  const weddingRequests: URL[] = []

  const wedding = (): Wedding => ({ id: 12, weddingDate, seats: SEATS })
  const count = (): Headcount =>
    guaranteed === null
      ? { mealHeadcount }
      : { mealHeadcount, guaranteedHeadcount: guaranteed }

  return {
    saved,
    headcountRequests,
    weddingRequests,
    /** What the last save asked the server to write. */
    lastSaved: () => saved[saved.length - 1],
    stored: () => ({ weddingDate, guaranteedHeadcount: guaranteed }),
    /**
     * The partner, editing the same wedding from their own device.
     *
     * TWO ACCOUNTS WITH THE SAME SCREEN OPEN IS THIS PRODUCT'S STANDING
     * SCENARIO, not an exotic race — it is the sentence
     * notes/2026-08-22-decision-partial-update-shape.md argues the whole partial
     * update from. The double changes underneath the form exactly as the server
     * would, so the next response the form is handed carries a member it never
     * sent.
     */
    partner: (edit: { weddingDate?: string; guaranteedHeadcount?: number | null }) => {
      if (edit.weddingDate !== undefined) weddingDate = edit.weddingDate
      if (edit.guaranteedHeadcount !== undefined) guaranteed = edit.guaranteedHeadcount
    },
    me: () =>
      http.get(`${API}/auth/me`, () =>
        HttpResponse.json<Session>({ id: 7, name: '김테스터' }),
      ),
    weddings: (respond?: () => Response) =>
      http.get(`${API}/weddings`, ({ request }) => {
        weddingRequests.push(new URL(request.url))
        return respond === undefined
          ? HttpResponse.json<Wedding[]>([wedding()])
          : respond()
      }),
    headcount: (respond?: () => Response) =>
      http.get(`${API}/weddings/:weddingId/headcount`, ({ request }) => {
        headcountRequests.push(new URL(request.url))
        return respond === undefined ? HttpResponse.json<Headcount>(count()) : respond()
      }),
    /** `PATCH /weddings/{weddingId}`, refusing exactly what the endpoint refuses. */
    update: (respond?: () => Response) =>
      http.patch(`${API}/weddings/:weddingId`, async ({ request }) => {
        const raw = await request.clone().text()
        const body = JSON.parse(raw) as UpdateWeddingRequest
        saved.push({ request, body, raw })

        if (request.headers.get('Content-Type') !== 'application/json')
          return problem(415, 'UNSUPPORTED_MEDIA_TYPE')
        if (respond !== undefined) return respond()

        if ('weddingDate' in body) {
          const sent: unknown = body.weddingDate
          // 예식일 has no cleared state: a wedding always has a date.
          if (sent === null) return problem(400, 'VALIDATION_FAILED')
          if (typeof sent !== 'string' || sent === '')
            return problem(400, 'MALFORMED_REQUEST_BODY')
          weddingDate = sent
        }
        if ('guaranteedHeadcount' in body) {
          const sent: unknown = body.guaranteedHeadcount
          // `null` is the ONLY spelling of "clear". `""` is a body that could
          // not be read, and answering 200 to it is what erased a couple's
          // 보증인원 before `#173`'s review caught it.
          if (sent === null) guaranteed = null
          else if (typeof sent !== 'number' || !Number.isInteger(sent))
            return problem(400, 'MALFORMED_REQUEST_BODY')
          else if (sent < 1) return problem(400, 'VALIDATION_FAILED')
          else guaranteed = sent
        }

        return HttpResponse.json<WeddingMutation>({
          wedding: wedding(),
          headcount: count(),
        })
      }),
  }
}

/**
 * A problem document as the API writes it. `detail` quotes the submitted value
 * back on purpose: it is why nothing renders `detail`, and a test that left it
 * out could not assert that nothing does.
 */
const problem = (status: number, code: string) =>
  HttpResponse.json(
    {
      type: 'about:blank',
      title: 'Error',
      status,
      detail: "Rejected value: '   '.",
      instance: '/weddings/12',
      code,
    },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )

/** The 웨딩 정보 form, once it has been prefilled from the two reads. */
async function form() {
  const date = await screen.findByLabelText('예식일')
  return {
    date: date as HTMLInputElement,
    guaranteed: screen.getByLabelText('보증인원') as HTMLInputElement,
    fill: async (field: HTMLInputElement, value: string) => {
      await userEvent.clear(field)
      if (value !== '') await userEvent.type(field, value)
    },
    save: () => userEvent.click(screen.getByRole('button', { name: '저장' })),
  }
}

it('prefills 보증인원 from the headcount, which is the only place it is published', async () => {
  const api = weddingApi({ weddingDate: '2027-03-14', guaranteedHeadcount: 150 })
  server.use(api.me(), api.weddings(), api.headcount(), api.update())

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()

  // The wedding carries the date; `WeddingResponse` has no `guaranteedHeadcount`
  // member and is not gaining one (docs/api-spec.md § GET /weddings/{weddingId}).
  expect(fields.date).toHaveValue('2027-03-14')
  expect(fields.guaranteed).toHaveValue('150')
  expect(api.headcountRequests).toHaveLength(1)
})

it('reads 미설정 as the ordinary state it is, not as something that went wrong', async () => {
  const api = weddingApi()
  server.use(api.me(), api.weddings(), api.headcount(), api.update())

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()

  expect(fields.guaranteed).toHaveValue('')
  // Not an error, and not a number of ours standing in for the venue's.
  expect(screen.queryByRole('alert')).toBeNull()
  expect(screen.queryByText('128')).toBeNull()
})

it('sends only the member the couple changed, and leaves the other alone', async () => {
  const api = weddingApi({ weddingDate: '2027-03-14' })
  server.use(api.me(), api.weddings(), api.headcount(), api.update())

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()
  await fields.fill(fields.guaranteed, '150')
  await fields.save()

  await waitFor(() => expect(api.saved).toHaveLength(1))
  /*
   * AN EQUALITY, NOT A SUBSET, AND `weddingDate` IS THE POINT OF IT. A body
   * carrying the date the form happened to load blind-writes whatever the
   * partner changed it to in the meantime, and `wedding` has no audit trail to
   * recover it from (notes/2026-08-22-decision-partial-update-shape.md).
   */
  expect(api.lastSaved()?.body).toEqual({ guaranteedHeadcount: 150 })
  expect(api.lastSaved()?.request.headers.get('Content-Type')).toBe('application/json')
  expect(api.lastSaved()?.request.credentials).toBe('include')
  expect(api.stored()).toEqual({ weddingDate: '2027-03-14', guaranteedHeadcount: 150 })
})

it('clears 보증인원 with null when the couple empties the field, never with ""', async () => {
  const api = weddingApi({ guaranteedHeadcount: 150 })
  server.use(api.me(), api.weddings(), api.headcount(), api.update())

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()
  await fields.fill(fields.guaranteed, '')
  await fields.save()

  await waitFor(() => expect(api.saved).toHaveLength(1))
  /*
   * THE TWO WAYS TO GET THIS WRONG, AND `toEqual` REFUSES BOTH. `""` is what a
   * blanked input serialises to and is a 400; `undefined` is dropped by
   * JSON.stringify altogether, which turns "지운다" into "그대로 둔다" and
   * answers 200 having written nothing. The member must be present AND null.
   */
  expect(api.lastSaved()?.body).toEqual({ guaranteedHeadcount: null })
  expect(api.lastSaved()?.raw).toBe('{"guaranteedHeadcount":null}')
  expect(api.stored().guaranteedHeadcount).toBeNull()

  // 미설정 is a state a couple can arrive back at, and it reads like one.
  expect(fields.guaranteed).toHaveValue('')
  expect(await screen.findByText('저장했습니다.')).toBeVisible()
})

it('does not ask to clear a 보증인원 that was never there', async () => {
  const api = weddingApi()
  server.use(api.me(), api.weddings(), api.headcount(), api.update())

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()
  await fields.save()

  // `{}` is a legal 200 no-op that does not even move `updated_at`, so a form
  // submitted with nothing edited needs no special case on this side either.
  await waitFor(() => expect(api.saved).toHaveLength(1))
  expect(api.lastSaved()?.body).toEqual({})
})

it('refuses to send a blank 예식일, because a wedding always has a date', async () => {
  const api = weddingApi()
  server.use(api.me(), api.weddings(), api.headcount(), api.update())

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()
  await fields.fill(fields.date, '')
  await fields.save()

  // 예식일 cannot be cleared — `null` is a 400 — so the request is not spent.
  expect(await screen.findByText('예식일을 입력해 주세요.')).toBeVisible()
  expect(api.saved).toHaveLength(0)
})

it('sends the date on its own when that is what moved', async () => {
  const api = weddingApi({ guaranteedHeadcount: 150 })
  server.use(api.me(), api.weddings(), api.headcount(), api.update())

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()
  await fields.fill(fields.date, '2027-03-14')
  await fields.save()

  await waitFor(() => expect(api.saved).toHaveLength(1))
  expect(api.lastSaved()?.body).toEqual({ weddingDate: '2027-03-14' })
  expect(api.stored().guaranteedHeadcount).toBe(150)
})

it('will not spend a round trip on a 보증인원 the API would refuse', async () => {
  const api = weddingApi()
  server.use(api.me(), api.weddings(), api.headcount(), api.update())

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()
  await fields.fill(fields.guaranteed, '0')
  await fields.save()

  expect(await screen.findByText('보증인원은 1명 이상으로 입력해 주세요.')).toBeVisible()
  expect(api.saved).toHaveLength(0)
})

it('says what is wrong with a 보증인원 that is not a number, rather than dropping it', async () => {
  const api = weddingApi({ guaranteedHeadcount: 150 })
  server.use(api.me(), api.weddings(), api.headcount(), api.update())

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()
  await fields.fill(fields.guaranteed, '백오십')
  await fields.save()

  /*
   * A `type="number"` INPUT WOULD MAKE THIS DANGEROUS, which is why the field is
   * not one: a browser hands back `""` for a value it cannot parse, so a
   * mistyped 보증인원 would read as a blanked field and CLEAR the venue's
   * number. What was typed stays on screen and is named instead.
   */
  expect(await screen.findByText('보증인원은 숫자로 입력해 주세요.')).toBeVisible()
  expect(fields.guaranteed).toHaveValue('백오십')
  expect(api.saved).toHaveLength(0)
})

it('takes the number off the save, and does not ask the headcount again', async () => {
  const api = weddingApi()
  server.use(api.me(), api.weddings(), api.headcount(), api.update())

  const { queryClient } = renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()
  await fields.fill(fields.guaranteed, '150')
  await fields.save()

  expect(await screen.findByText('저장했습니다.')).toBeVisible()
  /*
   * The response carries the 인원수 recomputed inside the same transaction as
   * the write, so 원장 has the venue's number the moment the couple walks back
   * to it. A `GET .../headcount` fired after the PATCH is refused by the spec in
   * as many words, and it is what puts the out-of-order race back
   * (docs/api-spec.md § PATCH /weddings/{weddingId}).
   */
  expect(queryClient.getQueryData(headcountQueryKey(12))).toEqual({
    mealHeadcount: 128,
    guaranteedHeadcount: 150,
  })
  expect(api.headcountRequests).toHaveLength(1)
})

it('never lets a read already in flight overwrite the number the save returned', async () => {
  const api = weddingApi()
  let releaseStale = () => {}
  const stale = new Promise<void>((resolve) => {
    releaseStale = resolve
  })
  let reads = 0
  server.use(
    api.me(),
    api.weddings(),
    /*
     * The second read is the one a couple tabbing back from KakaoTalk starts —
     * `staleTime: 0` and `refetchOnWindowFocus` are both deliberate — and it was
     * computed BEFORE the save, so it answers a wedding with no 보증인원 at all.
     */
    http.get(`${API}/weddings/:weddingId/headcount`, async () => {
      reads += 1
      if (reads > 1) await stale
      return HttpResponse.json<Headcount>({ mealHeadcount: 128 })
    }),
    api.update(),
  )

  const { queryClient } = renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()

  void queryClient.refetchQueries({ queryKey: headcountQueryKey(12) })
  await waitFor(() => expect(reads).toBe(2))

  await fields.fill(fields.guaranteed, '150')
  await fields.save()
  expect(await screen.findByText('저장했습니다.')).toBeVisible()

  // The older answer lands now, and it has no 보증인원 in it. Without the cancel
  // in `setHeadcount` this is where the number the couple just agreed with their
  // venue disappears again.
  releaseStale()
  await act(async () => {
    await new Promise((settle) => setTimeout(settle, 20))
  })

  expect(queryClient.getQueryData(headcountQueryKey(12))).toEqual({
    mealHeadcount: 128,
    guaranteedHeadcount: 150,
  })
})

it('writes once for a double press, not twice', async () => {
  const api = weddingApi()
  let release = () => {}
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  server.use(
    api.me(),
    api.weddings(),
    api.headcount(),
    http.patch(`${API}/weddings/:weddingId`, async ({ request }) => {
      api.saved.push({
        request,
        body: (await request.clone().json()) as UpdateWeddingRequest,
        raw: '',
      })
      await held
      return HttpResponse.json<WeddingMutation>({
        wedding: { id: 12, weddingDate: '2026-10-10', seats: SEATS },
        headcount: { mealHeadcount: 128, guaranteedHeadcount: 150 },
      })
    }),
  )

  const { queryClient } = renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()
  await fields.fill(fields.guaranteed, '150')
  await fields.save()
  await waitFor(() => expect(api.saved).toHaveLength(1))

  /*
   * SERIALISING MUTATIONS DELAYS A SECOND PRESS; IT NEVER REFUSES ONE. The scope
   * default queues it behind the first and then sends it, so what stops a second
   * write is the handler's `isPending` guard and the disabled button in front of
   * it (notes/2026-08-21-decision-query-defaults-and-mutation-ordering.md,
   * amended 2026-08-22). This asserts the property — one press, one write — and
   * cannot tell those two apart: a disabled submit button takes no click and no
   * implicit Enter either, so there is no user action that reaches the guard on
   * its own. It is kept because the guard is what holds if the button ever stops
   * being disabled.
   *
   * COUNTED AFTER THE QUEUE HAS DRAINED, which is the only moment that proves
   * anything: a press made while the first is in flight has not reached the
   * network yet, so asserting before the drain passes with no guard at all.
   */
  await fields.save()
  release()
  expect(await screen.findByText('저장했습니다.')).toBeVisible()
  await waitFor(() => expect(queryClient.isMutating()).toBe(0))
  expect(api.saved).toHaveLength(1)
})

it('catches the screen up when a save commits and its response is lost', async () => {
  const api = weddingApi()
  server.use(
    api.me(),
    api.weddings(),
    api.headcount(),
    http.patch(`${API}/weddings/:weddingId`, () => HttpResponse.error()),
  )

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()
  await waitFor(() => expect(api.weddingRequests).toHaveLength(1))
  await fields.fill(fields.date, '2027-03-14')
  await fields.save()

  /*
   * A PATCH can commit on the server and lose its response — a dropped
   * connection, a timeout — and mutations are `retry: 0`, so `onSuccess` never
   * runs and the screen would sit on a date the server no longer holds. The
   * refetch is in `onSettled` for exactly that (`#135`'s review,
   * notes/2026-08-21-decision-query-defaults-and-mutation-ordering.md).
   */
  await waitFor(() => expect(api.weddingRequests.length).toBeGreaterThan(1))
  // And the number is asked for again too, because after a lost response we do
  // not know whether 보증인원 was written — which is the one case where the
  // response we would otherwise trust does not exist.
  await waitFor(() => expect(api.headcountRequests.length).toBeGreaterThan(1))
  expect(await screen.findByRole('alert')).toBeVisible()
})

it('offers a failed save again rather than explaining it away', async () => {
  const api = weddingApi()
  server.use(
    api.me(),
    api.weddings(),
    api.headcount(),
    api.update(() => problem(400, 'MALFORMED_REQUEST_BODY')),
  )

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()
  await fields.fill(fields.guaranteed, '150')
  await fields.save()

  const failure = await screen.findByRole('alert')
  expect(failure).toHaveTextContent('저장하지 못했습니다. 입력한 내용을 확인해 주세요.')
  // `detail` quotes the submitted value back and is never rendered.
  expect(screen.queryByText(/Rejected value/)).toBeNull()
  // What was typed is still there to try again with.
  expect(fields.guaranteed).toHaveValue('150')
})

it('sends a 401 on the save back to log in, not to a message on the form', async () => {
  const api = weddingApi()
  server.use(
    api.me(),
    api.weddings(),
    api.headcount(),
    api.update(() => problem(401, 'UNAUTHENTICATED')),
  )

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()
  await fields.fill(fields.guaranteed, '150')
  await fields.save()

  expect(await screen.findByRole('link', { name: /구글/ })).toBeVisible()
})

it('says the wedding could not be read rather than offering an empty form', async () => {
  const api = weddingApi()
  server.use(
    api.me(),
    api.weddings(),
    api.headcount(() => problem(500, 'INTERNAL_ERROR')),
    api.update(),
  )

  renderWithProviders(<App />, { initialEntries: ['/settings'] })

  // A form prefilled from a read that failed would offer a blank 보증인원 —
  // which on this screen is not "unknown", it is "clear it".
  expect(await screen.findByText('결혼식 정보를 불러오지 못했습니다')).toBeVisible()
  expect(screen.queryByLabelText('보증인원')).toBeNull()

  server.use(api.headcount())
  await userEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByLabelText('보증인원')).toBeVisible()
})

it('is reached from 원장 and goes back to it', async () => {
  const api = weddingApi()
  server.use(
    api.me(),
    api.weddings(),
    api.headcount(),
    api.update(),
    http.get(`${API}/weddings/:weddingId/guests`, () => HttpResponse.json([])),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  await userEvent.click(await screen.findByRole('link', { name: '설정' }))

  const settings = await screen.findByRole('heading', { name: '설정' })
  expect(settings).toBeVisible()
  expect(await screen.findByLabelText('보증인원')).toBeVisible()

  // 원장 is home and this screen is a trip away from it, so the way back is on
  // the screen rather than in the browser's Back button alone.
  await userEvent.click(screen.getByRole('link', { name: '하객 명부' }))
  expect(await screen.findByRole('region', { name: '인원수' })).toBeVisible()
})

it('is where 마이페이지 is reached from, rather than the ledger header', async () => {
  const api = weddingApi()
  server.use(api.me(), api.weddings(), api.headcount())

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  await userEvent.click(await screen.findByRole('link', { name: '마이페이지' }))

  // An entry point in the ledger header would put back the third control the
  // two-row split was invented to fit; two taps from 원장 is the depth this is
  // worth (notes/2026-08-22-decision-logout-leaves-the-ledger.md).
  expect(await screen.findByRole('heading', { name: '마이페이지' })).toBeVisible()
  expect(screen.getByText('김테스터')).toBeVisible()
})

it('sends a person with no wedding to 웨딩 만들기 instead of an empty form', async () => {
  const api = weddingApi()
  server.use(
    api.me(),
    api.weddings(() => HttpResponse.json<Wedding[]>([])),
    api.headcount(),
    api.update(),
  )

  renderWithProviders(<App />, { initialEntries: ['/settings'] })

  // An empty list is the ordinary answer for a person with no wedding — 최초
  // 1회, and also what being removed from a partner's wedding looks like.
  expect(await screen.findByRole('heading', { name: '결혼식 만들기' })).toBeVisible()
})

it('keeps our own number off the screen the venue\u0027s number is typed into', async () => {
  const api = weddingApi({ guaranteedHeadcount: 150, mealHeadcount: 128 })
  server.use(api.me(), api.weddings(), api.headcount(), api.update())

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  await form()

  /*
   * 보증인원 IS THE VENUE'S NUMBER, NEVER OURS. No 식대 인원 beside the field, no
   * 여유/초과 subtraction, no meter and no suggestion: the comparison belongs on
   * 원장, where the couple reads it, and putting our count next to the box they
   * type theirs into is a hint (root AGENTS.md).
   */
  expect(screen.queryByText('128')).toBeNull()
  expect(screen.queryByTestId('guarantee-meter')).toBeNull()
  expect(screen.queryByText(/여유|초과/)).toBeNull()
})

it('never writes back a member the couple did not touch, however many times they save', async () => {
  const api = weddingApi({ weddingDate: '2027-03-14' })
  server.use(api.me(), api.weddings(), api.headcount(), api.update())

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()
  expect(fields.guaranteed).toHaveValue('')

  // The partner agrees 200 with the venue on their own device, on a form that
  // was prefilled before they did.
  api.partner({ guaranteedHeadcount: 200 })

  await fields.fill(fields.date, '2027-03-15')
  await fields.save()
  await waitFor(() => expect(api.saved).toHaveLength(1))
  expect(api.lastSaved()?.body).toEqual({ weddingDate: '2027-03-15' })

  /*
   * THE SECOND SAVE IS WHERE THIS BREAKS, and the first one is why: the response
   * carried the partner's 200 for a member this form never sent. A baseline
   * advanced from that response disagrees with an untouched empty box forever
   * after, and an empty box is spelled `null` — **the one spelling of "clear"**.
   * The partner's number would be gone, answered 200, with nothing to recover it
   * from: v1 records no attribution at all (`#25`, `#179`).
   */
  await fields.fill(fields.date, '2027-03-16')
  await fields.save()
  await waitFor(() => expect(api.saved).toHaveLength(2))
  expect(api.lastSaved()?.body).toEqual({ weddingDate: '2027-03-16' })
  expect(api.stored().guaranteedHeadcount).toBe(200)
})

it('does not revert a partner\u0027s newer 예식일 on a second save of the number', async () => {
  const api = weddingApi({ weddingDate: '2026-10-10' })
  server.use(api.me(), api.weddings(), api.headcount(), api.update())

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()

  // The same shape on the other member: the partner moves the date after this
  // form loaded it.
  api.partner({ weddingDate: '2027-03-14' })

  await fields.fill(fields.guaranteed, '150')
  await fields.save()
  await waitFor(() => expect(api.saved).toHaveLength(1))

  await fields.fill(fields.guaranteed, '160')
  await fields.save()
  await waitFor(() => expect(api.saved).toHaveLength(2))
  expect(api.lastSaved()?.body).toEqual({ guaranteedHeadcount: 160 })
  expect(api.stored().weddingDate).toBe('2027-03-14')
})

it('says it saved even when the response carries a member the form never sent', async () => {
  const api = weddingApi()
  server.use(api.me(), api.weddings(), api.headcount(), api.update())

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()
  api.partner({ guaranteedHeadcount: 200 })

  await fields.fill(fields.date, '2027-03-15')
  await fields.save()

  /*
   * A SUCCESSFUL SAVE THAT SAYS NOTHING IS WHAT INVITES THE SECOND PRESS. The
   * confirmation is withheld while the screen still differs from what was
   * written — so a baseline polluted by a member the form never sent makes a
   * perfectly good save look like a failed one, and the couple presses again.
   */
  expect(await screen.findByText('저장했습니다.')).toBeVisible()
})

it('sends a touched member again when the couple moves it a second time', async () => {
  const api = weddingApi()
  server.use(api.me(), api.weddings(), api.headcount(), api.update())

  renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()

  await fields.fill(fields.guaranteed, '150')
  await fields.save()
  await waitFor(() => expect(api.saved).toHaveLength(1))

  await fields.fill(fields.guaranteed, '160')
  await fields.save()
  await waitFor(() => expect(api.saved).toHaveLength(2))
  expect(api.lastSaved()?.body).toEqual({ guaranteedHeadcount: 160 })

  // And a third save with nothing moved is the legal no-op again, which is what
  // says the baseline DID advance for the member this form actually wrote.
  await fields.save()
  await waitFor(() => expect(api.saved).toHaveLength(3))
  expect(api.lastSaved()?.body).toEqual({})
})

it('never lets a wedding read already in flight overwrite the date the save wrote', async () => {
  const api = weddingApi({ weddingDate: '2026-10-10' })
  let releaseStale = () => {}
  const stale = new Promise<void>((resolve) => {
    releaseStale = resolve
  })
  let reads = 0
  server.use(
    api.me(),
    /*
     * The second read is the one a couple tabbing back from KakaoTalk starts,
     * and it was answered BEFORE the save — so it carries the old 예식일.
     *
     * THIS TEST DOES NOT ISOLATE `setWedding`'s CANCEL, and saying so is the
     * point of this comment: with a couple still on the screen, `onSettled`'s
     * invalidation repairs this case on its own — `invalidateQueries` defaults
     * to `cancelRefetch: true`, so it aborts the stale fetch and starts a fresh
     * one. Delete the cancel and this test stays green. What the cancel is FOR
     * is the test below it, where nobody is left on screen to refetch for
     * (`useWeddings.ts` § setWedding).
     */
    http.get(`${API}/weddings`, async () => {
      reads += 1
      if (reads === 2) {
        await stale
        return HttpResponse.json<Wedding[]>([
          { id: 12, weddingDate: '2026-10-10', seats: SEATS },
        ])
      }
      // Every other read answers honestly, the `onSettled` invalidation's
      // included — it is the stale one landing late that this is about.
      return HttpResponse.json<Wedding[]>([
        { id: 12, weddingDate: api.stored().weddingDate, seats: SEATS },
      ])
    }),
    api.headcount(),
    api.update(),
  )

  const { queryClient } = renderWithProviders(<App />, { initialEntries: ['/settings'] })
  const fields = await form()

  void queryClient.refetchQueries({ queryKey: weddingsQueryKey, exact: true })
  await waitFor(() => expect(reads).toBe(2))

  await fields.fill(fields.date, '2027-03-14')
  await fields.save()
  expect(await screen.findByText('저장했습니다.')).toBeVisible()

  releaseStale()
  await act(async () => {
    await new Promise((settle) => setTimeout(settle, 20))
  })

  // 저장했습니다 beside a header showing the date they just replaced is the
  // failure this closes — the same one `setHeadcount` closes for the number.
  expect(queryClient.getQueryData(weddingsQueryKey)).toEqual([
    { id: 12, weddingDate: '2027-03-14', seats: SEATS },
  ])
})

it('holds the date it wrote when nobody is left on screen to refetch it', async () => {
  const api = weddingApi({ weddingDate: '2026-10-10' })
  let releaseStale = () => {}
  const stale = new Promise<void>((resolve) => {
    releaseStale = resolve
  })
  let releaseSave = () => {}
  const held = new Promise<void>((resolve) => {
    releaseSave = resolve
  })
  let reads = 0
  server.use(
    api.me(),
    http.get(`${API}/weddings`, async () => {
      reads += 1
      if (reads === 2) {
        await stale
        return HttpResponse.json<Wedding[]>([
          { id: 12, weddingDate: '2026-10-10', seats: SEATS },
        ])
      }
      return HttpResponse.json<Wedding[]>([
        { id: 12, weddingDate: api.stored().weddingDate, seats: SEATS },
      ])
    }),
    api.headcount(),
    http.patch(`${API}/weddings/:weddingId`, async ({ request }) => {
      api.saved.push({
        request,
        body: (await request.clone().json()) as UpdateWeddingRequest,
        raw: '',
      })
      await held
      return HttpResponse.json<WeddingMutation>({
        wedding: { id: 12, weddingDate: '2027-03-14', seats: SEATS },
        headcount: { mealHeadcount: 128 },
      })
    }),
  )

  const { queryClient, unmount } = renderWithProviders(<App />, {
    initialEntries: ['/settings'],
  })
  const fields = await form()

  // A read a couple's own tab-back started, still in flight and already stale.
  void queryClient.refetchQueries({ queryKey: weddingsQueryKey, exact: true })
  await waitFor(() => expect(reads).toBe(2))

  await fields.fill(fields.date, '2027-03-14')
  await fields.save()
  await waitFor(() => expect(api.saved).toHaveLength(1))

  /*
   * THEY LEAVE THE MOMENT THEY PRESS 저장 — closed the tab, went back to
   * KakaoTalk, killed the app. The write still lands: a mutation's own
   * `onSuccess` runs whether or not the component that fired it is still
   * mounted.
   */
  unmount()
  releaseSave()
  await settle()

  /*
   * AND NOW NOTHING ELSE CAN REPAIR THE CACHE. `invalidateQueries` defaults to
   * `refetchType: 'active'`, and there are no active observers left, so
   * `onSettled` marks the list stale and refetches NOTHING. The stale read is
   * the only thing still in flight — so unless `setWedding` cancelled it, its
   * answer is the last word and 원장 opens on the date the couple replaced.
   *
   * This is the case that isolates the cancel: delete `cancelQueries` from
   * `setWedding` and this test alone goes red.
   */
  releaseStale()
  await settle()

  expect(queryClient.getQueryData(weddingsQueryKey)).toEqual([
    { id: 12, weddingDate: '2027-03-14', seats: SEATS },
  ])
})

/** Let every queued promise and the cache writes behind them run out. */
async function settle() {
  await act(async () => {
    await new Promise((done) => setTimeout(done, 20))
  })
}
