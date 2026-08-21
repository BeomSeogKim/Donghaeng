package com.donghaeng.guest

/**
 * The value set of the ledger's 참석 상태 filter — **a filter, not the guest's stored
 * attendance**, which is two columns and not one word. `docs/api-spec.md` carries
 * what `web/` needs; the decision behind it is
 * `notes/2026-08-20-decision-the-ledger-read-and-its-filters.md` §3.
 *
 * It selects on `coalesce(confirmed_attending, expected_attending)`, and **`#151`
 * answered the open question this used to carry**: 인원수 gates on attendance first
 * and reads it through this very expression
 * ([GuestRepository.sumAttendingPartySize],
 * notes/2026-08-21-decision-the-headcount-endpoint.md §1). 원장과 인원수는 한 화면이라
 * the chip and the number may not disagree, so the two are one expression and move
 * together or not at all.
 *
 * A named value set rather than a `Boolean` parameter, because `attendance=false`
 * invites being read as "no answer yet", and a blank confirmed slot means UNKNOWN,
 * never 불참.
 *
 * **There is no `UNKNOWN` member, and its absence is a decision** rather than a gap
 * waiting to be filled: `expected_attending` is NOT NULL, so the value this filters
 * on is never unknown. 참석 여부는 참석·불참 둘뿐이고 미확인 하객이라는 것은 v1에
 * 없다 (notes/2026-08-21-decision-attendance-is-two-states.md) — if that ever
 * reverses, 미확인 is a second, overlapping axis and arrives as its own parameter,
 * never as a third value here.
 */
enum class AttendanceFilter(
    internal val attending: Boolean,
) {
    ATTENDING(true),
    NOT_ATTENDING(false),
}
