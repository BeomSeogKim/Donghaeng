package com.donghaeng.auth.account

/**
 * The signed-in person: the id, and a display name that may be absent.
 *
 * **No `email`, and its absence is a decision** (founder, 2026-08-12). No v1
 * screen shows the couple their own address, so publishing it would be a seam
 * commitment with no requirement behind it — and `web/` generates types from this
 * shape, which makes an unused field expensive to withdraw later.
 *
 * This says nothing about what we STORE. `app_user.email` and the verified-email
 * merge key are untouched (notes/2026-08-11-decision-baseline-schema-calls.md §A);
 * this is only what the endpoint publishes.
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
    val name: String?,
)

/** Entities never serialize directly, and the mapping lives in the domain package. */
internal fun AppUser.toMeResponse() = MeResponse(id = id, name = name)
