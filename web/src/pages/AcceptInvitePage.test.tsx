import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { useLocation } from 'react-router'
import { afterEach, expect, it } from 'vitest'
import { App } from '../App'
import type { Headcount } from '../hooks/useHeadcount'
import type { Session } from '../hooks/useSession'
import type { Wedding } from '../hooks/useWeddings'
import type { paths } from '../lib/api-types.gen'
import { INVITE_STORAGE_KEY } from '../lib/invite'
import { renderWithProviders } from '../test/render'
import { server } from '../test/server'

/*
 * 초대 수락 — the whole path a partner walks, from a KakaoTalk link to a seat,
 * with only the network faked.
 *
 * THESE ARE MANDATORY TESTS, twice over: this is a mutation flow, and it
 * branches on the API's error `code`, each answer a different sentence to the
 * person reading it — which is the whole reason they are told apart
 * (notes/2026-08-08-decision-frontend-testing-methodology.md). One case per
 * code, so a new one arrives as a new test rather than as a recount here.
 *
 * WHAT IS ASSERTED IS WHERE THE TOKEN IS. It is bearer authority with a
 * one-day life (docs/api-spec.md § POST /weddings/{weddingId}/invite), so the
 * assertions that matter are invisible on screen: that it leaves the address
 * bar, that it survives the round trip in `sessionStorage`, that it reaches the
 * server only in a POST body, and that it is dropped the moment it can never
 * work again.
 */

const API = 'http://localhost:8080'
const TOKEN = 'sel3ct0r.v3r1f13r'

type JoinWedding = paths['/weddings/join']['post']
type JoinWeddingRequest = JoinWedding['requestBody']['content']['application/json']

const SEATS: Wedding['seats'] = [
  { side: 'GROOM', name: '김신랑' },
  { side: 'BRIDE', name: null },
]

/**
 * The API as far as an accepting partner can see it: who they are, whether they
 * already hold a wedding, and the one POST that seats them.
 *
 * The ledger's own two reads are here because a successful accept lands on 원장,
 * and a screen that cannot render is not a screen that accepted.
 */
function inviteApi({
  signedIn = true,
  weddings = [],
  held: hold = false,
  holdWeddings = false,
}: {
  signedIn?: boolean
  weddings?: Wedding[]
  held?: boolean
  holdWeddings?: boolean
} = {}) {
  const joins: { body: JoinWeddingRequest; contentType: string | null }[] = []
  // The list this person's `GET /weddings` answers, which a successful join
  // changes exactly as the server would.
  let held = weddings
  // The session, which 로그아웃 ends — the recovery for "I signed in with the
  // wrong Google account", and the one thing that has to take the 409 verdict
  // with it.
  let session = signedIn

  /*
   * A join that has not answered yet, which is the only window a second press
   * can land in. Without it the first press has already navigated away by the
   * time `userEvent.click` returns, and a test of the double press would pass
   * against a screen that has no guard at all — measured, not assumed.
   */
  let release = () => {}
  const answering = new Promise<void>((resolve) => {
    release = resolve
  })

  /*
   * 원장's own read, held open so that the trip through it is observable. Both
   * arrivals at this screen render the same words, so a test of the SECOND one
   * has to see the first leave — and it leaves the moment 원장 mounts with its
   * list still in flight.
   */
  let releaseWeddings = () => {}
  const listing = new Promise<void>((resolve) => {
    releaseWeddings = resolve
  })

  return {
    joins,
    release,
    releaseWeddings,
    handlers: (respond?: () => Response) => [
      http.get(`${API}/auth/me`, () =>
        session
          ? HttpResponse.json<Session>({ id: 7, name: null })
          : problem(401, 'UNAUTHENTICATED'),
      ),
      http.post(`${API}/auth/logout`, () => {
        session = false
        return new HttpResponse(null, { status: 204 })
      }),
      http.get(`${API}/weddings`, async () => {
        if (holdWeddings) await listing
        return HttpResponse.json<Wedding[]>(held)
      }),
      http.get(`${API}/weddings/:weddingId/guests`, () => HttpResponse.json([])),
      http.get(`${API}/weddings/:weddingId/headcount`, () =>
        HttpResponse.json<Headcount>({ mealHeadcount: 0 }),
      ),
      http.post(`${API}/weddings/join`, async ({ request }) => {
        joins.push({
          body: (await request.clone().json()) as JoinWeddingRequest,
          contentType: request.headers.get('Content-Type'),
        })
        if (hold) await answering
        if (respond !== undefined) return respond()

        const joined: Wedding = {
          id: 12,
          weddingDate: '2026-10-10',
          seats: [SEATS[0], { side: 'BRIDE', name: '이신부' }],
        }
        held = [joined]
        return HttpResponse.json<Wedding>(joined)
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

/** The address bar, which is the one thing a fragment has to leave. */
function Address() {
  const { hash, pathname } = useLocation()
  return <output data-testid="address">{`${pathname}${hash}`}</output>
}

const stored = () => sessionStorage.getItem(INVITE_STORAGE_KEY)

async function accept(name: string) {
  await userEvent.type(await screen.findByLabelText('내 이름'), name)
  await userEvent.click(screen.getByRole('button', { name: '수락' }))
}

it('takes the token out of the address bar and keeps it for the round trip', async () => {
  const api = inviteApi({ signedIn: false })
  server.use(...api.handlers())

  renderWithProviders(
    <>
      <App />
      <Address />
    </>,
    { initialEntries: [`/invite#t=${TOKEN}`] },
  )

  // Signed out is the ordinary case — a partner tapping a link in a KakaoTalk
  // room has no reason to be holding our session.
  expect(await screen.findByRole('link', { name: '구글로 로그인' })).toBeInTheDocument()

  // The fragment is gone from the URL, so it is out of Back and out of the
  // share sheet — and it is in the one place that survives the Google round
  // trip in this tab.
  await waitFor(() => expect(screen.getByTestId('address')).toHaveTextContent('/invite'))
  expect(screen.getByTestId('address').textContent).toBe('/invite')
  expect(stored()).toBe(TOKEN)
})

/**
 * The browser this page believes it is running in. Restored after every test —
 * one jsdom window is shared by the whole file.
 */
const SYSTEM_BROWSER = navigator.userAgent
const KAKAOTALK =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 KAKAOTALK 10.7.0'

function openedIn(userAgent: string) {
  Object.defineProperty(navigator, 'userAgent', { value: userAgent, configurable: true })
}
afterEach(() => openedIn(SYSTEM_BROWSER))

it('clears the fragment before the session probe answers, not after', async () => {
  const api = inviteApi({ signedIn: false })
  let answer = () => {}
  const probed = new Promise<void>((resolve) => {
    answer = resolve
  })
  server.use(
    // `GET /auth/me` still in flight — which is EVERY arrival from KakaoTalk,
    // because tapping a link is always a cold load.
    http.get(`${API}/auth/me`, async () => {
      await probed
      return problem(401, 'UNAUTHENTICATED')
    }),
    ...api.handlers().slice(1),
  )

  renderWithProviders(
    <>
      <App />
      <Address />
    </>,
    { initialEntries: [`/invite#t=${TOKEN}`] },
  )

  /*
   * THE TOKEN IS OUT OF THE ADDRESS BAR ALREADY, with the app still showing its
   * loading screen and no route mounted. Read from the accept screen instead,
   * this would sit in the address bar for the whole round trip — and forever
   * behind 다시 시도 when the API is unreachable, because that branch never
   * renders the route table at all (App.tsx).
   */
  await waitFor(() => expect(screen.getByTestId('address')).toHaveTextContent('/invite'))
  expect(screen.getByTestId('address').textContent).toBe('/invite')
  expect(stored()).toBe(TOKEN)

  answer()
  expect(await screen.findByRole('link', { name: '구글로 로그인' })).toBeInTheDocument()
})

it('warns before the tap that an in-app browser cannot finish the login', async () => {
  const api = inviteApi({ signedIn: false })
  server.use(...api.handlers())
  openedIn(KAKAOTALK)

  renderWithProviders(<App />, { initialEntries: [`/invite#t=${TOKEN}`] })

  /*
   * THIS IS THE LAST SCREEN THAT CAN SAY ANYTHING. Google refuses OAuth in an
   * embedded browser (`disallowed_useragent`) and KakaoTalk's is one, so the
   * tap does not come back here — it stops at Google's own error page. And the
   * fragment has already been cleared from the address bar, so "open this page
   * in another browser" would hand over a link with no token in it: the way out
   * is the message in the chat room, which still has the whole link.
   */
  expect(
    await screen.findByText('앱 안에서 열린 브라우저에서는 구글 로그인이 막힙니다.'),
  ).toBeInTheDocument()
  expect(
    screen.getByText(/대화방에서 초대 링크를 길게 눌러 복사한 뒤/),
  ).toBeInTheDocument()

  // BESIDE THE BUTTON, NEVER INSTEAD OF IT: a user agent match can be wrong in
  // both directions, and taking the login away from somebody whose login works
  // is the worse mistake.
  expect(screen.getByRole('link', { name: '구글로 로그인' })).toBeInTheDocument()
})

it('says nothing of the sort in an ordinary browser', async () => {
  const api = inviteApi({ signedIn: false })
  server.use(...api.handlers())

  renderWithProviders(<App />, { initialEntries: [`/invite#t=${TOKEN}`] })

  expect(await screen.findByRole('link', { name: '구글로 로그인' })).toBeInTheDocument()
  expect(screen.queryByText(/구글 로그인이 막힙니다/)).not.toBeInTheDocument()
})

it('sends the token in the body and the name the person typed, and seats them', async () => {
  const api = inviteApi()
  server.use(...api.handlers())
  sessionStorage.setItem(INVITE_STORAGE_KEY, TOKEN)

  renderWithProviders(<App />, { initialEntries: ['/invite'] })
  await accept('  이신부  ')

  await waitFor(() => expect(api.joins).toHaveLength(1))
  // The name is the ACCEPTING person's own, trimmed the way the server measures
  // it (docs/api-spec.md § POST /weddings/join).
  expect(api.joins[0].body).toEqual({ token: TOKEN, name: '이신부' })
  expect(api.joins[0].contentType).toBe('application/json')

  // 원장 is where a seated person belongs, and the wedding came off the accept's
  // own response rather than from a second read.
  expect(await screen.findByRole('heading', { name: '원장' })).toBeInTheDocument()
  // Spent, and it can never work again.
  expect(stored()).toBeNull()
})

it('never sends a blank name', async () => {
  const api = inviteApi()
  server.use(...api.handlers())
  sessionStorage.setItem(INVITE_STORAGE_KEY, TOKEN)

  renderWithProviders(<App />, { initialEntries: ['/invite'] })
  await userEvent.click(await screen.findByRole('button', { name: '수락' }))

  // The same rule the server applies, spent without a round trip and saying
  // which field it was — which the 400 itself does not (`lib/name.ts`).
  expect(await screen.findByText('이름을 입력해 주세요.')).toBeInTheDocument()
  expect(api.joins).toHaveLength(0)
})

it('sends once for a double press, because the token is spent by the first', async () => {
  const api = inviteApi({ held: true })
  server.use(...api.handlers())
  sessionStorage.setItem(INVITE_STORAGE_KEY, TOKEN)

  renderWithProviders(<App />, { initialEntries: ['/invite'] })
  await userEvent.type(await screen.findByLabelText('내 이름'), '이신부')

  const button = screen.getByRole('button', { name: '수락' })
  await userEvent.click(button)
  // Still in flight: this is the press that matters. Serialising mutations
  // DELAYS a second one and never refuses it, so the `isPending` guard is the
  // only thing between a double tap and a second request carrying a token the
  // first one has already spent — which comes back as "이 자리는 이미
  // 채워졌습니다", told to the couple by their own thumb
  // (notes/2026-08-21-decision-query-defaults-and-mutation-ordering.md).
  await waitFor(() => expect(api.joins).toHaveLength(1))
  expect(button).toBeDisabled()
  await userEvent.click(button)

  api.release()
  expect(await screen.findByRole('heading', { name: '원장' })).toBeInTheDocument()
  expect(api.joins).toHaveLength(1)
})

it('tells an expired link apart, because its recovery is a new link', async () => {
  const api = inviteApi()
  server.use(...api.handlers(() => problem(404, 'INVITE_EXPIRED')))
  sessionStorage.setItem(INVITE_STORAGE_KEY, TOKEN)

  renderWithProviders(<App />, { initialEntries: ['/invite'] })
  await accept('이신부')

  expect(await screen.findByText('파트너에게 새 링크를 요청하세요.')).toBeInTheDocument()
  // Nothing here can ever succeed, so the token is dropped rather than left to
  // divert the next screen this person opens.
  expect(stored()).toBeNull()
})

it('points a superseded link at the newer one instead of at nothing', async () => {
  const api = inviteApi()
  // 재발급 killed this token, and the link that replaced it is on the other
  // person's phone (docs/api-spec.md § POST /weddings/join).
  server.use(...api.handlers(() => problem(404, 'INVITE_SUPERSEDED')))
  sessionStorage.setItem(INVITE_STORAGE_KEY, TOKEN)

  renderWithProviders(<App />, { initialEntries: ['/invite'] })
  await accept('이신부')

  expect(await screen.findByText('새 링크가 발급되었습니다')).toBeInTheDocument()
  expect(screen.getByText('파트너에게 최신 링크를 요청하세요.')).toBeInTheDocument()
  /*
   * NOT 이 링크는 사용할 수 없습니다, which is what this used to say and what the
   * whole change is against: it sends a person nowhere while a working link
   * sits in the other person's chat room
   * (notes/2026-08-22-decision-the-superseded-link-speaks.md).
   */
  expect(screen.queryByText('이 링크는 사용할 수 없습니다')).not.toBeInTheDocument()
  // No 내 원장 열기 either — this person has no ledger of their own, which is
  // the whole reason they were sent a link.
  expect(screen.queryByRole('link', { name: '내 원장 열기' })).not.toBeInTheDocument()
  // Settled: pressing again cannot change the answer, so the form goes.
  expect(screen.queryByLabelText('내 이름')).not.toBeInTheDocument()
  // And the dead token goes with it. Before `#201` this answer was
  // `INVITE_NOT_FOUND` and the token was dropped; a token left behind diverts
  // every empty ledger this person opens back to this screen (`lib/invite.ts`).
  expect(stored()).toBeNull()
})

it('says nothing more about a link it cannot use', async () => {
  const api = inviteApi()
  server.use(...api.handlers(() => problem(404, 'INVITE_NOT_FOUND')))
  sessionStorage.setItem(INVITE_STORAGE_KEY, TOKEN)

  renderWithProviders(<App />, { initialEntries: ['/invite'] })
  await accept('이신부')

  expect(await screen.findByText('이 링크는 사용할 수 없습니다')).toBeInTheDocument()
  // 만료와 같은 말을 하지 않는다: telling the two apart is what somebody
  // guessing tokens would want (docs/api-spec.md § POST /weddings/join).
  expect(screen.queryByText('파트너에게 새 링크를 요청하세요.')).not.toBeInTheDocument()
  expect(stored()).toBeNull()
})

it('treats a person who already has a ledger as having one, not as having failed', async () => {
  const api = inviteApi({
    weddings: [{ id: 3, weddingDate: '2026-09-09', seats: SEATS }],
  })
  server.use(...api.handlers(() => problem(409, 'ALREADY_IN_A_WEDDING')))
  sessionStorage.setItem(INVITE_STORAGE_KEY, TOKEN)

  renderWithProviders(<App />, { initialEntries: ['/invite'] })
  await accept('이신부')

  expect(await screen.findByText('이미 다른 웨딩에 속해 있습니다')).toBeInTheDocument()

  /*
   * THE TOKEN SURVIVES THIS ONE, and it is the only 409 that keeps it: the spec
   * says "the token is not spent, so the real partner can still use it". The
   * person who typed the wrong account's login is not the person the link was
   * meant for, and wiping it here would take the invite away from somebody who
   * never touched it.
   */
  expect(stored()).toBe(TOKEN)

  // 로그아웃 is on this screen, not only under the form: for somebody who
  // signed in as the wrong account it IS the recovery.
  expect(screen.getByRole('button', { name: '로그아웃' })).toBeVisible()

  // The spec's own recovery: open the wedding `GET /weddings` comes back with.
  // A held token cannot divert them back here — 원장 only reads it when the
  // list is EMPTY, and this person's is not (LedgerPage.tsx).
  await userEvent.click(screen.getByRole('link', { name: '내 원장 열기' }))
  expect(await screen.findByRole('heading', { name: '원장' })).toBeInTheDocument()
})

it('says it again on the second arrival instead of offering the form that refused them', async () => {
  // The list DISAGREES with the 409: it says this person holds no wedding. A
  // replica read or a late cache produces exactly this, and it is what turns
  // the recovery into a loop — 원장 hands an empty list straight back to 수락
  // (LedgerPage.tsx), which is the form that has just refused them.
  const api = inviteApi({ holdWeddings: true })
  server.use(...api.handlers(() => problem(409, 'ALREADY_IN_A_WEDDING')))
  sessionStorage.setItem(INVITE_STORAGE_KEY, TOKEN)

  renderWithProviders(<App />, { initialEntries: ['/invite'] })
  await accept('이신부')
  expect(await screen.findByText('이미 다른 웨딩에 속해 있습니다')).toBeVisible()

  // On 원장 now, with its list still in flight — this screen is gone, so what
  // comes back below is the second arrival and not this render.
  await userEvent.click(screen.getByRole('link', { name: '내 원장 열기' }))
  await waitFor(() =>
    expect(screen.queryByText('이미 다른 웨딩에 속해 있습니다')).not.toBeInTheDocument(),
  )
  api.releaseWeddings()

  /*
   * THE SCREEN SPEAKS RATHER THAN LOOPING. Neither half of the loop is
   * reverted — the token survives the 409 because the spec says it is not
   * spent, and 원장 sends an empty list here because that is exactly what a
   * partner who has not joined looks like (`#158`). What changes is that this
   * arrival remembers the verdict and says it.
   */
  expect(await screen.findByText('이미 다른 웨딩에 속해 있습니다')).toBeVisible()
  expect(screen.queryByLabelText('내 이름')).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '내 원장 열기' })).toBeVisible()
  expect(screen.getByRole('button', { name: '로그아웃' })).toBeVisible()

  // Nothing was pressed a second time, and the token is untouched: the verdict
  // is about this caller, never about the invite.
  expect(api.joins).toHaveLength(1)
  expect(stored()).toBe(TOKEN)
})

it('keeps the invite alive for the person who signed in as the wrong account', async () => {
  const api = inviteApi({
    weddings: [{ id: 3, weddingDate: '2026-09-09', seats: SEATS }],
  })
  server.use(...api.handlers(() => problem(409, 'ALREADY_IN_A_WEDDING')))
  sessionStorage.setItem(INVITE_STORAGE_KEY, TOKEN)

  const { unmount } = renderWithProviders(<App />, { initialEntries: ['/invite'] })
  await accept('이신부')
  await screen.findByText('이미 다른 웨딩에 속해 있습니다')

  /*
   * NOTHING ON THIS SCREEN NAMES THE SIGNED-IN ACCOUNT, so this 409 is the ONLY
   * signal that the wrong Google account was used — and it is the one answer
   * that must not wipe the token, because 로그아웃 → sign back in correctly →
   * this same screen is the whole recovery. Wiping it lands the real partner on
   * an empty `GET /weddings` instead: 웨딩 만들기, which they may never fill in
   * (`#158`).
   */
  /*
   * AND THE SCREEN NAMES THAT POSSIBILITY, because the exit being reachable is
   * not the same as it being guessable. Nothing in this flow says who is signed
   * in — 마이페이지 does (`#159`) and it sits behind 원장, which this person
   * cannot open — so 이미 다른 웨딩에 속해 있습니다 is true of the account they
   * are in and says nothing about the one they meant.
   */
  expect(
    screen.getByText('원장이 열리지 않으면 다른 계정으로 로그인한 것일 수 있습니다.'),
  ).toBeVisible()

  await userEvent.click(screen.getByRole('button', { name: '로그아웃' }))
  expect(await screen.findByRole('link', { name: '구글로 로그인' })).toBeInTheDocument()
  expect(stored()).toBe(TOKEN)

  /*
   * THE VERDICT DIES WITH THE LOGOUT, and that is the whole reason it is
   * cleared where the session is rather than left to expire on its own: the
   * next person to sign in on this tab is the one the link was meant for, and
   * they must be handed a form they can use.
   */
  unmount()
  const right = inviteApi()
  server.use(...right.handlers())
  renderWithProviders(<App />, { initialEntries: ['/invite'] })

  await accept('이신부')
  await waitFor(() => expect(right.joins).toHaveLength(1))
  expect(right.joins[0].body).toEqual({ token: TOKEN, name: '이신부' })
})

it('keeps the token when a 404 did not come from the application', async () => {
  const api = inviteApi()
  // Not `application/problem+json`: a proxy or the servlet container answered,
  // so `code` is `null` and this is no answer about the token at all
  // (`lib/api.ts`). A load balancer must not be able to destroy a live invite.
  server.use(...api.handlers(() => new HttpResponse('Not Found', { status: 404 })))
  sessionStorage.setItem(INVITE_STORAGE_KEY, TOKEN)

  renderWithProviders(<App />, { initialEntries: ['/invite'] })
  await accept('이신부')

  expect(await screen.findByRole('alert')).toHaveTextContent('초대를 수락하지 못했습니다')
  expect(stored()).toBe(TOKEN)
})

it('says the seat is gone when somebody else took it', async () => {
  const api = inviteApi()
  server.use(...api.handlers(() => problem(409, 'PARTNER_ALREADY_JOINED')))
  sessionStorage.setItem(INVITE_STORAGE_KEY, TOKEN)

  renderWithProviders(<App />, { initialEntries: ['/invite'] })
  await accept('이신부')

  expect(await screen.findByText('이 자리는 이미 채워졌습니다')).toBeInTheDocument()
  expect(stored()).toBeNull()
})

it('keeps the token when the name is what was refused', async () => {
  const api = inviteApi()
  server.use(...api.handlers(() => problem(400, 'VALIDATION_FAILED')))
  sessionStorage.setItem(INVITE_STORAGE_KEY, TOKEN)

  renderWithProviders(<App />, { initialEntries: ['/invite'] })
  await accept('이신부')

  expect(await screen.findByRole('alert')).toHaveTextContent('이름을 다시 확인해 주세요.')
  // The token is NOT spent by a refused name, so the form stays and so does the
  // token (docs/api-spec.md § POST /weddings/join).
  expect(screen.getByLabelText('내 이름')).toBeInTheDocument()
  expect(stored()).toBe(TOKEN)
})

it('is a screen a signed-in person can leave, and one a signed-out person cannot log out of', async () => {
  const api = inviteApi()
  server.use(...api.handlers())
  sessionStorage.setItem(INVITE_STORAGE_KEY, TOKEN)

  const { unmount } = renderWithProviders(<App />, { initialEntries: ['/invite'] })

  /*
   * A signed-in person here holds NO wedding — that is what sent them here — so
   * 원장 sends them back and 웨딩 만들기 is the one screen they may not be
   * handed. 로그아웃 is the only exit left, and it is the one "I used the wrong
   * Google account" needs: the token survives a sign-out on purpose.
   */
  expect(await screen.findByRole('button', { name: '로그아웃' })).toBeInTheDocument()
  unmount()

  const signedOut = inviteApi({ signedIn: false })
  server.use(...signedOut.handlers())
  renderWithProviders(<App />, { initialEntries: ['/invite'] })

  // Nothing to sign out of: the exit a signed-out person needs is the login.
  await screen.findByRole('link', { name: '구글로 로그인' })
  expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument()
})

it('tells a tab that came back without the token how to recover', async () => {
  const api = inviteApi()
  server.use(...api.handlers())

  renderWithProviders(<App />, { initialEntries: ['/invite'] })

  // The KakaoTalk webview case: the tab that came back from Google is not the
  // tab that stashed the token. The failure is safe — the token went nowhere —
  // but it is a dead end unless the screen says the link still works.
  expect(await screen.findByText('초대 정보가 없습니다')).toBeInTheDocument()
  expect(screen.getByText(/링크는 만든 지 하루 동안 쓸 수 있습니다/)).toBeInTheDocument()
  expect(api.joins).toHaveLength(0)
})
