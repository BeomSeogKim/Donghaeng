import type { QueryClient } from '@tanstack/react-query'
import { useQuery } from '@tanstack/react-query'
import { apiError, apiFetch } from '../lib/api'
import type { paths } from '../lib/api-types.gen'
import { weddingsQueryKey } from './useWeddings'

// Reached through `paths[...]` rather than through the schema name, so the path
// and the status code are checked too. Response bodies are keyed `*/*` because
// no handler declares `produces` (docs/api-spec.md § The generated OpenAPI
// document) — consume it as written; correcting it is `#66`.

/**
 * 인원수 — 식대 인원, and 보증인원 when the couple has agreed one.
 *
 * THIS IS THE ONLY DECLARATION OF IT. Every guest mutation returns the same
 * object under its `headcount` member — `{guest, headcount}` — computed inside
 * the same transaction as the write, so a second alias would be a `setQueryData`
 * across a seam nothing checks (docs/api-spec.md § POST /weddings/{weddingId}/guests).
 *
 * `guaranteedHeadcount` IS ABSENT, NOT NULL, until the couple has a number from
 * their venue — 설정 · 웨딩 정보 is where they enter it, and where they can empty
 * it again. Cleared and never-set are one state and read the same way. The
 * generated type spells it `?: number | null` because OpenAPI cannot say
 * "omitted"; the server never sends `null`, so `== null` is the one check to
 * write and it means "no number yet"
 * (docs/api-spec.md § GET /weddings/{weddingId}/headcount).
 */
export type Headcount =
  paths['/weddings/{weddingId}/headcount']['get']['responses'][200]['content']['*/*']

/**
 * The wedding's number. A sibling of `ledgerQueryKey` under the same wedding,
 * because it is the same wedding's — and because signing out removes every key
 * whose first element is not the session, by prefix.
 */
export function headcountQueryKey(weddingId: number) {
  return [...weddingsQueryKey, weddingId, 'headcount'] as const
}

/**
 * `GET /weddings/{weddingId}/headcount` — the number pinned to the top of 원장.
 *
 * IT IS READ BESIDE THE LEDGER, NOT AFTER IT. 원장 and 인원수 are one screen but
 * two responses: `GET .../guests` is a read and carries no aggregate, so the
 * screen opens both, in parallel (docs/api-spec.md). Neither waits for the
 * other, and neither one's failure decides the other's.
 *
 * AFTER A MUTATION IT IS NOT ASKED AGAIN — see `setHeadcount` below.
 */
export function useHeadcount(weddingId: number) {
  return useQuery({
    queryKey: headcountQueryKey(weddingId),
    queryFn: () => fetchHeadcount(weddingId),
  })
}

async function fetchHeadcount(weddingId: number): Promise<Headcount> {
  const response = await apiFetch(`/weddings/${weddingId}/headcount`)
  if (!response.ok) throw await apiError(response)

  // Still a cast, as everywhere: generated types are compile-time only, so
  // nothing here has checked that the body matches what was declared.
  return (await response.json()) as Headcount
}

/**
 * Write the number a mutation just returned, and the one thing every guest
 * mutation must call.
 *
 * IT BELONGS IN THE MUTATION'S OWN `onSuccess`, and nowhere else:
 *
 *     onSuccess: (response) => setHeadcount(queryClient, weddingId, response.headcount)
 *
 * `POST .../guests` already carries the recomputed aggregate and `#12`/`#13`
 * follow. Fetching it instead — a `GET .../headcount` fired alongside the
 * mutation — lands outside the window mutations are serialised in and puts the
 * out-of-order race straight back, which is the one thing the number may not
 * have: a number lagging the tap by 100ms is fine, a number moving backwards is
 * not (notes/2026-08-21-decision-query-defaults-and-mutation-ordering.md).
 *
 * It exists as a function rather than as a documented `setQueryData` so the key
 * and the shape are bound together and checked: a caller cannot write a guest,
 * or another wedding's number, into this key by hand — and so the cancel below
 * cannot be the thing a call site forgets.
 *
 * A READ ALREADY IN FLIGHT IS CANCELLED FIRST, AND THAT IS THE WHOLE POINT OF
 * THE FIRST LINE. This query runs `staleTime: 0` and `refetchOnWindowFocus`,
 * both deliberate, so the ordinary sequence is real: a couple tabs back from
 * KakaoTalk, a read starts that will answer 128, they tap 추가, the write
 * answers 129 — and then the older read lands. query-core's fetch resolution
 * calls `setData` unconditionally, with no comparison of when the two answers
 * were computed, and `setQueryData` does not cancel anything on its own. The
 * pinned 인원수 would then read 128 beside a sheet saying 129. **A number
 * lagging a tap by 100ms is fine; a number moving backwards is not.**
 *
 * THE ORDER OF THESE TWO LINES IS LOAD-BEARING, and not for the obvious reason.
 * `cancelQueries` defaults to `revert: true`, which restores the state captured
 * when that fetch STARTED — the stale number. It applies it synchronously,
 * inside the call: `retryer.cancel()` rejects and then invokes `onCancel`,
 * which is what calls `setState(revertState)`. So the revert happens first and
 * the write below overwrites it. **Reverse these two lines and the stale value
 * is what survives.** `revert: false` is worse than either order: a
 * `CancelledError` that is neither silent nor reverting falls through to the
 * query's error dispatch, and the screen drops the number entirely.
 *
 * Awaiting the returned promise and writing in the `then` is *not* broken —
 * corrected 2026-08-22, having been claimed here as a third way to break it.
 * The revert is already applied by the time that promise settles, and it
 * restores the value that was on screen anyway, so the write still lands last
 * and correct. It is a microtask of deferral that buys nothing, which is why
 * this does not do it — not a trap.
 *
 * The cancelled fetch cannot write afterwards either: its promise rejects with
 * a `CancelledError` carrying `revert`, and that branch returns the current
 * data instead of reaching `setData`.
 */
export function setHeadcount(
  queryClient: QueryClient,
  weddingId: number,
  headcount: Headcount,
): void {
  const queryKey = headcountQueryKey(weddingId)
  // Not awaited: everything this has to do to the cache is already done when it
  // returns. The promise is only the cancelled fetch settling.
  void queryClient.cancelQueries({ queryKey })
  queryClient.setQueryData(queryKey, headcount)
}
