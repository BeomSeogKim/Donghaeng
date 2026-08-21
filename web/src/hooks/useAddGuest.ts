import { useMutation, useQueryClient } from '@tanstack/react-query'
import { apiError, apiFetch } from '../lib/api'
import type { paths } from '../lib/api-types.gen'
import { ledgerQueryKey } from './useGuests'
import { setHeadcount } from './useHeadcount'

// Reached through `paths[...]` rather than through the schema name, so the path
// and the status code are checked too. Response bodies are keyed `*/*` because
// no handler declares `produces` (docs/api-spec.md § The generated OpenAPI
// document) — consume it as written; correcting it is `#66`.

type AddGuest = paths['/weddings/{weddingId}/guests']['post']

/**
 * 하객 한 줄. **`name` and `side` are the only required members** and everything
 * else may be sent as `null`, which the server treats exactly as an omitted one
 * — so a control the couple left alone does not have to be built into the body
 * conditionally (docs/api-spec.md § POST /weddings/{weddingId}/guests).
 *
 * `groupCategory` IS THE ONE MEMBER THAT MAY NOT BE `null`, and that is the
 * generated type rather than the endpoint: `openapi-typescript` renders an enum
 * as the union of its values with no null branch. Send a value or omit it.
 */
export type AddGuestRequest = AddGuest['requestBody']['content']['application/json']

/**
 * `{guest, headcount}` — the row as stored, and the number recomputed inside
 * the same transaction as the write.
 *
 * THE ENVELOPE IS NOT UNWRAPPED HERE. `#12`'s edit and `#13`'s attendance
 * toggle return this same shape, so a hook that handed back only the guest
 * would have to be rewritten by the first caller that needs the number — which
 * is every caller (docs/api-spec.md).
 */
export type GuestMutation = AddGuest['responses'][201]['content']['*/*']

/**
 * `POST /weddings/{weddingId}/guests` — 하객 추가, and in v1 the ONLY way a row
 * enters a ledger: the vendor-email parser and the CSV import are post-v1.
 *
 * THE NUMBER COMES OFF THIS RESPONSE AND IS NEVER FETCHED BESIDE IT. A
 * `GET .../headcount` fired alongside the mutation lands outside the window
 * mutations are serialised in, which puts the out-of-order race straight back —
 * and a number that moves backwards is the one thing this product may not do
 * (notes/2026-08-21-decision-query-defaults-and-mutation-ordering.md).
 *
 * THE LEDGER IS INVALIDATED RATHER THAN WRITTEN INTO, and the two filters are
 * why. Which filter combinations a new row belongs in is the server's answer,
 * and reproducing it here would be a second implementation of 원장's filtering
 * — the same class of mistake as computing the headcount client-side. The
 * invalidation targets `ledgerQueryKey`, the whole ledger, so a guest added
 * while the 신랑측 chip is pressed does not leave the unfiltered list stale
 * behind it (notes/2026-08-21-decision-ledger-screen.md § Query keys).
 *
 * IT IS NOT AWAITED. Returning the promise from `onSuccess` makes query-core
 * wait for the refetch before releasing the next mutation, which is exactly the
 * serialisation this product's tap rate must not pay for.
 */
export function useAddGuest(weddingId: number) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (request: AddGuestRequest): Promise<GuestMutation> => {
      const response = await apiFetch(`/weddings/${weddingId}/guests`, {
        method: 'POST',
        body: JSON.stringify(request),
      })
      if (!response.ok) throw await apiError(response)

      // Still a cast, as everywhere: generated types are compile-time only, so
      // nothing here has checked that the body matches what was declared.
      return (await response.json()) as GuestMutation
    },
    onSuccess: (created) => {
      setHeadcount(queryClient, weddingId, created.headcount)
      void queryClient.invalidateQueries({ queryKey: ledgerQueryKey(weddingId) })
    },
  })
}
