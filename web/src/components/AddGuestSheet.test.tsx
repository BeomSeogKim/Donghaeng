import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { expect, it } from 'vitest'
import { App } from '../App'
import type { Guest } from '../hooks/useGuests'
import { type Headcount, headcountQueryKey } from '../hooks/useHeadcount'
import type { Session } from '../hooks/useSession'
import type { Wedding } from '../hooks/useWeddings'
import type { paths } from '../lib/api-types.gen'
import { renderWithProviders } from '../test/render'
import { server } from '../test/server'

/*
 * 하객 추가 — the sheet that opens over 원장, end to end inside the app, with
 * only the network faked.
 *
 * THESE ARE MANDATORY TESTS. A mutation flow and the headcount display are both
 * named in notes/2026-08-08-decision-frontend-testing-methodology.md, and this
 * is the ONLY intake path in v1: the vendor-email parser and the CSV import went
 * to post-v1 on 2026-08-21, so every row in every v1 ledger is typed here. What
 * is asserted is what reaches the server and what the couple is told — never the
 * layout.
 */

const API = 'http://localhost:8080'

type AddGuest = paths['/weddings/{weddingId}/guests']['post']
type CreateGuestRequest = AddGuest['requestBody']['content']['application/json']
/** `{guest, headcount}` — the envelope `#12` and `#13` will return too. */
type GuestMutation = AddGuest['responses'][201]['content']['*/*']

const WEDDING: Wedding = {
  id: 12,
  weddingDate: '2026-10-10',
  seats: [
    { side: 'GROOM', name: '김신랑' },
    { side: 'BRIDE', name: '이신부' },
  ],
}

/**
 * The wedding's ledger, as the server keeps it — the rows, the two filters, the
 * create, and the number computed from the rows.
 *
 * IT IS STATEFUL AND IT SUMS ITS OWN ROWS, deliberately. A create double that
 * answered a constant headcount would let a screen that shows a number
 * contradicting its own list stay green, and this is the product whose single
 * claim is that the number is never wrong. The sum is the server's rule: a
 * guest marked 불참 contributes zero whatever their party size says
 * (docs/api-spec.md § POST /weddings/{weddingId}/guests).
 */
function ledger(...initial: readonly Guest[]) {
  const rows: Guest[] = [...initial]
  const created: { request: Request; body: CreateGuestRequest }[] = []
  const headcountRequests: URL[] = []
  let nextId = 100

  const count = (): Headcount => ({
    mealHeadcount: rows
      .filter((row) => row.expectedAttending)
      .reduce((total, row) => total + row.expectedPartySize, 0),
  })

  return {
    rows,
    created,
    headcountRequests,
    me: () =>
      http.get(`${API}/auth/me`, () =>
        HttpResponse.json<Session>({ id: 7, name: '김테스터' }),
      ),
    weddings: () => http.get(`${API}/weddings`, () => HttpResponse.json([WEDDING])),
    /** 원장 itself, filtered exactly as the endpoint filters it. */
    list: () =>
      http.get(`${API}/weddings/:weddingId/guests`, ({ request }) => {
        const query = new URL(request.url).searchParams
        const side = query.get('side')
        const attendance = query.get('attendance')
        return HttpResponse.json<Guest[]>(
          rows.filter(
            (row) =>
              (side === null || side === '' || row.side === side) &&
              (attendance === null ||
                attendance === '' ||
                row.expectedAttending === (attendance === 'ATTENDING')),
          ),
        )
      }),
    headcount: () =>
      http.get(`${API}/weddings/:weddingId/headcount`, ({ request }) => {
        headcountRequests.push(new URL(request.url))
        return HttpResponse.json<Headcount>(count())
      }),
    /**
     * `POST /weddings/{weddingId}/guests`.
     *
     * It refuses what the API refuses — a state-changing request without
     * `Content-Type: application/json` is answered 415 and never reaches the
     * endpoint — and it stores the row AS STORED rather than as sent: the
     * server trims every string and turns a blank one into `null`, so a double
     * that echoed the body back would hide a client that never trims.
     */
    create: (respond?: () => Response) =>
      http.post(`${API}/weddings/:weddingId/guests`, async ({ request }) => {
        const body = (await request.clone().json()) as CreateGuestRequest
        created.push({ request, body })
        if (request.headers.get('Content-Type') !== 'application/json')
          return problem(415, 'UNSUPPORTED_MEDIA_TYPE')
        if (respond !== undefined) return respond()

        nextId += 1
        const guest: Guest = {
          id: nextId,
          name: body.name.trim(),
          side: body.side,
          groupCategory: body.groupCategory ?? 'OTHER',
          groupLabel: stored(body.groupLabel),
          contact: stored(body.contact),
          accessibilityNote: stored(body.accessibilityNote),
          expectedAttending: body.expectedAttending ?? true,
          expectedPartySize: body.expectedPartySize ?? 1,
        }
        rows.push(guest)
        return HttpResponse.json<GuestMutation>(
          { guest, headcount: count() },
          { status: 201 },
        )
      }),
  }
}

/** Trimmed, and nothing rather than an empty string (docs/api-spec.md). */
function stored(value: string | null | undefined): string | null {
  const trimmed = (value ?? '').trim()
  return trimmed === '' ? null : trimmed
}

/** A row as the API returns it, typed from the generated document. */
function guest(id: number, name: string, overrides: Partial<Guest> = {}): Guest {
  return {
    id,
    name,
    side: 'GROOM',
    groupCategory: 'OTHER',
    groupLabel: null,
    contact: null,
    accessibilityNote: null,
    expectedAttending: true,
    expectedPartySize: 1,
    ...overrides,
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
      instance: '/weddings/12/guests',
      code,
    },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )

/** Open 하객 추가 from 원장 and hand back the sheet. */
async function openSheet() {
  await userEvent.click(await screen.findByRole('button', { name: '하객 추가' }))
  return await screen.findByRole('dialog', { name: '하객 추가' })
}

/** The sheet's own controls, never the ledger's — both are on screen at once. */
function form(sheet: HTMLElement) {
  return {
    name: (value: string) => userEvent.type(within(sheet).getByLabelText('이름'), value),
    side: (label: '신랑측' | '신부측') =>
      userEvent.click(within(sheet).getByRole('radio', { name: label })),
    attendance: (label: '참석' | '불참') =>
      userEvent.click(within(sheet).getByRole('radio', { name: label })),
    party: async (label: '예상 인원 늘리기' | '예상 인원 줄이기', times = 1) => {
      for (let taps = 0; taps < times; taps += 1)
        await userEvent.click(within(sheet).getByRole('button', { name: label }))
    },
    submit: () => userEvent.click(within(sheet).getByRole('button', { name: '추가' })),
  }
}

const renderedNames = () =>
  screen.getAllByTestId('guest-name').map((element) => element.textContent ?? '')

it('adds a guest from a name and a 측, with every other member left at its default', async () => {
  const api = ledger()
  server.use(api.me(), api.weddings(), api.list(), api.headcount(), api.create())

  renderWithProviders(<App />, { initialEntries: ['/'] })
  const sheet = await openSheet()
  await form(sheet).name('김영수')
  await form(sheet).side('신랑측')
  await form(sheet).submit()

  await waitFor(() => expect(api.created).toHaveLength(1))
  /*
   * AN EQUALITY, NOT A SUBSET. The defaults are the API's — `OTHER`, 참석, a
   * party of one — and the client sends them as the couple sees them rather
   * than omitting them, so what is on the screen and what is in the row are the
   * same statement. `groupCategory` is the one member that may not be `null`:
   * the generated type has no null branch for an enum (docs/api-spec.md).
   */
  expect(api.created[0]?.body).toEqual({
    name: '김영수',
    side: 'GROOM',
    groupCategory: 'OTHER',
    groupLabel: null,
    contact: null,
    accessibilityNote: null,
    expectedAttending: true,
    expectedPartySize: 1,
  })
  // Without it the API answers 415 and nothing is added.
  expect(api.created[0]?.request.headers.get('Content-Type')).toBe('application/json')
  expect(api.created[0]?.request.credentials).toBe('include')
})

it('will not guess a 측 for a guest, and says so instead of sending one', async () => {
  const api = ledger()
  server.use(api.me(), api.weddings(), api.list(), api.headcount(), api.create())

  renderWithProviders(<App />, { initialEntries: ['/'] })
  const sheet = await openSheet()

  // 측 has two values and no residual — `OTHER` means "not stated yet" for a
  // group, and 신랑측/신부측 has nothing that does. A default would be a claim
  // the couple never made, on one of the ledger's two filters and one of its
  // aggregation axes (notes/2026-08-20-decision-guest-entry-side-and-companions.md).
  for (const label of ['신랑측', '신부측'])
    expect(within(sheet).getByRole('radio', { name: label })).not.toBeChecked()

  await form(sheet).name('김영수')
  await form(sheet).submit()

  expect(
    await within(sheet).findByText('신랑측인지 신부측인지 골라 주세요.'),
  ).toBeVisible()
  expect(api.created).toHaveLength(0)
})

it('does not spend a round trip on a name that is only whitespace', async () => {
  const api = ledger()
  server.use(api.me(), api.weddings(), api.list(), api.headcount(), api.create())

  renderWithProviders(<App />, { initialEntries: ['/'] })
  const sheet = await openSheet()
  await form(sheet).name('   ')
  await form(sheet).side('신랑측')
  await form(sheet).submit()

  expect(await within(sheet).findByText('이름을 입력해 주세요.')).toBeVisible()
  expect(api.created).toHaveLength(0)
})

it('moves the number from the response the create carried, and never asks again', async () => {
  const api = ledger(guest(1, '박지민'))
  server.use(api.me(), api.weddings(), api.list(), api.headcount(), api.create())

  renderWithProviders(<App />, { initialEntries: ['/'] })
  const headcount = await screen.findByRole('region', { name: '인원수' })
  expect(await within(headcount).findByText('1')).toBeVisible()

  const sheet = await openSheet()
  await form(sheet).name('김영수')
  await form(sheet).side('신랑측')
  await form(sheet).party('예상 인원 늘리기')
  await form(sheet).submit()

  // The sheet covers the pinned number on a phone, so the write says what it
  // did — the same number the response carried, not a second read of it.
  expect(await within(sheet).findByText(/김영수님을 추가했습니다/)).toBeVisible()
  expect(within(sheet).getByText(/식대 인원 3명/)).toBeVisible()
  // And the number behind the sheet moved in place: 1 + a party of 2.
  expect(await within(headcount).findByText('3')).toBeVisible()
  /*
   * ONE read of the number, on open. Every guest mutation carries the
   * recomputed aggregate, computed inside the same transaction as the write; a
   * `GET .../headcount` fired beside the mutation lands outside the window
   * mutations are serialised in and puts the out-of-order race straight back
   * (notes/2026-08-21-decision-query-defaults-and-mutation-ordering.md).
   */
  expect(api.headcountRequests).toHaveLength(1)
})

it('never lets a read already in flight overwrite the number the create returned', async () => {
  const api = ledger(guest(1, '박지민'))
  let releaseStale = () => {}
  const stale = new Promise<void>((resolve) => {
    releaseStale = resolve
  })
  let reads = 0
  server.use(
    api.me(),
    api.weddings(),
    api.list(),
    /*
     * The second read is the one a couple tabbing back from KakaoTalk starts —
     * `staleTime: 0` and `refetchOnWindowFocus` are both deliberate — and it
     * was computed BEFORE the guest was added, so it answers the old number.
     */
    http.get(`${API}/weddings/:weddingId/headcount`, async () => {
      reads += 1
      if (reads > 1) await stale
      return HttpResponse.json<Headcount>({ mealHeadcount: 1 })
    }),
    api.create(),
  )

  const { queryClient } = renderWithProviders(<App />, { initialEntries: ['/'] })
  const headcount = await screen.findByRole('region', { name: '인원수' })
  expect(await within(headcount).findByText('1')).toBeVisible()

  void queryClient.refetchQueries({ queryKey: headcountQueryKey(12) })
  await waitFor(() => expect(reads).toBe(2))

  const sheet = await openSheet()
  await form(sheet).name('김영수')
  await form(sheet).side('신랑측')
  await form(sheet).submit()
  expect(await within(headcount).findByText('2')).toBeVisible()

  // The older answer lands now. query-core's fetch resolution writes whatever
  // it was given, with no comparison of when the two numbers were computed, so
  // without the cancel in `setHeadcount` this is where 2 becomes 1 again.
  releaseStale()
  await act(async () => {
    await new Promise((settle) => setTimeout(settle, 20))
  })

  // A number lagging a tap by 100ms is fine; a number moving backwards is not.
  expect(queryClient.getQueryData(headcountQueryKey(12))).toEqual({ mealHeadcount: 2 })
  expect(within(headcount).getByText('2')).toBeVisible()
})

it('writes one guest for a double press, not two people with one name', async () => {
  const api = ledger()
  let release = () => {}
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  server.use(
    api.me(),
    api.weddings(),
    api.list(),
    api.headcount(),
    http.post(`${API}/weddings/:weddingId/guests`, async ({ request }) => {
      api.created.push({ request, body: (await request.clone().json()) as never })
      await held
      return HttpResponse.json<GuestMutation>(
        {
          guest: guest(101, '김영수'),
          headcount: { mealHeadcount: 1 },
        },
        { status: 201 },
      )
    }),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  const sheet = await openSheet()
  await form(sheet).name('김영수')
  await form(sheet).side('신랑측')
  await form(sheet).submit()
  await waitFor(() => expect(api.created).toHaveLength(1))

  /*
   * A create is not idempotent: a second guest with the same name succeeds and
   * is a second row, deliberately, because 동명이인 is real and direct entry
   * needs no matching (docs/api-spec.md). So a double press is two people in
   * the ledger and a meal headcount honestly computed from a wrong list — and
   * the sheet staying open, with the name still in the field, is exactly the
   * shape that invites the second press.
   */
  await form(sheet).submit()
  await form(sheet).submit()

  /*
   * COUNTED AFTER THE QUEUE HAS DRAINED, WHICH IS THE ONLY MOMENT THAT PROVES
   * ANYTHING. Mutations share one scope and run one at a time, so a press made
   * while the first is in flight does not reach the network yet — asserting
   * here would pass with no guard at all, because the extra presses would be
   * sitting in the queue rather than absent. They fire the moment the first one
   * settles, so the count has to be read after that.
   */
  release()
  expect(await within(sheet).findByText(/김영수님을 추가했습니다/)).toBeVisible()
  await act(async () => {
    await new Promise((settle) => setTimeout(settle, 20))
  })
  expect(api.created).toHaveLength(1)
})

it('does not leave the last guest\u0027s number standing over the next one', async () => {
  const api = ledger()
  server.use(
    api.me(),
    api.weddings(),
    api.list(),
    api.headcount(),
    api.create(),
    // The second add fails, after the first one succeeded.
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  const sheet = await openSheet()
  await form(sheet).name('김영수')
  await form(sheet).side('신랑측')
  await form(sheet).submit()
  expect(await within(sheet).findByText(/김영수님을 추가했습니다/)).toBeVisible()

  server.use(api.create(() => problem(500, 'INTERNAL_ERROR')))
  await form(sheet).name('이서연')
  await form(sheet).submit()

  /*
   * THE CONFIRMATION IS ABOUT ONE WRITE AND MAY NOT OUTLIVE IT. It carries a
   * number, and a number standing on the screen after a later add failed is the
   * one thing this product may not show — a 식대 인원 that was true a guest ago,
   * rendered as though it were true now.
   */
  expect(await within(sheet).findByRole('alert')).toBeVisible()
  expect(within(sheet).queryByText(/김영수님을 추가했습니다/)).not.toBeInTheDocument()
})

it('offers a failure it cannot explain again, rather than explaining it away', async () => {
  const api = ledger()
  server.use(
    api.me(),
    api.weddings(),
    api.list(),
    api.headcount(),
    api.create(() => problem(500, 'INTERNAL_ERROR')),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  const sheet = await openSheet()
  await form(sheet).name('김영수')
  await form(sheet).side('신랑측')
  await form(sheet).submit()

  // A 5xx says nothing about what went wrong, by design, so there is nothing to
  // explain and only something to try again — and this is the failure a couple
  // on a phone in a wedding hall actually meets.
  expect(
    await within(sheet).findByText(
      '하객을 추가하지 못했습니다. 잠시 후 다시 시도해 주세요.',
    ),
  ).toBeVisible()
  expect(within(sheet).getByLabelText('이름')).toHaveValue('김영수')
})

it('puts the new row in the ledger even while a filter is pressed', async () => {
  const api = ledger(guest(1, '박지민', { side: 'GROOM' }))
  server.use(api.me(), api.weddings(), api.list(), api.headcount(), api.create())

  renderWithProviders(<App />, { initialEntries: ['/'] })
  await screen.findByTestId('guest-name')
  await userEvent.click(screen.getByRole('button', { name: '신랑' }))
  await waitFor(() => expect(renderedNames()).toEqual(['박지민']))

  const sheet = await openSheet()
  await form(sheet).name('김영수')
  await form(sheet).side('신랑측')
  await form(sheet).submit()

  // The whole ledger is invalidated, not the filter combination on screen, so
  // the unfiltered list does not stay stale behind the pressed chip
  // (notes/2026-08-21-decision-ledger-screen.md § Query keys). And the order is
  // still 이름 가나다순.
  await waitFor(() => expect(renderedNames()).toEqual(['김영수', '박지민']))
})

it('stays open for the next guest, keeping the 측 and clearing the name', async () => {
  const api = ledger()
  server.use(api.me(), api.weddings(), api.list(), api.headcount(), api.create())

  renderWithProviders(<App />, { initialEntries: ['/'] })
  const sheet = await openSheet()
  await form(sheet).name('김영수')
  await form(sheet).side('신부측')
  await form(sheet).submit()
  await waitFor(() => expect(api.created).toHaveLength(1))

  /*
   * DIRECT ENTRY IS THE ONLY INTAKE PATH IN v1, so a couple works through a
   * list their parents sent them and the ordinary case is a run of guests on
   * one 측. Closing the sheet per guest would charge them a re-open tap each
   * time, on the product whose whole claim is that it is less work than the
   * spreadsheet. The 측 is retained because it is a visible, filled control —
   * a carried-over answer, not a silent default (docs/api-spec.md: a
   * pre-selection is a frontend affordance).
   */
  expect(within(sheet).getByLabelText('이름')).toHaveValue('')
  expect(within(sheet).getByRole('radio', { name: '신부측' })).toBeChecked()
  expect(within(sheet).getByLabelText('이름')).toHaveFocus()

  await form(sheet).name('이서연')
  await form(sheet).submit()

  await waitFor(() => expect(api.created).toHaveLength(2))
  expect(api.created[1]?.body).toMatchObject({ name: '이서연', side: 'BRIDE' })
})

it('sends the optional members trimmed, and nothing at all for the blank ones', async () => {
  const api = ledger()
  server.use(api.me(), api.weddings(), api.list(), api.headcount(), api.create())

  renderWithProviders(<App />, { initialEntries: ['/'] })
  const sheet = await openSheet()
  await form(sheet).name('  김영수 ')
  await form(sheet).side('신랑측')
  await userEvent.selectOptions(within(sheet).getByLabelText('그룹'), '친구')
  await userEvent.type(within(sheet).getByLabelText('그룹 라벨'), '  대학교 동아리 ')
  await userEvent.type(within(sheet).getByLabelText('배려사항'), '   ')
  await form(sheet).submit()

  await waitFor(() => expect(api.created).toHaveLength(1))
  /*
   * Every length bound is measured on what is SENT, before the server's own
   * trim, so trimming here is what makes the two agree. A field left blank is
   * sent as `null`, which the server treats exactly as an omitted member — so
   * the body does not have to be built conditionally (docs/api-spec.md).
   */
  expect(api.created[0]?.body).toMatchObject({
    name: '김영수',
    groupCategory: 'FRIEND',
    groupLabel: '대학교 동아리',
    contact: null,
    accessibilityNote: null,
  })
})

it('keeps the party size on a guest who cannot come, rather than erasing it', async () => {
  const api = ledger()
  server.use(api.me(), api.weddings(), api.list(), api.headcount(), api.create())

  renderWithProviders(<App />, { initialEntries: ['/'] })
  const sheet = await openSheet()

  // A party of zero is not a party — 불참 is what says nobody is coming, and a
  // size of 0 is a 400 (docs/api-spec.md).
  expect(within(sheet).getByRole('button', { name: '예상 인원 줄이기' })).toBeDisabled()

  await form(sheet).name('김영수')
  await form(sheet).side('신랑측')
  await form(sheet).party('예상 인원 늘리기', 2)
  await form(sheet).attendance('불참')
  await form(sheet).submit()

  await waitFor(() => expect(api.created).toHaveLength(1))
  // The size is kept rather than erased, so flipping back to 참석 later restores
  // the count instead of making the couple retype it.
  expect(api.created[0]?.body).toMatchObject({
    expectedAttending: false,
    expectedPartySize: 3,
  })
  // 불참 contributes zero, so the number did not move.
  const headcount = await screen.findByRole('region', { name: '인원수' })
  expect(await within(headcount).findByText('0')).toBeVisible()
})

it('holds every field to the same bound the server does, and names the one that broke', async () => {
  const api = ledger()
  server.use(api.me(), api.weddings(), api.list(), api.headcount(), api.create())

  renderWithProviders(<App />, { initialEntries: ['/'] })
  const sheet = await openSheet()
  await form(sheet).name('김'.repeat(101))
  await form(sheet).side('신랑측')
  await userEvent.type(within(sheet).getByLabelText('연락처'), '0'.repeat(31))
  await form(sheet).submit()

  /*
   * NOTHING IS TRUNCATED ON THE WAY IN. A `maxLength` would cut a pasted
   * 배려사항 at 500 characters without saying so, and report success on the
   * couple's own words thrown away — so the bound is checked and the field that
   * broke it is named, which is what the 400 itself does not do (`#63`).
   */
  expect(await within(sheet).findByText('이름은 100자까지 쓸 수 있습니다.')).toBeVisible()
  expect(within(sheet).getByText('연락처는 30자까지 쓸 수 있습니다.')).toBeVisible()
  expect(api.created).toHaveLength(0)
})

it('offers the seven groups the API has, and no eighth', async () => {
  const api = ledger()
  server.use(api.me(), api.weddings(), api.list(), api.headcount(), api.create())

  renderWithProviders(<App />, { initialEntries: ['/'] })
  const sheet = await openSheet()

  // The ledger aggregates on this and never on the free label, so an invented
  // eighth value would be a 400 and a mis-spelled one a wrong group.
  expect(
    within(sheet)
      .getAllByRole('option')
      .map((option) => option.textContent),
  ).toEqual(['가족', '친척', '사촌', '혼주 손님', '친구', '직장동료', '기타'])
  // 기타 honestly means "not stated yet", which is why it is the one default
  // this control can carry.
  expect(within(sheet).getByLabelText('그룹')).toHaveValue('OTHER')
})

/*
 * Two codes, one meaning for the user: the request was wrong. They differ only
 * in whether the body had been read yet, and the spec is explicit that no
 * different UI is built for them (docs/api-spec.md).
 */
for (const code of ['VALIDATION_FAILED', 'MALFORMED_REQUEST_BODY']) {
  it(`says the same thing for ${code} as for the other 400, and keeps what was typed`, async () => {
    const api = ledger()
    server.use(
      api.me(),
      api.weddings(),
      api.list(),
      api.headcount(),
      api.create(() => problem(400, code)),
    )

    renderWithProviders(<App />, { initialEntries: ['/'] })
    const sheet = await openSheet()
    await form(sheet).name('김영수')
    await form(sheet).side('신랑측')
    await form(sheet).submit()

    expect(
      await within(sheet).findByText(
        '하객을 추가하지 못했습니다. 입력한 내용을 확인해 주세요.',
      ),
    ).toBeVisible()
    // `detail` is an English diagnostic that quotes the submitted value back;
    // rendering it would paint attacker-supplied text onto the screen.
    expect(document.body.textContent).not.toContain('Rejected value')
    // Retyping a failed guest is the couple's work, not ours to throw away.
    expect(within(sheet).getByLabelText('이름')).toHaveValue('김영수')
  })
}

it('closes without sending anything, and forgets what was half-typed', async () => {
  const api = ledger()
  server.use(api.me(), api.weddings(), api.list(), api.headcount(), api.create())

  renderWithProviders(<App />, { initialEntries: ['/'] })
  const sheet = await openSheet()
  await form(sheet).name('김영수')
  await userEvent.click(within(sheet).getByRole('button', { name: '닫기' }))

  await waitFor(() =>
    expect(screen.queryByRole('dialog', { name: '하객 추가' })).not.toBeInTheDocument(),
  )
  expect(api.created).toHaveLength(0)

  const reopened = await openSheet()
  expect(within(reopened).getByLabelText('이름')).toHaveValue('')
})

it('sends a 401 on the create back to log in, not to a message on the sheet', async () => {
  const api = ledger()
  server.use(
    api.me(),
    api.weddings(),
    api.list(),
    api.headcount(),
    api.create(() => problem(401, 'UNAUTHENTICATED')),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  const sheet = await openSheet()
  await form(sheet).name('김영수')
  await form(sheet).side('신랑측')
  await form(sheet).submit()

  // One status, one answer, in one place: the client writes the session to null
  // on any 401 and the login screen replaces the ledger (lib/queryClient.ts). A
  // message on the sheet would be a second answer to it.
  expect(await screen.findByRole('link', { name: '구글로 로그인' })).toBeVisible()
})
