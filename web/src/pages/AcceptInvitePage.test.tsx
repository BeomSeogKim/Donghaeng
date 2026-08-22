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
 * branches on the API's error `code` in four directions that mean four
 * different things to the person reading them
 * (notes/2026-08-08-decision-frontend-testing-methodology.md).
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
}: {
  signedIn?: boolean
  weddings?: Wedding[]
  held?: boolean
} = {}) {
  const joins: { body: JoinWeddingRequest; contentType: string | null }[] = []
  // The list this person's `GET /weddings` answers, which a successful join
  // changes exactly as the server would.
  let held = weddings

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

  return {
    joins,
    release,
    handlers: (respond?: () => Response) => [
      http.get(`${API}/auth/me`, () =>
        signedIn
          ? HttpResponse.json<Session>({ id: 7, name: null })
          : problem(401, 'UNAUTHENTICATED'),
      ),
      http.get(`${API}/weddings`, () => HttpResponse.json<Wedding[]>(held)),
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

  // The spec's own recovery: open the wedding `GET /weddings` comes back with.
  // The token is dropped first, or 원장 would send them straight back here.
  expect(stored()).toBeNull()
  await userEvent.click(screen.getByRole('link', { name: '내 원장 열기' }))
  expect(await screen.findByRole('heading', { name: '원장' })).toBeInTheDocument()
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
