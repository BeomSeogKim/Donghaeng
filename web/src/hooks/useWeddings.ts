import type { QueryClient } from '@tanstack/react-query'
import { useQuery } from '@tanstack/react-query'
import { apiError, apiFetch } from '../lib/api'
import type { paths } from '../lib/api-types.gen'

// Reached through `paths[...]` rather than through the schema name, so the path
// and the status code are checked too. Response bodies are keyed `*/*` because
// no handler declares `produces` (docs/api-spec.md § The generated OpenAPI
// document) — consume it as written; correcting it is `#66`.

/**
 * A wedding as stored: the date and the couple's two seats, and nothing else
 * yet. `seats` always holds exactly two entries, 신랑 먼저, and a seat's `name`
 * is `null` until that person arrives (docs/api-spec.md § GET /weddings).
 *
 * THIS IS THE ONLY DECLARATION OF IT. `POST /weddings` returns the same
 * `WeddingResponse` — one type for all three wedding endpoints, and the spec
 * says so in as many words (docs/api-spec.md § GET /weddings) — and the create
 * writes its result straight into this list, so a second alias would be a
 * `setQueryData` across a seam nothing checks.
 */
export type Wedding =
  paths['/weddings']['get']['responses'][200]['content']['*/*'][number]

/** The weddings this person belongs to. Also the prefix every ledger key sits under. */
export const weddingsQueryKey = ['weddings'] as const

/**
 * `GET /weddings` — the question a client has before it has a `weddingId`.
 *
 * THE LEDGER IS THE SECOND CALLER, AND THE REASON THIS EXISTS. The id from
 * `POST /weddings` does not survive a refresh, so 원장 reloads by asking this and
 * taking `[0]`: the list is ordered newest first and that order is contract, so
 * the same reload gets the same wedding every time (docs/api-spec.md
 * § GET /weddings).
 *
 * AN EMPTY ARRAY IS NOT AN ERROR. It is the ordinary answer for a person with no
 * wedding — the branch 최초 1회 exists for — and it is what sends them to
 * 웨딩 만들기 rather than to an empty ledger.
 */
export function useWeddings() {
  return useQuery({ queryKey: weddingsQueryKey, queryFn: fetchWeddings })
}

async function fetchWeddings(): Promise<readonly Wedding[]> {
  const response = await apiFetch('/weddings')
  if (!response.ok) throw await apiError(response)

  // Still a cast, as everywhere: generated types are compile-time only, so
  // nothing here has checked that the body matches what was declared.
  return (await response.json()) as readonly Wedding[]
}

/**
 * Write a wedding a mutation just returned into the list every screen reads it
 * from, and the one thing a wedding mutation must call.
 *
 * IT IS `setHeadcount`'s SIBLING AND EXISTS FOR THE SAME REASON: the key and the
 * shape are bound together and checked, so a caller cannot write another
 * wedding's row into this key by hand — and so **the cancel cannot be the thing
 * a call site forgets** (`useHeadcount.ts`). Two call sites wrote this key by
 * hand until `#174`'s review, and only one of them cancelled anything.
 *
 * A READ ALREADY IN FLIGHT IS CANCELLED FIRST. This query runs `staleTime: 0`
 * and `refetchOnWindowFocus`, so a `GET /weddings` started before the write
 * lands after it, and query-core's fetch resolution calls `setData`
 * unconditionally with no comparison of when the two answers were computed — the
 * couple would be told 저장했습니다 while 원장's header still shows the old
 * 예식일.
 *
 * WHAT `onSettled`'S INVALIDATION DOES ABOUT THAT, MEASURED RATHER THAN
 * ASSUMED: it repairs the ORDINARY case on its own, because `invalidateQueries`
 * defaults to `cancelRefetch: true` and therefore aborts the stale fetch and
 * starts a fresh one rather than deduping into it (query-core 5.101.4). So with
 * a couple still on the screen, removing this line changes nothing.
 *
 * **THE CASE IT CANNOT REPAIR IS THE ONE THIS LINE IS FOR**, and it is ordinary
 * too: `invalidateQueries` also defaults to `refetchType: 'active'`, so a couple
 * who leave the moment they press 저장 — closed the tab, back to KakaoTalk —
 * leave no active observer, and the invalidation refetches NOTHING. The stale
 * read is then the only thing still in flight and its answer is the last word.
 * That case is a test (`SettingsPage.test.tsx` § holds the date it wrote when
 * nobody is left on screen), and deleting this line is what turns it red.
 *
 * THE ORDER OF THE TWO LINES IS LOAD-BEARING, exactly as in `setHeadcount`:
 * `cancelQueries` defaults to `revert: true` and applies the revert
 * synchronously, so the write has to come after it or the stale list survives.
 *
 * `exact` IS REQUIRED, NOT TIDINESS. `['weddings']` is the PREFIX of every
 * ledger and headcount key, so an inexact cancel would abort the ledger read and
 * the number's read beside it.
 *
 * IT UPSERTS, NEWEST FIRST. A created wedding is prepended — the list's order is
 * contract and a new wedding is the newest — and an updated one is replaced
 * where it already sits, which is what makes one function serve both call sites.
 */
export function setWedding(queryClient: QueryClient, wedding: Wedding): void {
  const queryKey = weddingsQueryKey
  // Not awaited: everything this has to do to the cache is already done when it
  // returns. The promise is only the cancelled fetch settling.
  void queryClient.cancelQueries({ queryKey, exact: true })
  queryClient.setQueryData(queryKey, (weddings: readonly Wedding[] | undefined) => {
    if (weddings === undefined) return [wedding]
    return weddings.some((held) => held.id === wedding.id)
      ? weddings.map((held) => (held.id === wedding.id ? wedding : held))
      : [wedding, ...weddings]
  })
}
