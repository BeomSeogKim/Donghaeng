import { type DefaultOptions, QueryClient } from '@tanstack/react-query'
import { ApiError } from './api'

/*
 * The app's single QueryClient. Server state — anything that is a client-side
 * copy of what the API owns — lives here and nowhere else
 * (notes/2026-08-08-decision-frontend-architecture.md).
 *
 * Every default below is argued from the three reads this product actually has —
 * the session probe, the couple's weddings, and the whole ledger, which does not
 * paginate — in notes/2026-08-21-decision-query-defaults-and-mutation-ordering.md.
 * The tests next door assert them as behaviour; change one and read that record.
 */
export const queryClientDefaults = {
  queries: {
    /*
     * Retry a read once, but only when nothing answered it. A 4xx is an answer:
     * the server looked and decided, and repeating the question cannot change
     * 404 WEDDING_NOT_FOUND or 400 into anything else — it only puts a second
     * round trip between the couple and the screen that says so.
     */
    retry: (failureCount: number, error: Error) =>
      !(error instanceof ApiError && error.status >= 400 && error.status < 500) &&
      failureCount < 1,

    /*
     * Stock, and kept on purpose. The alternative is knowingly showing a number
     * that may be old, which is the one thing this product may not do. It is
     * also what makes the line below mean anything: above zero, a refetch on
     * focus or on mount is skipped as fresh, and the ledger's number is a
     * refetch until #17 gives mutations an aggregate to carry.
     */
    staleTime: 0,

    /*
     * Stock, kept on purpose. Two people share one ledger by design, so the
     * partner's edit being there when you tab back is correct behaviour rather
     * than a surprise — and it is the only thing that orders this client
     * against another device.
     */
    refetchOnWindowFocus: true,
  },
  mutations: {
    /*
     * A mutation here is not idempotent, and the spec says so in as many words:
     * a second guest with the same name succeeds and is a second row. A retried
     * create writes a person into the ledger twice, and this product's whole
     * claim is that the number is never wrong. A failed mutation surfaces as a
     * failure the couple sees, never as a silent second attempt.
     */
    retry: 0,

    /*
     * THE OUT-OF-ORDER GUARD. Mutations sharing a scope id run one at a time, in
     * the order they were fired, and React Query awaits the finished one's
     * onSuccess before starting the next — so a cache write can never be
     * overtaken by an older one from this client, and the server applies the
     * taps in the order the couple made them.
     *
     * One id for the whole app, not one per wedding: a default cannot be
     * forgotten at a call site, and forgetting is silent. The concurrency it
     * gives up is concurrency one couple at a human tap rate never had. A call
     * site may still pass a narrower scope; this is the floor.
     */
    scope: { id: 'donghaeng' },
  },
} satisfies DefaultOptions

export function createQueryClient() {
  return new QueryClient({ defaultOptions: queryClientDefaults })
}
