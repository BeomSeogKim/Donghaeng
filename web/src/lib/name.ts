/*
 * 사람 이름 하나에 대한 규칙, and there is exactly one of it.
 *
 * TWO SCREENS WRITE A NAME INTO A SEAT — 웨딩 만들기 and 초대 수락 — and both
 * write the CALLER's own, never their partner's. The API validates the same
 * column the same way for both, and says so in as many words: "one client-side
 * rule covers both screens" (docs/api-spec.md § POST /weddings/join). Two
 * copies of a limit is how the two screens start disagreeing about it.
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
 */
export function nameError(name: string): string | undefined {
  if (name === '') return '이름을 입력해 주세요.'
  // In UTF-16 code units — the same unit and the same value the server counts.
  if (name.length > NAME_MAX) return `이름은 ${NAME_MAX}자까지 쓸 수 있습니다.`
  return undefined
}
