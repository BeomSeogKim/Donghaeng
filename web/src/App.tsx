import type { ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router'
import { BrandMark } from './components/BrandMark'
import { Screen } from './components/Screen'
import { SessionUnavailable } from './components/SessionUnavailable'
import { useSession } from './hooks/useSession'
import { createWeddingPath, settingsPath } from './lib/routes'
import { CreateWeddingPage } from './pages/CreateWeddingPage'
import { LedgerPage } from './pages/LedgerPage'
import { LoginPage } from './pages/LoginPage'
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

  if (session.isPending) {
    return (
      <Screen>
        <BrandMark />
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

  /** A screen only a signed-in person may see. */
  const signedIn = (screen: () => ReactNode) =>
    person === null ? <Navigate replace to="/login" /> : screen()

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
      {/* The OAuth callback returns the browser to the configured frontend
          origin — the root — so there is no callback route to write. Anything
          else is a mistyped URL, and the root will sort out where it belongs. */}
      <Route element={<Navigate replace to="/" />} path="*" />
    </Routes>
  )
}
