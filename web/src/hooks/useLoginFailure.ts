import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'

/*
 * How a failed OAuth callback gets back into the app.
 *
 * The callback is a browser navigation, so none of our code runs during it. A
 * failure returns the browser to `<frontend origin>/login` with a code in the
 * URL fragment and no session cookie — `#e=denied` or `#e=failed`, and that is
 * the entire vocabulary (docs/api-spec.md § GET /login/oauth2/code/google,
 * notes/2026-08-13-decision-login-failure-return-path.md).
 *
 * THE VALUE IS SWITCHED ON, NEVER RENDERED. At this end the fragment is fully
 * attacker-controlled — anyone can send a victim to `<frontend>/login#e=…`
 * without touching our API — so it is mapped to one of two constants here and
 * an unrecognised code falls to `failed`, exactly as the spec instructs. The
 * words the user reads are ours, chosen from the constant.
 */
export type LoginFailure = 'denied' | 'failed'

/**
 * The failure the person arrived with, or `null` if they simply opened the
 * login screen. Reading it consumes it: the fragment is stripped from the URL,
 * while the returned value stays until the screen unmounts.
 */
export function useLoginFailure(): LoginFailure | null {
  const { hash, pathname, search, state } = useLocation()
  const navigate = useNavigate()
  const [failure, setFailure] = useState<LoginFailure | null>(null)

  /*
   * External sync, and the outside-of-React thing is the address bar: the URL
   * still says a login was refused after we have said so. Left alone it
   * re-announces itself on a reload, and on any later navigation back to
   * /login from history.
   *
   * THE CODE IS LATCHED HERE, NOT READ ONCE AT MOUNT. A fragment can arrive at
   * a screen that is already mounted — an in-app navigate, or a Back to a
   * history entry that still carries one — and a mount-time read would render
   * nothing while this effect stripped the fragment, destroying the failure
   * rather than missing it. Clearing re-runs the effect with no code present,
   * which sets nothing, so the message survives the clear without reappearing.
   *
   * Only a fragment we actually read is cleared: `/login#top` is not a failure
   * (readFailure, below) and rewriting it would silently break any anchor or
   * deep link. `state` is carried through because the obvious
   * redirect-after-login is `<Navigate to="/login" state={{ from }} />`, and
   * dropping it would delete the destination on every failed return.
   */
  useEffect(() => {
    const code = readFailure(hash)
    if (code === null) return
    setFailure(code)
    navigate({ pathname, search, hash: '' }, { replace: true, state })
  }, [hash, pathname, search, state, navigate])

  return failure
}

function readFailure(hash: string): LoginFailure | null {
  const code = new URLSearchParams(hash.replace(/^#/, '')).get('e')
  if (code === null) return null
  return code === 'denied' ? 'denied' : 'failed'
}
