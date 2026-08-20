package com.donghaeng.wedding

/**
 * 신랑측 or 신부측 — **the one value set in this schema that is a Postgres `enum`
 * type**, because a Korean wedding has exactly two sides and always will
 * (`V1__baseline_schema.sql`). Everything else with a fixed list is a varchar
 * (api/AGENTS.md, Domain mechanisms).
 *
 * It lives in `wedding/` and is public as a **cross-domain contract**: it is the
 * wedding's axis rather than the guest's, and seating will read the same two words.
 * `web/` generates a TypeScript union from it, so these names are wire values.
 */
enum class WeddingSide {
    GROOM,
    BRIDE,
}
