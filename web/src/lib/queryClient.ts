import { QueryClient } from '@tanstack/react-query'

/*
 * The app's single QueryClient. Server state — anything that is a client-side
 * copy of what the API owns — lives here and nowhere else
 * (notes/2026-08-08-decision-frontend-architecture.md).
 *
 * Defaults, and why each is not the stock value:
 *
 * - mutations retry: 0. A mutation here is not idempotent — a retried guest
 *   create double-writes a person into the ledger. This product's claim is
 *   never-wrong numbers, so a failed mutation surfaces as a failure the couple
 *   sees, never as a silent second attempt.
 * - queries retry: 1. A read is safe to repeat, and one retry covers a dropped
 *   connection without making a genuinely broken screen take four round trips
 *   to say so.
 * - refetchOnWindowFocus: true (the stock value, kept deliberately). Two people
 *   share one ledger by design, so the partner's edit appearing when you tab
 *   back is correct behaviour, not a surprise.
 * - staleTime: 0 (stock, kept deliberately). The alternative is showing a
 *   number we already know might be old, which is the one thing this product
 *   may not do. Traffic is one couple, so refetching costs nothing worth
 *   trading a stale headcount for.
 */
export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: 1, staleTime: 0, refetchOnWindowFocus: true },
      mutations: { retry: 0 },
    },
  })
}

/*
 * Out-of-order responses — the guard, decided here so the first mutation stop
 * inherits a decision rather than a blank (AGENTS.md: a number lagging the tap
 * by 100ms is fine, a number moving backwards is not).
 *
 * The hazard is specific: every mutation response carries the recomputed
 * aggregate, and a mutation's onSuccess writes that aggregate straight into the
 * query cache. Tap 참석 then tap 불참 quickly, and if the first response lands
 * second, the cache is overwritten with the older headcount.
 *
 * The guard, in preference order:
 *
 * 1. If the aggregate carries a server-side monotonic marker — a version, a
 *    sequence, or a strictly-increasing updatedAt — onSuccess compares against
 *    the marker already in the cache and drops the older payload. This is the
 *    one that also covers the partner mutating concurrently from another
 *    device, because the ordering comes from the server rather than from this
 *    client's own timeline. It is what to ask the backend for.
 * 2. If no such marker exists, a client-side monotonic counter: each mutation
 *    captures a sequence number when it fires, and onSuccess writes only if its
 *    number is the highest that has settled so far. Needs nothing from the API
 *    and fixes the double-tap case, but cannot order this client against the
 *    partner's.
 * 3. Either way, onSettled invalidates the query as a backstop, so the last
 *    word always comes from a fresh read rather than from a race.
 *
 * Which of 1 or 2 applies cannot be settled here: docs/api-spec.md has no
 * endpoint yet, so the aggregate's shape is unknown, and inventing a version
 * field the API does not return would be exactly the client-side guess the
 * spec rule forbids. #39 (generated types) will reveal which; #43 implements
 * whichever it turns out to be.
 */
