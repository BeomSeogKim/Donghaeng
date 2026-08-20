package com.donghaeng.guest

/**
 * The value set of the ledger's 참석 상태 filter — **a filter, not the guest's stored
 * attendance**, which is two columns and not one word.
 *
 * What it selects on is the value the headcount sums: the confirmed answer when
 * there is one, and the couple's expected value otherwise
 * (notes/2026-08-05-design-meal-headcount.md §1). That is the whole reason it is not
 * a `Boolean` query parameter — `attendance=false` invites being read as "no answer
 * yet", and a blank confirmed slot means UNKNOWN, never 불참.
 *
 * **There is no `UNKNOWN` member, and its absence is a decision** rather than a gap
 * waiting to be filled: `expected_attending` is NOT NULL, so the value this filters
 * on is never unknown. 아직 모르는 N명 — the guests with no confirmed answer — is a
 * second, overlapping axis (a 참석 guest can also be 미확인), so it can never be a
 * third value here; it would arrive as its own parameter. `docs/api-spec.md` says so
 * to `web/`.
 */
enum class AttendanceFilter(
    internal val attending: Boolean,
) {
    ATTENDING(true),
    NOT_ATTENDING(false),
}
