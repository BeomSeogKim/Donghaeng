import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { expect, it } from 'vitest'
import { App } from '../App'
import type { Headcount } from '../hooks/useHeadcount'
import type { Session } from '../hooks/useSession'
import type { Wedding } from '../hooks/useWeddings'
import { renderWithProviders } from '../test/render'
import { server } from '../test/server'

/*
 * 마이페이지 — who is signed in on this device, and the way out.
 *
 * IT EXISTS BECAUSE TWO ACCOUNTS EDIT ONE 원장. A couple share phones, and a
 * screen that never says whose session it is lets one of them edit the ledger
 * signed in as the other (`#159`). So the assertions here are on the name the
 * person actually reads, including the case where the provider gave us none.
 *
 * Only the network is faked. The route, the guard and the session cache are the
 * real ones, because the failure worth catching is 마이페이지 rendering for
 * somebody the guard should have sent to 로그인.
 */

const API = 'http://localhost:8080'

const signedIn = (name: Session['name']) => HttpResponse.json<Session>({ id: 12, name })

/** `GET /auth/me`, and the two reads 설정 makes when it is walked back to. */
function api(name: Session['name'] = '김테스터') {
  return [
    http.get(`${API}/auth/me`, () => signedIn(name)),
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
    http.get(`${API}/weddings/:weddingId/headcount`, () =>
      HttpResponse.json<Headcount>({ mealHeadcount: 0 }),
    ),
  ]
}

it('says which account this device is signed in as', async () => {
  server.use(...api('김테스터'))

  renderWithProviders(<App />, { initialEntries: ['/me'] })

  expect(await screen.findByRole('heading', { name: '마이페이지' })).toBeVisible()
  // Scoped to the section, because the running head above every signed-in
  // screen now names the account too — which is the same answer to `#159`,
  // given everywhere rather than only here.
  expect(
    within(screen.getByRole('region', { name: '로그인한 계정' })).getByText('김테스터'),
  ).toBeVisible()
})

it('names the account when the provider handed us no name at all', async () => {
  // `name` is nullable and this is the ordinary answer for a provider that
  // returns none (docs/api-spec.md § GET /auth/me) — not an error, and not a
  // blank where the answer to "who am I" belongs.
  server.use(...api(null))

  renderWithProviders(<App />, { initialEntries: ['/me'] })

  expect(await screen.findByText('이름 없음')).toBeVisible()
  // The id is the identity, never display text — and a substring match, because
  // `ID 12` would satisfy a whole-node one while putting it on screen anyway.
  expect(screen.queryByText(/12/)).not.toBeInTheDocument()
})

it('offers no edit for a name the next login would overwrite', async () => {
  server.use(...api('김테스터'))

  renderWithProviders(<App />, { initialEntries: ['/me'] })
  await screen.findByText('김테스터')

  // v1 shows what the provider gives and nothing else (docs/api-spec.md
  // § GET /auth/me). A field here would accept a name and lose it at the next
  // sign-in.
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '저장' })).not.toBeInTheDocument()
})

it('is the screen 로그아웃 sits on, and signing out leaves for 로그인', async () => {
  let signedOut = false
  server.use(
    http.get(`${API}/auth/me`, () =>
      signedOut
        ? HttpResponse.json(
            {
              type: 'about:blank',
              title: 'Unauthorized',
              status: 401,
              instance: '/auth/me',
              code: 'UNAUTHENTICATED',
            },
            { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
          )
        : signedIn('김테스터'),
    ),
    http.post(`${API}/auth/logout`, () => {
      signedOut = true
      return new HttpResponse(null, { status: 204 })
    }),
  )

  renderWithProviders(<App />, { initialEntries: ['/me'] })

  await userEvent.click(await screen.findByRole('button', { name: '로그아웃' }))

  expect(await screen.findByRole('link', { name: '구글로 로그인' })).toBeVisible()
})

it('goes back to 설정, which is the screen it is reached from', async () => {
  server.use(...api('김테스터'))

  renderWithProviders(<App />, { initialEntries: ['/me'] })
  await userEvent.click(await screen.findByRole('link', { name: '설정' }))

  // Two taps from 원장 and two taps back — the depth a screen visited about once
  // a session is worth (`#159`).
  expect(await screen.findByRole('heading', { name: '설정' })).toBeVisible()
})
