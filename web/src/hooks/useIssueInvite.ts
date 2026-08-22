import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiError, apiFetch } from '../lib/api'
import type { paths } from '../lib/api-types.gen'
import { weddingsQueryKey } from './useWeddings'

// Reached through `paths[...]` rather than through the schema name, so the path
// and the status code are checked too. Response bodies are keyed `*/*` because
// no handler declares `produces` (docs/api-spec.md § The generated OpenAPI
// document) — consume it as written; correcting it is `#66`.

/**
 * `{token, expiresAt}` — the whole of a live invite, and the only time either
 * is ever published.
 *
 * THE TOKEN CAN NEVER BE READ BACK. Only a hash of it is stored, so there is no
 * endpoint that returns a link issued earlier and there will not be one — which
 * makes `expiresAt` memory-only too. A reload knows nothing about the link this
 * couple made a minute ago, and a screen built around re-displaying one cannot
 * exist against this API (docs/api-spec.md § POST /weddings/{weddingId}/invite).
 */
export type IssuedInvite =
  paths['/weddings/{weddingId}/invite']['post']['responses'][201]['content']['*/*']

/**
 * `POST /weddings/{weddingId}/invite` — 파트너 초대, and 재발급 is the same call.
 * Reissuing *is* issuing, and the previous token dies either way.
 *
 * IT SENDS NO BODY, and `Content-Type: application/json` all the same: the
 * standing content-type rule is a mapping condition, so a request without one
 * does not reach the handler at all. `apiFetch` sets it on every method that is
 * not a read, so there is nothing to remember here.
 *
 * NOTHING IS WRITTEN TO THE CACHE AND NOTHING IS INVALIDATED ON SUCCESS, and
 * that is argued rather than skipped. Issuing a link changes no 하객, no 인원수
 * and not the seats either — the seat fills when somebody *accepts* — so there
 * is no aggregate to carry (notes/2026-08-22-decision-the-partner-invite.md §6)
 * and no read that has gone stale. The response is not server state at all: it
 * is a value published once, held in the screen's own `useState` for as long as
 * the couple is looking at it, and gone after that by design.
 *
 * A 409 IS THE ONE ANSWER THAT TEACHES US SOMETHING, so it is the one that
 * invalidates. `PARTNER_ALREADY_JOINED` means our copy of `seats` is stale —
 * the partner accepted from their own phone while this tab sat open — and
 * re-reading the wedding is what takes the button away rather than leaving a
 * couple pressing something that can only be refused.
 */
export function useIssueInvite(weddingId: number) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (): Promise<IssuedInvite> => {
      const response = await apiFetch(`/weddings/${weddingId}/invite`, { method: 'POST' })
      if (!response.ok) throw await apiError(response)

      // Still a cast, as everywhere: generated types are compile-time only, so
      // nothing here has checked that the body matches what was declared.
      return (await response.json()) as IssuedInvite
    },
    onError: (error) => {
      // `exact`, because `['weddings']` is the prefix of every ledger and
      // headcount key and this is not their news.
      if (error instanceof ApiError && error.status === 409)
        void queryClient.invalidateQueries({ queryKey: weddingsQueryKey, exact: true })
    },
  })
}
