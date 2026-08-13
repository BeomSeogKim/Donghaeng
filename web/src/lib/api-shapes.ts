/*
 * ⚠️ STAND-IN FOR GENERATED TYPES. Delete this file when #39 lands.
 *
 * The rule is that no request or response type is hand-written: springdoc emits
 * the OpenAPI document and `web/` generates its types from it, so a field the
 * backend renames breaks the frontend build instead of leaving green tests
 * mocking a shape the API stopped returning
 * (notes/2026-08-08-decision-build-workflow.md, "the seam is type-checked").
 *
 * That generation is issue #39 and is not wired yet — neither the `api/` side
 * that writes `build/openapi.json` nor the `web/` side that reads it. This file
 * is the whole of the gap: one shape, copied from docs/api-spec.md, kept in a
 * file named after the problem so it is greppable and deletable in one move.
 * Nothing else in `web/` may declare an API shape.
 */

/** `GET /auth/me` — who is signed in. */
export type Session = {
  readonly id: number
  /** Nullable: a provider may return no name. Render a fallback. */
  readonly name: string | null
}
