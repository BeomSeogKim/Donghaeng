import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { pendingInvite, readInviteToken, rememberInvite } from '../lib/invite'

/**
 * The invite token this tab is holding, however it got here — and `null` when
 * there is none to hold.
 *
 * IT IS CALLED ABOVE THE SESSION GATE, IN `App`, AND THAT PLACEMENT IS THE
 * WHOLE POINT (moved there in review of `#182`). An arrival from KakaoTalk is
 * always a cold load, so `GET /auth/me` has not answered yet — and while it has
 * not, `App` renders the brand mark instead of the route table. Reading the
 * fragment from the accept screen therefore left `#t=<token>` in the address
 * bar for a whole network round trip, and **indefinitely** behind 다시 시도 when
 * the API is unreachable, because that branch never renders the route table at
 * all. A one-day bearer credential must not sit in the address bar waiting on a
 * server: that is Back, the share sheet and any screenshot.
 *
 * TWO ARRIVALS, ONE ANSWER. A partner tapping a KakaoTalk link arrives with the
 * token in the URL fragment; the same partner coming back from Google arrives
 * with nothing in the URL at all, because the round trip returns to the
 * configured origin and knows nothing about where they were
 * (notes/2026-08-22-decision-the-invite-link.md §3). `sessionStorage` is what
 * joins the two, and it is written here on the first arrival so the second one
 * has something to read.
 *
 * IT IS READ SYNCHRONOUSLY AND PERSISTED IN AN EFFECT, which is the split that
 * matters. Reading in the Effect would render one frame of "초대 정보가 없습니다"
 * at somebody holding a perfectly good link. Persisting in the Effect is
 * correct on its own terms: `sessionStorage` and the address bar are both
 * outside React, and that is the only thing an Effect is for.
 *
 * THE FRAGMENT IS CLEARED THE MOMENT IT IS READ, and that is a decision rather
 * than tidiness — it takes the token out of Back and out of the browser's share
 * sheet. `state` is carried through the replace for the same reason
 * `useLoginFailure` carries it: a navigation's state is not this hook's to drop.
 *
 * THE CODE IS LATCHED, NOT READ ONCE AT MOUNT. A fragment can arrive at a
 * component that is already mounted — a Back to a history entry that still
 * carries one — and clearing it must not destroy what it carried.
 */
export function useInviteToken(): string | null {
  const { hash, pathname, search, state } = useLocation()
  const navigate = useNavigate()
  const [token, setToken] = useState<string | null>(
    () => readInviteToken(hash) ?? pendingInvite(),
  )

  useEffect(() => {
    const arriving = readInviteToken(hash)
    if (arriving === null) return
    rememberInvite(arriving)
    setToken(arriving)
    navigate({ pathname, search, hash: '' }, { replace: true, state })
  }, [hash, pathname, search, state, navigate])

  return token
}
