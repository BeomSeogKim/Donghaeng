import { useQuery, useQueryClient } from '@tanstack/react-query'
import { answerAlreadyInAWedding } from '../lib/alreadyInAWedding'
import { apiError, apiFetch } from '../lib/api'
import type { paths } from '../lib/api-types.gen'
import { forgetSpentInvite } from '../lib/invite'

// Reached through `paths[...]` rather than through the schema name, so the path
// and the status code are checked too. Response bodies are keyed `*/*` because
// no handler declares `produces` (docs/api-spec.md § The generated OpenAPI
// document) — consume it as written; correcting it is `#66`.

/**
 * 결혼식 이름 · 예식일 · 초대한 사람, and nothing else.
 *
 * THERE IS NO `weddingId` IN IT AND THERE WILL NOT BE. Nothing this read
 * answers can be carried to a wedding-scoped endpoint: the caller learns whose
 * wedding this is, not which one to ask about (docs/api-spec.md
 * § POST /weddings/join/preview). Do not build a screen that pre-fetches a
 * ledger from a preview.
 *
 * `invitedBy` IS THE SEAT THAT IS ALREADY TAKEN — the partner who sent the
 * link, never the empty seat this token would fill.
 */
export type InvitePreview =
  paths['/weddings/join/preview']['post']['responses'][200]['content']['*/*']

/**
 * `POST /weddings/join/preview` — what the link opens, before anybody takes the
 * seat.
 *
 * **IT IS A READ, SO IT IS A QUERY, AND THE POST IS THE TOKEN'S DOING.** The
 * method belongs to the credential rather than to the operation: a token may
 * not travel in a path or a query string, ours or anyone's, so it travels in a
 * body — and a body means POST. Nothing is written, no token is spent, and it
 * may be called twice with the same token still joining afterwards.
 *
 * WHY THE SCREEN ASKS AT ALL. The person about to accept is making the one
 * irreversible choice in the product — a seat, once taken, cannot be released,
 * and a person belongs to exactly one wedding, ever. Naming the wedding
 * beforehand is what makes that choice an informed one, and it tells them
 * strictly less than accepting would: **it is the token that makes this safe,
 * not the session.**
 *
 * IT NEEDS A SESSION ALL THE SAME, and the caller is what enforces that. An
 * anonymous request is a 401 whatever it sends, so this belongs to the state
 * AFTER the Google round trip — the pre-sign-in screen cannot name the wedding
 * it is inviting somebody into, and must not try. `enabled` is not used for
 * that: the accept screen renders a different component before there is a
 * session, so this hook is not mounted at all.
 *
 * THE TOKEN IS IN THE QUERY KEY AND NOWHERE ELSE NEW. The key lives in memory
 * for as long as the cache does — nothing persists this client, and a persisted
 * one would be the change that needs arguing, not this. Keying on the token is
 * what makes a second token a second answer rather than a stale one.
 *
 * TWO OF ITS FAILURES ARE NOT THIS SCREEN'S TO INTERPRET, and both are answered
 * here where the error actually is, rather than during a render:
 *
 * - a refusal that ends the token's life drops it, exactly as the accept does
 *   (`lib/invite.ts`) — without this the preview would be a regression, since
 *   a person who is refused before the tap never taps and nothing else would
 *   ever drop it;
 * - `ALREADY_IN_A_WEDDING` is a fact about the CALLER's account, checked by the
 *   API before the token is even looked at, and the product has exactly one
 *   answer to it wherever it is raised (`lib/alreadyInAWedding.ts`).
 *
 * NO RETRY TO WORRY ABOUT: the client's default refuses to retry a 4xx, which
 * is every refusal this endpoint has (`lib/queryClient.ts`).
 */
export function useInvitePreview(token: string) {
  const queryClient = useQueryClient()

  return useQuery({
    queryKey: ['invite-preview', token],
    queryFn: async (): Promise<InvitePreview> => {
      const response = await apiFetch('/weddings/join/preview', {
        method: 'POST',
        body: JSON.stringify({ token }),
      })
      if (!response.ok) {
        const error = await apiError(response)
        forgetSpentInvite(error)
        answerAlreadyInAWedding(queryClient, error)
        throw error
      }

      // Still a cast, as everywhere: generated types are compile-time only, so
      // nothing here has checked that the body matches what was declared.
      return (await response.json()) as InvitePreview
    },
  })
}
