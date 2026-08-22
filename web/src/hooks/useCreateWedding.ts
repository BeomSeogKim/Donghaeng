import { useMutation, useQueryClient } from '@tanstack/react-query'
import { answerAlreadyInAWedding } from '../lib/alreadyInAWedding'
import { apiError, apiFetch } from '../lib/api'
import type { paths } from '../lib/api-types.gen'
import { setWedding, type Wedding } from './useWeddings'

// Reached through `paths[...]` rather than through the schema name, so the path
// and the status code are checked too. Response bodies are keyed `*/*` because
// no handler declares `produces` (docs/api-spec.md § The generated OpenAPI
// document) — consume it as written; correcting it is `#66`.

/**
 * 예식일과, 내가 어느 자리인지와, 내 이름. There is no fourth member and there
 * cannot be one — and the third is the CALLER's name, never their partner's
 * (changed 2026-08-22, docs/api-spec.md § POST /weddings).
 */
export type CreateWeddingRequest =
  paths['/weddings']['post']['requestBody']['content']['application/json']

// `Wedding` IS NOT DECLARED HERE. `POST /weddings`, `GET /weddings` and
// `GET /weddings/{weddingId}` return the same `WeddingResponse` — "one type for
// all three", in the spec's own words (docs/api-spec.md § GET /weddings) — and
// this hook writes its result into the list this hook's neighbour reads. Two
// aliases for one schema is how a `setQueryData` starts writing across a seam
// nothing checks, so `useWeddings` owns the type and this file imports it.

/**
 * `POST /weddings` — the wedding, both of its seats and its free subscription
 * term, in one transaction. One of the two endpoints in the product that are not
 * scoped to a wedding.
 *
 * NO AGGREGATE IS WRITTEN, and that is not an oversight of the "a mutation's
 * onSuccess writes the response into the cache" rule: a wedding is created
 * empty — no guests, no meal types — so there is no headcount to carry, and the
 * spec says so in as many words.
 *
 * THE WEDDING ITSELF IS WRITTEN INTO `GET /weddings`, and that is not a
 * nicety. The next screen is 원장, which resolves its wedding from that list and
 * sends a person with none to this form. The cached list is one request old and
 * says the person has no wedding, so without this write the couple would be
 * bounced straight back to the form they just submitted, for as long as the
 * refetch takes. It is prepended because the list is newest first, and that
 * order is contract (docs/api-spec.md § GET /weddings). `setWedding` is what
 * prepends it, and it is shared with the wedding's edit so that neither call
 * site can forget to cancel a read already in flight (`#174`).
 *
 * A 401 IS NOT HANDLED HERE. It means "log in again" rather than "something
 * went wrong" wherever it comes from, and the client answers it once for every
 * call in the app (`lib/queryClient.ts`). This hook answering it too was the
 * second of two answers to one status.
 *
 * 409 `ALREADY_IN_A_WEDDING` IS HANDED ON RATHER THAN DECIDED HERE. 초대 수락 is
 * told the same thing by the same check, so the answer belongs to neither
 * screen's hook and lives in one module (`lib/alreadyInAWedding.ts`). What it
 * does is refetch `GET /weddings`, which is the spec's recovery: the guard this
 * screen already has sends anybody holding a wedding to 원장, so a list that
 * comes back holding one opens it.
 */
export function useCreateWedding() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (request: CreateWeddingRequest): Promise<Wedding> => {
      const response = await apiFetch('/weddings', {
        method: 'POST',
        body: JSON.stringify(request),
      })
      if (!response.ok) throw await apiError(response)

      // Still a cast, as everywhere: generated types are compile-time only, so
      // nothing here has checked that the body matches what was declared.
      return (await response.json()) as Wedding
    },
    onSuccess: (wedding) => {
      setWedding(queryClient, wedding)
    },
    onError: (error) => answerAlreadyInAWedding(queryClient, error),
  })
}
