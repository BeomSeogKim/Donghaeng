import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiError, apiFetch } from '../lib/api'
import type { paths } from '../lib/api-types.gen'
import { forgetInvite } from '../lib/invite'
import { setWedding, type Wedding, weddingsQueryKey } from './useWeddings'

// Reached through `paths[...]` rather than through the schema name, so the path
// and the status code are checked too. Response bodies are keyed `*/*` because
// no handler declares `produces` (docs/api-spec.md § The generated OpenAPI
// document) — consume it as written; correcting it is `#66`.

/**
 * 토큰과 내 이름, and there is no third member.
 *
 * `name` IS THE ACCEPTING PERSON'S OWN — nobody types anybody else's name,
 * which is the same rule that took the partner's name out of `POST /weddings`.
 * There is no `side` either: the seat already exists and the token identifies
 * it (docs/api-spec.md § POST /weddings/join).
 */
export type JoinWeddingRequest =
  paths['/weddings/join']['post']['requestBody']['content']['application/json']

// `Wedding` IS NOT DECLARED HERE, for the reason `useCreateWedding` gives: this
// endpoint returns the same `WeddingResponse` the three wedding endpoints
// return, and this hook writes its result into the list `useWeddings` reads.

/**
 * `POST /weddings/join` — 초대 수락, and the third endpoint in the product that
 * is not scoped to a wedding. It could not be: the caller holds no seat yet,
 * which is what the request is *for*, and the token stands in for the scope.
 *
 * NO AGGREGATE IS CARRIED, and that is stated rather than defaulted: joining
 * changes no 하객 and no 인원수, so there is no number to write
 * (notes/2026-08-22-decision-the-partner-invite.md §6).
 *
 * THE WEDDING IS WRITTEN INTO `GET /weddings` BECAUSE THAT LIST IS WHERE EVERY
 * SCREEN READS IT FROM, and here it is load-bearing rather than a nicety: the
 * next screen is 원장, and the cached list is one request old and says this
 * person has NO wedding — which is the state that sends them to 웨딩 만들기,
 * the one screen an invited partner may never reach (`#158`). The response is
 * the wedding they just joined, so the client never has to ask who they are.
 *
 * THE TOKEN IS DROPPED HERE RATHER THAN ON THE SCREEN, because it is a fact
 * about the token and not about a screen. A spent one can never work again; and
 * on this endpoint **every 404 and every 409 is a settled answer** — unknown,
 * expired, spent, replaced, the seat taken, or the caller already seated —
 * where pressing again cannot change the outcome and only the words differ. A
 * 400 is the opposite and the spec says so: the token is NOT spent by a refused
 * name, so correcting it and tapping again works.
 *
 * A 401 IS NOT HANDLED HERE either way. It means "log in again" wherever it
 * comes from, the client answers it once for the whole app
 * (`lib/queryClient.ts`), and the token has to survive that — signing in again
 * is exactly what this flow was already doing.
 *
 * THE INVALIDATION IS THE STANDING BACKSTOP: a POST can commit and lose its
 * response, mutations are `retry: 0`, so `onSuccess` never runs and the client
 * would hold a list that says this person has no wedding while the server has
 * seated them. It is `exact` because `['weddings']` is the prefix of every
 * ledger and headcount key, and it is not awaited — query-core waits on
 * whatever `onSettled` returns before releasing the next mutation.
 */
export function useJoinWedding() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (request: JoinWeddingRequest): Promise<Wedding> => {
      const response = await apiFetch('/weddings/join', {
        method: 'POST',
        body: JSON.stringify(request),
      })
      if (!response.ok) throw await apiError(response)

      // Still a cast, as everywhere: generated types are compile-time only, so
      // nothing here has checked that the body matches what was declared.
      return (await response.json()) as Wedding
    },
    onSuccess: (wedding) => {
      forgetInvite()
      setWedding(queryClient, wedding)
    },
    onError: (error) => {
      if (error instanceof ApiError && (error.status === 404 || error.status === 409))
        forgetInvite()
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: weddingsQueryKey, exact: true })
    },
  })
}
