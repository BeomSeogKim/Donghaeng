import { invitePath } from './routes'

/*
 * The invite token's whole life inside the browser, in one file.
 *
 * IT IS BEARER AUTHORITY WITH A ONE-DAY LIFE — whoever holds a live one enters
 * the ledger and reads every 하객's contact (docs/api-spec.md
 * § POST /weddings/{weddingId}/invite). So the rules are narrow and they are
 * all here rather than at three call sites: it travels in a URL FRAGMENT, which
 * is the only part of a URL never sent to any server; it rests in
 * `sessionStorage` and nowhere else, for exactly as long as the Google round
 * trip needs; and it reaches us only in the body of the accept POST.
 *
 * THE FRAGMENT IS WHAT MAKES A `returnTo` UNNECESSARY, and refusing to build
 * one is the same decision rather than a separate convenience: a `returnTo`
 * would be a second place a token-bearing URL could be logged
 * (notes/2026-08-22-decision-the-invite-link.md §3).
 *
 * NOTHING GUARDS `sessionStorage` AGAINST BEING UNAVAILABLE, and that is a
 * decision. A browser that refuses storage refuses the session cookie too — it
 * is cross-site — so there is no working path left to protect, and a `try` here
 * would only make an unusable app look like a broken invite.
 */

/** Where the token waits out the Google round trip. Named for the test that reads it. */
export const INVITE_STORAGE_KEY = 'donghaeng.invite'

/**
 * The link a couple sends on KakaoTalk.
 *
 * The API does not build this and must not: it does not know the frontend's
 * origin, and the token belongs in the fragment (docs/api-spec.md
 * § POST /weddings/{weddingId}/invite). `URLSearchParams` does the encoding in
 * both directions, so the two ends cannot disagree about what a `+` means.
 */
export function inviteLink(token: string): string {
  return `${window.location.origin}${invitePath}#${new URLSearchParams({ t: token })}`
}

/** The token carried by a URL fragment, or `null` when it carries none. */
export function readInviteToken(hash: string): string | null {
  const token = new URLSearchParams(hash.replace(/^#/, '')).get('t')
  return token === null || token === '' ? null : token
}

export function rememberInvite(token: string): void {
  sessionStorage.setItem(INVITE_STORAGE_KEY, token)
}

/**
 * The token this tab is holding for an accept that has not happened yet.
 *
 * IT IS READ BEFORE THE EMPTY-LIST GUARD, and that is the reason it is
 * reachable from outside the accept screen. A partner who has not accepted yet
 * *is* an empty `GET /weddings`, and 웨딩 만들기 is where an empty list sends
 * people — creating there closes their partner's ledger to them permanently
 * (notes/2026-08-22-decision-the-invite-link.md §3).
 */
export function pendingInvite(): string | null {
  return sessionStorage.getItem(INVITE_STORAGE_KEY)
}

/**
 * Drop it — spent, or refused in a way that can never become a yes.
 *
 * A TOKEN THAT CAN NEVER WORK AGAIN MUST NOT SURVIVE, because while it is here
 * it diverts every empty ledger this person opens into a screen that can only
 * refuse them again.
 *
 * SIGNING OUT IS NOT ONE OF THOSE CASES, and that is a decision rather than an
 * omission: "I used the wrong Google account" is a real way to arrive at an
 * invite, and its recovery is to sign out and sign back in — which only works
 * if the token is still here when they do. It is tab-scoped, and accepting is a
 * form somebody has to fill in, so the alternative buys little.
 */
export function forgetInvite(): void {
  sessionStorage.removeItem(INVITE_STORAGE_KEY)
}
