import { useQuery } from '@tanstack/react-query'
import { apiError, apiFetch } from '../lib/api'
import type { paths } from '../lib/api-types.gen'

// Reached through `paths[...]` rather than through the schema name, so the path
// and the status code are checked too: a `/auth/me` that moves, or a 200 that
// stops carrying a body, fails here rather than one screen later.
//
// THE `*/*` KEY IS THE DOCUMENT AS SPRINGDOC WRITES IT. No handler declares
// `produces`, so every response body is keyed by the wildcard even though the
// server always sends `application/json` (docs/api-spec.md § The generated
// OpenAPI document). Consume it as written — correcting it is `#66`, and it is
// a backend change either way.

/** Who is signed in, as the API declares it. */
export type Session = paths['/auth/me']['get']['responses'][200]['content']['*/*']

/** The one cache entry that answers "am I logged in?". */
export const sessionQueryKey = ['session'] as const

/**
 * `GET /auth/me`, the first call on every page load.
 *
 * `data` is the signed-in person, or `null` for signed out. A 401 is not an
 * error and must not be reported as one — it is the ordinary answer for someone
 * who has not logged in, or whose session expired, was revoked, or was not
 * recognised. Every other failure does throw, because "the server is down" and
 * "you are signed out" are different answers and showing the second for the
 * first would be a lie the couple acts on.
 */
export function useSession() {
  return useQuery({ queryKey: sessionQueryKey, queryFn: fetchSession })
}

async function fetchSession(): Promise<Session | null> {
  const response = await apiFetch('/auth/me')
  if (response.status === 401) return null
  if (!response.ok) throw await apiError(response)

  // Still a cast, and it always will be: generated types are compile-time only,
  // so nothing here has checked that the body matches. What #39 changed is what
  // it is cast TO — a shape the backend emits rather than one copied by hand.
  // Validating the body at runtime is a separate decision nobody has made.
  return (await response.json()) as Session
}
