package com.donghaeng.wedding

/**
 * What a wedding's live term entitles it to.
 *
 * **One value, and that is the point** — the free/paid boundary is an open question
 * the founder parked deliberately, because deciding how many 하객 are free before a
 * single couple has filled a ledger is a guess
 * (`notes/2026-08-22-decision-entitlement-belongs-to-the-wedding.md` §7). What this
 * work buys is that the column EXISTS and hangs off the wedding; `#168` is what makes
 * it vary.
 *
 * A Kotlin enum over a `varchar(30)`, never a Postgres enum type — the value set is
 * the opposite of closed, so adding one must be a deploy rather than an `ALTER TYPE`
 * (api/AGENTS.md, Domain mechanisms). Not on the seam: no endpoint publishes it.
 */
internal enum class SubscriptionPlan {
    FREE,
}
