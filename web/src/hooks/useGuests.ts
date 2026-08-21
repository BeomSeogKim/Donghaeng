import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { apiError, apiFetch } from '../lib/api'
import type { paths } from '../lib/api-types.gen'
import { weddingsQueryKey } from './useWeddings'

// Reached through `paths[...]` rather than through the schema name, so the path
// and the status code are checked too. Response bodies are keyed `*/*` because
// no handler declares `produces` (docs/api-spec.md § The generated OpenAPI
// document) — consume it as written; correcting it is `#66`.

type ListGuests = paths['/weddings/{weddingId}/guests']['get']

/** One row of the ledger — the same shape every guest mutation returns. */
export type Guest = ListGuests['responses'][200]['content']['*/*'][number]

/**
 * The seven aggregation groups, in the API's spelling, and what each is called
 * on screen. There is no eighth — an eighth value is a 400.
 *
 * IT LIVES BESIDE THE TYPE IT LABELS, not in the row that first rendered it:
 * 원장 reads these and 하객 추가 writes them, and a second copy of seven Korean
 * spellings is how one screen starts calling `PARENTS_GUEST` something the
 * other does not. `satisfies` is what makes that checkable — a group added to
 * the API fails this file rather than rendering blank.
 *
 * THE ORDER IS THE API'S, and the select offers them in it. 기타 is last
 * because it is the residual: it honestly means "not stated yet", which is what
 * lets it be a default at all (docs/api-spec.md § POST /weddings/{weddingId}/guests).
 */
export const GROUP_LABELS = {
  FAMILY: '가족',
  RELATIVE: '친척',
  COUSIN: '사촌',
  PARENTS_GUEST: '혼주 손님',
  FRIEND: '친구',
  COWORKER: '직장동료',
  OTHER: '기타',
} as const satisfies Record<Guest['groupCategory'], string>

/**
 * The two filters, exactly as the endpoint declares them.
 *
 * IT IS AT MOST ONE VALUE PER AXIS, AND THAT IS LOAD-BEARING RATHER THAN
 * INCIDENTAL. `?side=GROOM&side=BRIDE` is a 400 — the endpoint refuses a
 * repeated parameter deliberately, because a repeat binds to its first value and
 * would otherwise answer 신랑측 only, with a 200 and no sign that half the ledger
 * is missing (docs/api-spec.md § GET /weddings/{weddingId}/guests). Holding one
 * optional value per axis makes "both chips pressed" a state that cannot be
 * expressed, so the refused request is unreachable rather than merely avoided.
 *
 * "Both" is spelled by omitting the parameter — which here is `undefined`.
 */
export type GuestFilters = NonNullable<ListGuests['parameters']['query']>

/**
 * The whole ledger of one wedding, whatever it is filtered by.
 *
 * `#135` and every guest mutation after it invalidate THIS key rather than one
 * filter combination of it: a guest added while the 신랑측 chip is pressed must
 * not leave the unfiltered list stale behind it
 * (notes/2026-08-21-decision-query-defaults-and-mutation-ordering.md).
 *
 * It nests under `weddingsQueryKey` because a ledger belongs to a wedding, and
 * because signing out removes everything that is not the session by prefix.
 */
export function ledgerQueryKey(weddingId: number) {
  return [...weddingsQueryKey, weddingId, 'guests'] as const
}

/** One filter combination of one wedding's ledger — what is actually fetched. */
export function guestsQueryKey(weddingId: number, filters: GuestFilters) {
  return [...ledgerQueryKey(weddingId), filters] as const
}

/**
 * The path and query string for one filter state.
 *
 * `URLSearchParams.set` is chosen over `append` for the same reason the type
 * above holds one value per axis: `set` cannot produce a second copy of a
 * parameter even if a caller asks it twice.
 */
export function guestsPath(weddingId: number, filters: GuestFilters): string {
  const query = new URLSearchParams()
  if (filters.side !== undefined) query.set('side', filters.side)
  if (filters.attendance !== undefined) query.set('attendance', filters.attendance)
  const search = query.toString()
  return `/weddings/${weddingId}/guests${search === '' ? '' : `?${search}`}`
}

/**
 * `GET /weddings/{weddingId}/guests` — 원장 itself.
 *
 * IT DOES NOT PAGINATE AND WILL NOT FOR v1. The response is the wedding's whole
 * live ledger in entry order, so there is no cursor to hold and no page to merge
 * a mutated row back into (docs/api-spec.md).
 *
 * THE ORDER ON SCREEN IS THE CLIENT'S, AND IT IS 이름 가나다순 — founder's call,
 * 2026-08-21 (notes/2026-08-21-decision-ledger-screen.md). Entry order is the
 * API's contract and stays what the cache holds; the sort is applied in `select`,
 * on the way to the screen.
 *
 * `keepPreviousData` keeps the rows on screen while a chip's request is in
 * flight. The list is not the headcount — a list that lags a tap by 100ms is an
 * instrument that does not flicker, whereas blanking it on every filter tap is
 * the screen changing its mind in front of the couple.
 */
export function useGuests(weddingId: number, filters: GuestFilters) {
  return useQuery({
    queryKey: guestsQueryKey(weddingId, filters),
    queryFn: () => fetchGuests(weddingId, filters),
    select: byName,
    placeholderData: keepPreviousData,
  })
}

async function fetchGuests(
  weddingId: number,
  filters: GuestFilters,
): Promise<readonly Guest[]> {
  const response = await apiFetch(guestsPath(weddingId, filters))
  if (!response.ok) throw await apiError(response)

  // Still a cast, as everywhere: generated types are compile-time only, so
  // nothing here has checked that the body matches what was declared.
  return (await response.json()) as readonly Guest[]
}

/**
 * 가나다순, by the browser's own Korean collation.
 *
 * Sorting Hangul by code point is not 가나다순 the moment a name is anything but
 * plain syllables, and the server deliberately did not sort: it would have to
 * commit to a collation whose behaviour differs between a laptop's Postgres and
 * a managed one. The client holds every row, so this costs one pass.
 *
 * Two guests may share a name — direct entry allows it on purpose — and `sort`
 * is stable, so a tie keeps the API's entry order underneath. The copy is not
 * optional: the array belongs to the query cache and must never be mutated.
 */
const collator = new Intl.Collator('ko')

function byName(guests: readonly Guest[]): readonly Guest[] {
  return [...guests].sort((a, b) => collator.compare(a.name, b.name))
}
