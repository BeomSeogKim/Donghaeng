import type { QueryClient } from '@tanstack/react-query'
import { weddingsQueryKey } from '../hooks/useWeddings'
import { ApiError } from './api'

/*
 * 409 `ALREADY_IN_A_WEDDING`, in one file, because TWO SCREENS ARE TOLD IT and
 * two screens saying one thing differently is how the next reader learns the
 * wrong half (notes/2026-08-22-decision-the-409-recovery-loop.md).
 *
 * 웨딩 만들기 gets it when two tabs submit and one loses the race; 초대 수락 gets
 * it when the person accepting already has a ledger of their own. The API
 * raises both **from one check**, so the two can never disagree about the fact,
 * and this is what stops the client disagreeing about the answer
 * (docs/api-spec.md § POST /weddings, § POST /weddings/join).
 *
 * THE RECOVERY IS NEITHER A RETRY NOR AN ERROR SCREEN: call `GET /weddings` and
 * open the one that comes back. To the couple that is not a failure at all —
 * "이미 있으니 그걸 열었다". Both screens already redirect a person who holds a
 * wedding to 원장, so refetching that list is the whole of it: the redirect they
 * already have is the recovery.
 *
 * AND THE VERDICT IS REMEMBERED, which is the other half and the reason this is
 * a module rather than two `onError` bodies. The recovery needs
 * `GET /weddings` to AGREE, and when it does not — a replica read, a late cache
 * — the couple walks a circle: 원장 sees an empty list, decides they are a
 * partner who has not accepted yet, and hands them back to the screen that just
 * refused them (`LedgerPage.tsx`, `#158`). **Neither half of that circle may be
 * reverted**; the screen speaks instead, and this is what it reads.
 *
 * REMEMBERING IT IS HONEST BECAUSE IT IS SETTLED FOR THIS PERSON. Pressing
 * 수락 or 만들기 again cannot put anybody in two weddings, so there is no answer
 * being withheld. It records what happened to **this caller** and never that
 * the invite is dead — which is why nothing here touches the token
 * (`lib/invite.ts` owns that, and the spec keeps this 409's token alive on
 * purpose).
 *
 * IT IS A MODULE VARIABLE AND NOT WEB STORAGE, and that is the point rather
 * than an economy. `sessionStorage` would outlive the person: it survives a
 * reload, and the one recovery this must never block is "I signed in with the
 * wrong Google account" — sign out, sign back in as the right person, get a
 * form that works. So it dies where the session does (`useLogout`), and a tab
 * that reloads starts with no verdict and simply earns a fresh one if the
 * server still says so.
 */

/** Told to this caller, in this tab, since the last time a session ended. */
let told = false

/**
 * The app's one answer to this 409 — called from every mutation that can be
 * refused with it, and doing nothing at all for every other failure.
 *
 * The invalidation is the spec's recovery. It is `exact` because `['weddings']`
 * is the prefix of every ledger and headcount key, and it is not awaited:
 * query-core waits on whatever a mutation callback returns before releasing the
 * next mutation.
 */
export function answerAlreadyInAWedding(queryClient: QueryClient, error: unknown): void {
  // `code` is the only member of a problem document anything branches on, and
  // it is enough here: this code is the same fact about the caller's account
  // whichever endpoint reports it.
  if (!(error instanceof ApiError) || error.code !== 'ALREADY_IN_A_WEDDING') return

  told = true
  void queryClient.invalidateQueries({ queryKey: weddingsQueryKey, exact: true })
}

/** Whether this person has been told, and therefore what the screen must say. */
export function wasAlreadyInAWedding(): boolean {
  return told
}

/**
 * Forget it — called where the session is cleared, and nowhere else.
 *
 * The next person to sign in on this tab must be handed a form they can use,
 * and on a phone the couple share that is the ordinary case rather than the
 * exotic one.
 */
export function forgetAlreadyInAWedding(): void {
  told = false
}
