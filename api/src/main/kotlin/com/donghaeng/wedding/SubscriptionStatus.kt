package com.donghaeng.wedding

/**
 * How the live term is doing, which is a different question from what it grants
 * ([SubscriptionPlan]).
 *
 * **Only `ACTIVE` is ever written until payment exists**; the other two are here
 * because `V3` names them and a row carrying one must be readable rather than a
 * startup-time surprise. Nothing in this tree branches on them — the gate that would
 * is `#169`'s, and it cannot be written before the boundary it enforces is decided.
 *
 * A `varchar(20)` for the reason `group_category` is one (api/AGENTS.md, Domain
 * mechanisms): whichever PSP is eventually chosen brings its own vocabulary of
 * failure, and that must be a deploy and not an `ALTER TYPE`.
 */
internal enum class SubscriptionStatus {
    ACTIVE,
    PAST_DUE,
    CANCELED,
}
