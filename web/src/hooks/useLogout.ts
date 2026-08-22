import { useMutation, useQueryClient } from '@tanstack/react-query'
import { forgetAlreadyInAWedding } from '../lib/alreadyInAWedding'
import { apiError, apiFetch } from '../lib/api'
import { sessionQueryKey } from './useSession'

/**
 * `POST /auth/logout` — ends the session on this device only.
 *
 * POST is not a style choice. `SameSite=Lax` does send the cookie on top-level
 * GET navigation, so a logout reachable by GET could be fired by an `<img>` on
 * any page the couple visit. What closes the POST is not Lax either — a sibling
 * host is same-site with the API — but the CORS preflight forced by the
 * `Content-Type: application/json` that `apiFetch` sends
 * (`notes/2026-08-13-decision-static-front-and-content-type-gate.md`).
 *
 * It always answers 204 — no cookie, an expired session, one already revoked,
 * all of them mean "you are not logged in on this device", which is what was
 * asked for. So there is no error path to map; a non-204 here means the request
 * never reached the API, and the button says so rather than pretending.
 */
export function useLogout() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async () => {
      const response = await apiFetch('/auth/logout', { method: 'POST' })
      if (!response.ok) throw await apiError(response)
    },
    onSuccess: () => {
      // The answer is already known, so it is written rather than asked for: the
      // login screen appears now instead of after a round trip that can only say
      // "signed out" again.
      queryClient.setQueryData(sessionQueryKey, null)

      // Everything else was fetched for the person who just signed out, and the
      // couple share each other's phones. (`queryClient.clear()` would be wrong
      // here — it detaches the mounted session observer from its cache entry,
      // and the screen then never hears that the session ended.)
      queryClient.removeQueries({
        predicate: (query) => query.queryKey[0] !== sessionQueryKey[0],
      })

      // And the one thing the couple was told that is not in the cache: that
      // this account already holds a wedding. It is a fact about the person who
      // just left, so the next one to sign in on this tab must not meet a
      // screen built on it (`lib/alreadyInAWedding.ts`).
      forgetAlreadyInAWedding()
    },
  })
}
