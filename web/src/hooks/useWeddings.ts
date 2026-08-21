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
