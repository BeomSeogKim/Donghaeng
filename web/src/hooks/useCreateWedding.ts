import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiError, apiFetch } from '../lib/api'
import type { paths } from '../lib/api-types.gen'
import { sessionQueryKey } from './useSession'
import { weddingsQueryKey } from './useWeddings'

// Reached through `paths[...]` rather than through the schema name, so the path
// and the status code are checked too. Response bodies are keyed `*/*` because
// no handler declares `produces` (docs/api-spec.md § The generated OpenAPI
// document) — consume it as written; correcting it is `#66`.

/** 날짜와 두 사람 이름. There is no fourth member and there cannot be one. */
export type CreateWeddingRequest =
  paths['/weddings']['post']['requestBody']['content']['application/json']

/** The wedding as stored — which is not always what was sent; the names are trimmed. */
export type Wedding = paths['/weddings']['post']['responses'][201]['content']['*/*']

/**
 * `POST /weddings` — the wedding and the caller's membership in it, in one
 * transaction. The one endpoint in the product that is not scoped to a wedding.
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
 * order is contract (docs/api-spec.md § GET /weddings).
 *
 * A 401 is the one failure with no error UI: it means "log in again", never
 * "something went wrong". Writing the session to null puts the login screen up
 * immediately rather than after a round trip that can only say the same thing.
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
      queryClient.setQueryData(
        weddingsQueryKey,
        (weddings: readonly Wedding[] | undefined) => [wedding, ...(weddings ?? [])],
      )
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 401)
        queryClient.setQueryData(sessionQueryKey, null)
    },
  })
}
