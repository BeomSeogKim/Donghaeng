package com.donghaeng.auth

/**
 * The signed-in person. `email` and `name` are whatever the provider told us and
 * may both be absent — `email` is only ever present when a provider asserted it as
 * verified (notes/2026-08-11-decision-baseline-schema-calls.md §A).
 *
 * Public, like the controller that returns it: it is the wire shape `web/`
 * generates a TypeScript type from, so it is part of the cross-tree contract
 * rather than an internal of this package.
 *
 * **It lives here rather than beside the controller, and the service returns it
 * rather than an entity** (api/AGENTS.md, API conventions). Declaring it inside
 * `AuthController.kt` pointed the dependency the wrong way — the service layer
 * naming a type owned by the controller — and this package is the template
 * fifteen later ones copy.
 */
data class MeResponse(
    val id: Long,
    val email: String?,
    val name: String?,
)

/** Entities never serialize directly, and the mapping lives in the domain package. */
internal fun AppUser.toMeResponse() = MeResponse(id = id, email = email, name = name)
