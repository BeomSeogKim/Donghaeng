import type { ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router'
import { BrandMark } from './components/BrandMark'
import { Screen } from './components/Screen'
import { SessionUnavailable } from './components/SessionUnavailable'
import { useInviteToken } from './hooks/useInviteToken'
import { type Session, useSession } from './hooks/useSession'
import { createWeddingPath, invitePath, myPagePath, settingsPath } from './lib/routes'
import { AcceptInvitePage } from './pages/AcceptInvitePage'
import { CreateWeddingPage } from './pages/CreateWeddingPage'
import { LedgerPage } from './pages/LedgerPage'
import { LoginPage } from './pages/LoginPage'
import { MyPage } from './pages/MyPage'
import { SettingsPage } from './pages/SettingsPage'

/*
 * The route table, and the one place that decides whether a screen is reachable.
 *
 * PLAIN <Routes>, NO LOADERS — decided here, recorded in
 * notes/2026-08-13-decision-frontend-routing.md. React Router's data APIs are a
 * second place to fetch, and server state already has exactly one home: the
 * React Query cache. Two of them would eventually disagree about the headcount,
 * which is the number this product may never get wrong.
 *
 * The guard is one function applied per route, because that is all the two
 * protected screens share. It becomes a layout route rendering an <Outlet> the
 * moment they share a shell as well — that is a mechanical change, and
 * inventing the nesting before there is anything to put in it would not be.
 *
 * NOTHING RENDERS BEFORE THE ANSWER. The session is resolved above the table, so
 * no screen is shown to someone who might not be entitled to it and the login
 * button never flashes at a person who is already signed in. On a cold load
 * this is one round trip; the alternative is a screen that changes its mind.
 */
export function App() {
  const session = useSession()

  /*
   * ABOVE THE SESSION GATE ON PURPOSE, and it is the only thing here that is.
   * An invite arrives as a cold load with `#t=<token>` in the address bar, and
   * everything below this line waits on `GET /auth/me` — the pending branch
   * renders the brand mark, and the error branch renders 다시 시도 and never
   * reaches the route table at all. Reading the fragment from the accept screen
   * left a one-day bearer credential in the address bar for a network round
   * trip, and forever when the API was unreachable (found in review of `#182`).
   *
   * It is a hook and not a redirect, so it costs a signed-in couple with no
   * fragment nothing: one `URLSearchParams` on a string that is almost always
   * empty.
   */
  const invite = useInviteToken()

  if (session.isPending) {
    return (
      <Screen>
        <BrandMark heading />
      </Screen>
    )
  }

  if (session.isError) {
    return (
      <SessionUnavailable
        onRetry={() => {
          void session.refetch()
        }}
      />
    )
  }

  const person = session.data

  /**
   * A screen only a signed-in person may see.
   *
   * IT HANDS THE PERSON DOWN, because one screen is *about* them: 마이페이지
   * would otherwise carry a signed-out branch for a state this function has
   * just made unreachable. A screen that does not want the argument ignores it.
   */
  const signedIn = (screen: (person: Session) => ReactNode) =>
    person === null ? <Navigate replace to="/login" /> : screen(person)

  return (
    <Routes>
      <Route
        element={person === null ? <LoginPage /> : <Navigate replace to="/" />}
        path="/login"
      />
      {/* 원장 IS HOME (notes/2026-08-07-design-screens-and-flow.md). Everything
          else is a sheet that opens over it or a flow that leaves and comes
          back, so `/` is the ledger rather than a screen that links to it. */}
      <Route element={signedIn(() => <LedgerPage />)} path="/" />
      <Route element={signedIn(() => <CreateWeddingPage />)} path={createWeddingPath} />
      {/* 설정 · 웨딩 정보 — the one screen a couple navigates to and back from,
          and the shell `#9`'s 파트너 초대 joins. */}
      <Route element={signedIn(() => <SettingsPage />)} path={settingsPath} />
      {/* 마이페이지 — the account, and the only screen a signed-in person is
          parked on with something to read. Reached from 설정 and not from the
          ledger header, which is why 로그아웃 could leave 원장 without leaving
          the app (notes/2026-08-22-decision-logout-leaves-the-ledger.md). */}
      <Route
        element={signedIn((person) => <MyPage person={person} />)}
        path={myPagePath}
      />
      {/* 초대 수락 — NOT behind `signedIn`, and it is the only screen besides
          로그인 that is not. The person holding an invite link is almost always
          signed out, and the token in its fragment has to be stashed BEFORE the
          Google round trip: sending them to /login first would take the
          fragment away with the navigation, and the alternative to that is a
          returnTo, which is the thing this flow exists to avoid
          (notes/2026-08-22-decision-the-invite-link.md §3). */}
      <Route element={<AcceptInvitePage token={invite} />} path={invitePath} />
      {/* The OAuth callback returns the browser to the configured frontend
          origin — the root — so there is no callback route to write. Anything
          else is a mistyped URL, and the root will sort out where it belongs. */}
      <Route element={<Navigate replace to="/" />} path="*" />
    </Routes>
  )
}
