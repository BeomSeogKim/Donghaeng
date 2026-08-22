import { useMutation, useQueryClient } from '@tanstack/react-query'
import { apiError, apiFetch } from '../lib/api'
import type { paths } from '../lib/api-types.gen'
import { headcountQueryKey, setHeadcount } from './useHeadcount'
import { type Wedding, weddingsQueryKey } from './useWeddings'

// Reached through `paths[...]` rather than through the schema name, so the path
// and the status code are checked too. Response bodies are keyed `*/*` because
// no handler declares `produces` (docs/api-spec.md § The generated OpenAPI
// document) — consume it as written; correcting it is `#66`.

type UpdateWedding = paths['/weddings/{weddingId}']['patch']

/**
 * 예식일과 보증인원, and **only what is changing**.
 *
 * THIS IS THE PRODUCT'S FIRST PARTIAL UPDATE AND ITS RULES ARE THE OPPOSITE OF
 * A CREATE'S (docs/api-spec.md § Partial updates,
 * notes/2026-08-22-decision-partial-update-shape.md):
 *
 * - **a member left out is not written** — not rewritten with what it already
 *   had, not touched at all;
 * - **`null` clears it**, and is the ONLY spelling of "clear". `""`, `"  "` and
 *   `[]` are a 400 `MALFORMED_REQUEST_BODY`, so a blanked number input handed
 *   straight to `JSON.stringify` is a refused request rather than a clear;
 * - **`weddingDate` has no `null` branch in this type at all**, and that is the
 *   API's answer rather than the generator's: a wedding always has a date, so
 *   clearing it is a 400.
 *
 * Which members to send is the caller's decision and it is a real one — a body
 * carrying the date the form happened to load blind-writes whatever the partner
 * changed it to, and `wedding` has no audit trail to recover it from.
 */
export type UpdateWeddingRequest =
  UpdateWedding['requestBody']['content']['application/json']

/**
 * `{wedding, headcount}` — the wedding as it now stands, and the 인원수
 * recomputed inside the same transaction as the write.
 *
 * `wedding` DOES NOT CARRY 보증인원 and is not going to: one response may not
 * spell one number twice, so `headcount.guaranteedHeadcount` is the only
 * spelling, here and in `GET .../headcount` alike (docs/api-spec.md
 * § PATCH /weddings/{weddingId} — which reverses what that file predicted).
 */
export type WeddingMutation = UpdateWedding['responses'][200]['content']['*/*']

/**
 * `PATCH /weddings/{weddingId}` — 설정 · 웨딩 정보, and **the only way 보증인원
 * ever gets into the product.**
 *
 * THE NUMBER COMES OFF THIS RESPONSE AND IS NEVER FETCHED BESIDE IT. The
 * response carries the recomputed 인원수 and the spec refuses a
 * `GET .../headcount` after a successful PATCH in as many words. It is written
 * through `setHeadcount`, which cancels a read already in flight before writing
 * — a couple tabs back from KakaoTalk, a read starts, they save 보증인원, and
 * the older answer lands with no 보증인원 in it at all. A number lagging a tap
 * by 100ms is fine; a number moving backwards is not
 * (notes/2026-08-21-decision-query-defaults-and-mutation-ordering.md).
 *
 * THE WEDDING IS WRITTEN INTO `GET /weddings` BECAUSE THAT LIST IS WHERE EVERY
 * SCREEN READS IT FROM. 원장 resolves its wedding by taking `[0]` of it and
 * renders 예식일 from that entry, so without this write the couple walks back to
 * a header still showing the date they just changed.
 *
 * THE INVALIDATION IS `exact`, AND THAT IS NOT TIDINESS. `headcountQueryKey` is
 * `['weddings', id, 'headcount']` — a prefix match on `['weddings']`, so a
 * plain invalidation would fire exactly the headcount read the spec forbids
 * after a successful save, and put the out-of-order race back with it.
 *
 * IT IS `onSettled` FOR THE LIST AND `onError` FOR THE NUMBER, which is the
 * same backstop split by what we know. A PATCH can commit and lose its response
 * — a dropped connection, a timeout — and mutations are `retry: 0`, so
 * `onSuccess` never runs and the screen would sit on a value the server no
 * longer holds (`#135`'s review). After a *successful* save we hold both
 * answers already, so the number is only re-read in the branch where we hold
 * neither.
 *
 * NEITHER IS AWAITED. query-core waits for whatever `onSettled` returns before
 * releasing the next mutation, so returning the invalidation would put a full
 * refetch between two presses.
 */
export function useUpdateWedding(weddingId: number) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (request: UpdateWeddingRequest): Promise<WeddingMutation> => {
      const response = await apiFetch(`/weddings/${weddingId}`, {
        method: 'PATCH',
        body: JSON.stringify(request),
      })
      if (!response.ok) throw await apiError(response)

      // Still a cast, as everywhere: generated types are compile-time only, so
      // nothing here has checked that the body matches what was declared.
      return (await response.json()) as WeddingMutation
    },
    onSuccess: (updated) => {
      setHeadcount(queryClient, weddingId, updated.headcount)
      queryClient.setQueryData(
        weddingsQueryKey,
        (weddings: readonly Wedding[] | undefined) =>
          weddings?.map((wedding) =>
            wedding.id === updated.wedding.id ? updated.wedding : wedding,
          ),
      )
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: headcountQueryKey(weddingId) })
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: weddingsQueryKey, exact: true })
    },
  })
}
