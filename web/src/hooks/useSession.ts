import { useQuery } from '@tanstack/react-query'
import { apiError, apiFetch } from '../lib/api'
import type { Session } from '../lib/api-shapes'

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

  // The one unchecked cast in the app, and it disappears with #39 — see
  // lib/api-shapes.ts.
  return (await response.json()) as Session
}
