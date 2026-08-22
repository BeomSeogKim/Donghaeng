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
 * about the token and not about a screen — and **it is decided by `code`, never
 * by status.** Three codes end a token's life: it was unknown, it was stale, or
 * the seat it pointed at is filled. Nothing else does.
 *
 * `ALREADY_IN_A_WEDDING` IS THE 409 THAT KEEPS ITS TOKEN, and the spec says so
 * in as many words: "**The token is not spent**, so the real partner can still
 * use it." Dropping it looked like tidiness and was the opposite — this is
 * exactly what somebody who signed in with the wrong Google account is told,
 * and nothing on the accept screen names which account that was, so **the 409
 * is the only signal they get.** Their recovery is 로그아웃 → sign back in as
 * the right person → this same screen with the invite still waiting, and that
 * only works if the token is still here. Wiping it lands them on an empty
 * `GET /weddings` with nothing pending, which is 웨딩 만들기 — the one screen an
 * invited partner may never fill in (`#158`).
 *
 * A `code` OF `null` KEEPS IT TOO, and that is the same rule rather than a
 * second one. `apiError` reports `null` for a 4xx that is not a problem
 * document — a proxy or the servlet container answered, not the application
 * (`lib/api.ts`) — so it is not an answer about the token at all, and a 404
 * from a load balancer must not destroy a live invite.
 *
 * A 400 KEEPS IT for the reason the spec gives: the token is NOT spent by a
 * refused name, so correcting it and tapping again works.
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
/**
 * The three answers that mean this token can never work again — read off `code`,
 * which is the only member of a problem document anything may branch on.
 *
 * `INVITE_NOT_FOUND` already covers unknown, wrong, spent and replaced-by-a-
 * 재발급 as one answer; `INVITE_EXPIRED` is the day-old link; and
 * `PARTNER_ALREADY_JOINED` is the seat being filled by somebody else. Every
 * other refusal leaves a token that may still be good.
 */
const SPENT: ReadonlySet<string | null> = new Set([
  'INVITE_NOT_FOUND',
  'INVITE_EXPIRED',
  'PARTNER_ALREADY_JOINED',
])

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
      if (error instanceof ApiError && SPENT.has(error.code)) forgetInvite()
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: weddingsQueryKey, exact: true })
    },
  })
}
