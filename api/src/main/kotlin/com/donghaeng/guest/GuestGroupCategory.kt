package com.donghaeng.guest

/**
 * 가족 · 친척 · 사촌 · 혼주 손님 · 친구 · 직장동료 · 기타 — the seven the ledger
 * aggregates by (notes/2026-08-06-design-ledger-and-import.md §1).
 *
 * **A Kotlin enum over a `varchar(30)`, never a Postgres enum type**: the list
 * changed twice in one day, so adding a value must be a deploy and not an
 * `ALTER TYPE` (api/AGENTS.md, Domain mechanisms). This class is the whole of the
 * "application-level validation" half of that rule — it is the only thing that
 * refuses an eighth word.
 *
 * [OTHER] is the residual member, the only one that means "not stated", and so the
 * only one an omitted `groupCategory` could become ([GuestService.create]).
 */
enum class GuestGroupCategory {
    FAMILY,
    RELATIVE,
    COUSIN,
    PARENTS_GUEST,
    FRIEND,
    COWORKER,
    OTHER,
}
