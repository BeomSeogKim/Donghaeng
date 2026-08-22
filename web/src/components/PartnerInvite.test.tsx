import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { expect, it, vi } from 'vitest'
import { App } from '../App'
import type { Headcount } from '../hooks/useHeadcount'
import type { Session } from '../hooks/useSession'
import type { Wedding } from '../hooks/useWeddings'
import type { paths } from '../lib/api-types.gen'
import { renderWithProviders } from '../test/render'
import { server } from '../test/server'

/*
 * 설정 · 파트너 초대 — the screen that mints the link, end to end inside the app
 * with only the network faked.
 *
 * THESE ARE MANDATORY TESTS: a mutation flow, and one that branches on the
 * API's error `code` (notes/2026-08-08-decision-frontend-testing-methodology.md).
 *
 * WHAT IS ASSERTED IS THE SHAPE OF THE LINK AND WHO IS OFFERED ONE. The token
 * belongs in the FRAGMENT and nowhere else — a link that put it in the path or
 * the query would be recorded in plaintext in every access log it passes
 * (notes/2026-08-22-decision-the-invite-link.md §2) — and a wedding with two
 * seated people has nobody to invite, which the API only tells us through
 * `seats[].name`.
 */

const API = 'http://localhost:8080'
const TOKEN = 'sel3ct0r.v3r1f13r'
/** The instant the API published. Rendered in whatever zone the reader is in. */
const EXPIRES_AT = '2026-08-23T09:00:00Z'

/** So a formatted date can be matched literally, dots and all. */
const escapeRegExp = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

type IssueInvite = paths['/weddings/{weddingId}/invite']['post']
/** `{token, expiresAt}` — published exactly once and never readable again. */
type IssuedInvite = IssueInvite['responses'][201]['content']['*/*']

const WAITING: Wedding['seats'] = [
  { side: 'GROOM', name: '김신랑' },
  { side: 'BRIDE', name: null },
]
const SEATED: Wedding['seats'] = [
  { side: 'GROOM', name: '김신랑' },
  { side: 'BRIDE', name: '이신부' },
]

/**
 * The wedding as the server keeps it, and the one POST that mints a link.
 *
 * IT IS STATEFUL ABOUT THE SEATS because that is the only signal the API gives
 * about whether there is anybody left to invite, and the screen has to follow
 * it: a partner who joins from their own phone turns this couple's 재발급 button
 * into a button that can only be refused.
 */
function inviteApi({
  seats = WAITING,
  held: hold = false,
}: {
  seats?: Wedding['seats']
  held?: boolean
} = {}) {
  let taken = seats
  const issued: { contentType: string | null }[] = []

  let release = () => {}
  const answering = new Promise<void>((resolve) => {
    release = resolve
  })

  /** Set while `GET /weddings` should fail — a 5xx, then recovery. */
  let weddingsFail = false
  let weddingReads = 0

  return {
    issued,
    release,
    /** How many times the shared read has been answered — a focus refetch is async. */
    weddingReads: () => weddingReads,
    /** The partner, accepting from their own phone while this screen is open. */
    partnerJoins: () => {
      taken = SEATED
    },
    /**
     * The shared read failing and recovering. `staleTime: 0` and
     * `refetchOnWindowFocus` are both deliberate, so a refetch firing while the
     * couple is on this screen is the ordinary case, not an exotic one.
     */
    weddingsFail: (failing: boolean) => {
      weddingsFail = failing
    },
    handlers: (respond?: () => Response) => [
      http.get(`${API}/auth/me`, () =>
        HttpResponse.json<Session>({ id: 7, name: '김신랑' }),
      ),
      http.get(`${API}/weddings`, () => {
        weddingReads += 1
        return weddingsFail
          ? HttpResponse.json({}, { status: 500 })
          : HttpResponse.json<Wedding[]>([
              { id: 12, weddingDate: '2026-10-10', seats: taken },
            ])
      }),
      http.get(`${API}/weddings/:weddingId/headcount`, () =>
        HttpResponse.json<Headcount>({ mealHeadcount: 0 }),
      ),
      http.post(`${API}/weddings/:weddingId/invite`, async ({ request }) => {
        issued.push({ contentType: request.headers.get('Content-Type') })
        if (hold) await answering
        if (respond !== undefined) return respond()
        // A DISTINCT TOKEN PER MINT, because 재발급 kills the previous one: a
        // double that answered one constant could not tell a stale link from a
        // live one, which is the whole of what this screen gets wrong.
        return HttpResponse.json<IssuedInvite>(
          {
            token: issued.length === 1 ? TOKEN : `${TOKEN}-${issued.length}`,
            expiresAt: EXPIRES_AT,
          },
          { status: 201 },
        )
      }),
    ],
  }
}

/** A problem document as the API writes it — `code` is the only member read. */
const problem = (status: number, code: string) =>
  HttpResponse.json(
    { type: 'about:blank', title: 'Error', status, detail: 'Diagnostic.', code },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )

const settings = () => renderWithProviders(<App />, { initialEntries: ['/settings'] })
const issue = async (name = '초대 링크 만들기') =>
  await userEvent.click(await screen.findByRole('button', { name }))

it('publishes the token in the link fragment, and never anywhere else in the URL', async () => {
  const api = inviteApi()
  server.use(...api.handlers())

  settings()
  await issue()

  const link = await screen.findByText(`${window.location.origin}/invite#t=${TOKEN}`)
  expect(link).toBeInTheDocument()
  // The fragment is the only part of a URL never sent to a server. A token in
  // the path is in an access log before any of our code runs.
  expect(link.textContent).not.toContain(`?t=`)
  expect(api.issued[0].contentType).toBe('application/json')
})

it('says what a link costs: one day, and one at a time', async () => {
  const api = inviteApi()
  server.use(...api.handlers())

  settings()
  // 재발급이 이전 링크를 죽인다 — said BEFORE the couple presses it, because a
  // couple who believe they hold three live links have been misled by us
  // (notes/2026-08-22-decision-the-invite-link.md §1).
  expect(
    await screen.findByText(/새로 만들면 이전 링크는 바로 쓸 수 없게 됩니다/),
  ).toBeInTheDocument()

  await issue()

  /*
   * `expiresAt` is what is rendered — never a duration computed here.
   *
   * THE INSTANT IS PINNED, NOT THE RENDERED DATE. `2026-08-23T09:00:00Z` falls
   * on 8월 23일 under CI's UTC and under KST, but on 8월 22일 west of UTC-9 —
   * so asserting the date made this test pass because of where it ran. What is
   * actually being checked is that the server's instant reaches the screen, so
   * the expectation is computed from that instant in the same zone the browser
   * is in.
   */
  const expected = new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'long',
    timeStyle: 'short',
  }).format(new Date(EXPIRES_AT))
  expect(await screen.findByText(new RegExp(escapeRegExp(expected)))).toBeInTheDocument()
  // And it can never be read back: only a hash is stored, so leaving this
  // screen loses the link for good (docs/api-spec.md § POST .../invite).
  expect(
    screen.getByText(/이 화면을 벗어나면 링크를 다시 볼 수 없습니다/),
  ).toBeInTheDocument()
})

it('copies the link a couple has to paste into KakaoTalk', async () => {
  const api = inviteApi()
  server.use(...api.handlers())
  const writeText = vi.fn().mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText },
    configurable: true,
  })

  settings()
  await issue()
  await userEvent.click(await screen.findByRole('button', { name: '링크 복사' }))

  expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/invite#t=${TOKEN}`)
  expect(await screen.findByText('복사했습니다')).toBeInTheDocument()
})

it('offers nothing to a wedding whose seats are both taken', async () => {
  const api = inviteApi({ seats: SEATED })
  server.use(...api.handlers())

  settings()

  expect(await screen.findByText('두 사람 모두 참여했습니다')).toBeInTheDocument()
  // A screen state, not a rule enforced twice: `seats[].name` is the only
  // signal the API gives a fresh tab (notes/2026-08-22-decision-the-partner-invite.md §5).
  expect(
    screen.queryByRole('button', { name: '초대 링크 만들기' }),
  ).not.toBeInTheDocument()
  expect(api.issued).toHaveLength(0)
})

it('takes the button away when the partner joined while this tab was open', async () => {
  const api = inviteApi()
  server.use(...api.handlers(() => problem(409, 'PARTNER_ALREADY_JOINED')))

  settings()
  // The tab has the empty seat on screen; the partner accepts from their own
  // phone a moment later.
  const button = await screen.findByRole('button', { name: '초대 링크 만들기' })
  api.partnerJoins()
  await userEvent.click(button)

  // Not a failure to explain away — the seat is filled, which is what the
  // couple wanted. The screen re-reads the wedding and the button goes.
  expect(await screen.findByText('두 사람 모두 참여했습니다')).toBeInTheDocument()
  await waitFor(() =>
    expect(
      screen.queryByRole('button', { name: '초대 링크 만들기' }),
    ).not.toBeInTheDocument(),
  )
})

it('mints one link for a double press', async () => {
  const api = inviteApi({ held: true })
  server.use(...api.handlers())

  settings()
  const button = await screen.findByRole('button', { name: '초대 링크 만들기' })
  await userEvent.click(button)
  await waitFor(() => expect(api.issued).toHaveLength(1))
  // Still in flight. Serialising mutations delays a second press and never
  // refuses it, and a second mint would kill the link the first one just made
  // — the couple would be holding a dead link they watched us make.
  expect(button).toBeDisabled()
  await userEvent.click(button)

  api.release()
  expect(
    await screen.findByText(`${window.location.origin}/invite#t=${TOKEN}`),
  ).toBeInTheDocument()
  expect(api.issued).toHaveLength(1)
})

it('holds the link through a failed refetch of the wedding', async () => {
  const api = inviteApi()
  server.use(...api.handlers())

  settings()
  await issue()
  const link = `${window.location.origin}/invite#t=${TOKEN}`
  expect(await screen.findByText(link)).toBeInTheDocument()
  const before = api.weddingReads()

  /*
   * THE REFETCH IS FIRED BY A FOCUS EVENT, NOT BY `refetchQueries`, and that is
   * load-bearing rather than incidental — measured, not assumed. A refetch
   * driven by `refetchQueries` leaves `status: 'success'` when the cache
   * already holds data, so it CANNOT reproduce this at all; a focus refetch
   * sets `status: 'error'` while keeping `data`. Written the other way this
   * test passes against the broken screen.
   *
   * AND THE FOCUS EVENT IS THE ORDINARY CASE, not a contrived one: the couple
   * switches to KakaoTalk to paste the link and comes back, which is exactly
   * what `refetchOnWindowFocus` listens for — and `staleTime: 0` means it
   * really refetches (lib/queryClient.ts, both deliberate).
   *
   * ONLY A HASH IS STORED SERVER-SIDE, so there is no way back: the link would
   * be gone for good and the couple must 재발급, killing the link they may have
   * already pasted. It is the one value on this screen no read can reconstruct,
   * so it may not hang off a read's success.
   */
  api.weddingsFail(true)
  window.dispatchEvent(new Event('visibilitychange'))
  window.dispatchEvent(new Event('focus'))
  await waitFor(() => expect(api.weddingReads()).toBeGreaterThan(before))

  // And the screen said 이 화면을 벗어나면 — the couple did not leave it.
  await waitFor(() => expect(screen.getByText(link)).toBeInTheDocument())

  /*
   * AND IT IS STILL THERE ONCE THE READ RECOVERS. 다시 시도 belongs to 웨딩 정보
   * above — this section deliberately has no retry of its own, so one failed
   * read does not put two retry buttons on one screen — and the recovery is
   * what re-renders the branch this section was in.
   */
  api.weddingsFail(false)
  await userEvent.click(await screen.findByRole('button', { name: '다시 시도' }))

  expect(
    await screen.findByRole('button', { name: '새 링크 만들기' }),
  ).toBeInTheDocument()
  expect(screen.getByText(link)).toBeInTheDocument()
})

it('says so when a 409 does not turn out to be the seat being filled', async () => {
  const api = inviteApi()
  // `PARTNER_ALREADY_JOINED`, but the refetch still shows an open seat — a
  // replica read that has not caught up. The silence was justified by the
  // refetch confirming, and nothing checked that it did.
  server.use(...api.handlers(() => problem(409, 'PARTNER_ALREADY_JOINED')))

  settings()
  await issue()

  /*
   * WITHOUT THIS THE SCREEN DOES NOT MOVE: no alert, no link, the button still
   * enabled, and 두 사람 모두 참여했습니다 never arriving either. A couple taps
   * and nothing happens, which is the one thing an instrument may not do.
   */
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '초대할 자리가 남아 있지 않습니다',
  )
})

it('stays silent only for the 409 that is actually good news', async () => {
  const api = inviteApi()
  server.use(...api.handlers(() => problem(409, 'PARTNER_ALREADY_JOINED')))

  settings()
  const button = await screen.findByRole('button', { name: '초대 링크 만들기' })
  // The refetch DOES confirm this time: the partner really did join.
  api.partnerJoins()
  await userEvent.click(button)

  // Not a failure — the seat this couple was inviting somebody into is filled,
  // which is what they wanted. A red line beside that is us calling good news
  // an error.
  expect(await screen.findByText('두 사람 모두 참여했습니다')).toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('speaks up for a 409 it does not recognise', async () => {
  const api = inviteApi()
  // A future or unrecognised `code` on the same status. Branching on the status
  // swallowed it: an unrecognised code is a generic failure, never a silence.
  server.use(...api.handlers(() => problem(409, 'SOME_FUTURE_CONFLICT')))

  settings()
  await issue()

  expect(await screen.findByRole('alert')).toHaveTextContent(
    '초대 링크를 만들지 못했습니다',
  )
})

it('does not let 복사했습니다 vouch for a link that was just replaced', async () => {
  const api = inviteApi()
  server.use(...api.handlers())
  const writeText = vi.fn().mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText },
    configurable: true,
  })

  settings()
  await issue()
  await userEvent.click(await screen.findByRole('button', { name: '링크 복사' }))
  expect(await screen.findByText('복사했습니다')).toBeInTheDocument()

  // 재발급 kills the previous token, so the clipboard now holds a DEAD link
  // while the screen still says it was copied. The couple pastes the one they
  // were told was safe.
  await issue('새 링크 만들기')

  expect(
    await screen.findByText(`${window.location.origin}/invite#t=${TOKEN}-2`),
  ).toBeInTheDocument()
  expect(screen.queryByText('복사했습니다')).not.toBeInTheDocument()
  expect(writeText).toHaveBeenCalledTimes(1)
})

it('names the way out when the clipboard refuses', async () => {
  const api = inviteApi()
  server.use(...api.handlers())
  // A real phone with no clipboard permission, which is what this branch is
  // for — and the copy is the couple's only fallback.
  const writeText = vi.fn().mockRejectedValue(new Error('denied'))
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText },
    configurable: true,
  })

  settings()
  await issue()
  await userEvent.click(await screen.findByRole('button', { name: '링크 복사' }))

  expect(
    await screen.findByText('복사하지 못했습니다. 길게 눌러 복사해 주세요.'),
  ).toBeInTheDocument()
  // The link is on screen either way, so it is still selectable by hand.
  expect(
    screen.getByText(`${window.location.origin}/invite#t=${TOKEN}`),
  ).toBeInTheDocument()
})

it('re-reads the wedding only for the 409, never for an ordinary failure', async () => {
  const api = inviteApi()
  server.use(...api.handlers(() => HttpResponse.json({}, { status: 500 })))

  settings()
  // After the screen has settled, so the mount's own read is not counted.
  await screen.findByRole('button', { name: '초대 링크 만들기' })
  const before = api.weddingReads()
  await issue()
  await screen.findByRole('alert')

  /*
   * A 409 IS THE ONLY ANSWER THAT TEACHES US ANYTHING — it means our copy of
   * `seats` is stale, because the partner accepted from their own phone. A 5xx
   * says nothing about the seats, and re-reading on every failure would put a
   * full round trip behind a button the couple is about to press again.
   */
  expect(api.weddingReads()).toBe(before)
})

it('re-reads the wedding when the seat turns out to be taken', async () => {
  const api = inviteApi()
  server.use(...api.handlers(() => problem(409, 'PARTNER_ALREADY_JOINED')))

  settings()
  const before = await screen.findByRole('button', { name: '초대 링크 만들기' })
  const reads = api.weddingReads()
  api.partnerJoins()
  await userEvent.click(before)

  await waitFor(() => expect(api.weddingReads()).toBeGreaterThan(reads))
  expect(await screen.findByText('두 사람 모두 참여했습니다')).toBeInTheDocument()
})

it('says a failed mint failed, and leaves the button pressable', async () => {
  const api = inviteApi()
  server.use(...api.handlers(() => HttpResponse.json({}, { status: 500 })))

  settings()
  await issue()

  expect(await screen.findByRole('alert')).toHaveTextContent(
    '초대 링크를 만들지 못했습니다. 잠시 후 다시 시도해 주세요.',
  )
  expect(screen.getByRole('button', { name: '초대 링크 만들기' })).toBeEnabled()
})
