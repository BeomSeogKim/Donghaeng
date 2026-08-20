package com.donghaeng.guest

/**
 * The value set of the ledger's 참석 상태 filter — **a filter, not the guest's stored
 * attendance**, which is two columns and not one word. `docs/api-spec.md` carries
 * what `web/` needs; the decision behind it is
 * `notes/2026-08-20-decision-the-ledger-read-and-its-filters.md` §3.
 *
 * It selects on `coalesce(confirmed_attending, expected_attending)`, and **that is
 * a constraint `#17` has still to meet, not a fact already true of the headcount**:
 * what the aggregation reads first is undecided and is the founder's
 * (`V1__baseline_schema.sql`, `guest`). What binds is that 원장과 인원수는 한
 * 화면이라 the chip and the number may not disagree — so whatever `#17` gates on
 * attendance with has to be this same per-guest fallback.
 *
 * A named value set rather than a `Boolean` parameter, because `attendance=false`
 * invites being read as "no answer yet", and a blank confirmed slot means UNKNOWN,
 * never 불참.
 *
 * **There is no `UNKNOWN` member, and its absence is a decision** rather than a gap
 * waiting to be filled: `expected_attending` is NOT NULL, so the value this filters
 * on is never unknown. 아직 모르는 N명 is a second, overlapping axis (a 참석 guest can
 * also be 미확인), so it can never be a third value here; it would arrive as its own
 * parameter.
 */
enum class AttendanceFilter(
    internal val attending: Boolean,
) {
    ATTENDING(true),
    NOT_ATTENDING(false),
}
