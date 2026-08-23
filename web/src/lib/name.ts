/*
 * 사람 이름 하나에 대한 규칙, and there is exactly one of it.
 *
 * TWO SCREENS WRITE A NAME INTO A SEAT — 웨딩 만들기 and 초대 수락 — and both
 * write the CALLER's own, never their partner's. The API validates the same
 * column the same way for both, and says so in as many words: "one client-side
 * rule covers both screens" (docs/api-spec.md § POST /weddings/join). Two
 * copies of a limit is how the two screens start disagreeing about it.
 *
 * 결혼식 이름 IS HERE TOO, AND IT IS THE SAME COLUMN RULE. The API says so in as
 * many words — "it obeys the same name rule the seat's `name` does" — so it is
 * the same limit read by a second function rather than a second limit
 * (docs/api-spec.md § POST /weddings).
 */

/** The column's limit, and the API's. */
export const NAME_MAX = 100

/**
 * What is wrong with this name, or `undefined` when nothing is.
 *
 * IT MEASURES THE TRIMMED VALUE, because the server measures before its own
 * trim: 100 characters plus a trailing space is a 400 even though it would have
 * been stored as 100. Trimming in the client is what makes the two agree — and
 * it is a normalisation of what the person typed, not a computation.
 *
 * **THIS CATCHES THE COMMON CASE AND THE SERVER IS THE AUTHORITY.** It does not
 * reproduce the server's rule and must not try to: 보이지 않는 문자로만 된 이름은
 * 이름으로 치지 않는다 (the founder, 2026-08-22), which `#188` implements as "at
 * least one character that is neither whitespace nor Unicode category C". JS
 * `trim()` removes U+3000 and U+00A0, so those agree by luck — but U+200B and
 * U+2800 pass this check and are refused by the server with a 400, which both
 * screens already render.
 *
 * A UNICODE-BLANK WHITELIST IS DELIBERATELY NOT BUILT HERE. A client cannot
 * cheaply replicate a category test, and a half-copy of the rule is worse than
 * none: it drifts from the server silently, and the failure it produces is a
 * name this app accepted and the API refused — which is exactly the round trip
 * the 400 path exists for.
 */
export function nameError(name: string): string | undefined {
  if (name === '') return '이름을 입력해 주세요.'
  // In UTF-16 code units, which is the unit the server's own limit is in.
  if (name.length > NAME_MAX) return `이름은 ${NAME_MAX}자까지 쓸 수 있습니다.`
  return undefined
}

/**
 * The same rule for 결혼식 이름, with the one difference that matters: **empty is
 * an answer here.**
 *
 * A WEDDING WITH NO NAME IS AN ORDINARY WEDDING. The field is optional at
 * creation and stays optional in 설정 — a couple who named theirs and would
 * rather not have can empty the box — so a blank one is "none" rather than
 * something wrong, and the screens spell it by omitting the member or sending
 * `null` (docs/api-spec.md § POST /weddings, § PATCH /weddings/{weddingId}).
 *
 * EVERYTHING ELSE IS THE COLUMN'S RULE, unchanged: 100 characters measured on
 * what is sent, before the server's own trim, and the invisible-character rule
 * left to the server exactly as `nameError` leaves it. This is not a second copy
 * of that rule — it is the same one, asked of a value that may be absent.
 */
export function weddingNameError(weddingName: string): string | undefined {
  if (weddingName === '') return undefined
  if (weddingName.length > NAME_MAX)
    return `결혼식 이름은 ${NAME_MAX}자까지 쓸 수 있습니다.`
  return undefined
}
