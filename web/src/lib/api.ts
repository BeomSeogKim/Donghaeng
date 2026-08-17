/*
 * The one door to the API. Every request the app makes goes through apiFetch,
 * because two things have to be true of all of them and neither survives being
 * remembered per call site (docs/api-spec.md § Calling the API from the browser):
 *
 * 1. CREDENTIALS ARE INCLUDED. The session lives in an HttpOnly cookie on a
 *    different origin, so without `credentials: 'include'` the browser sends no
 *    cookie at all and the API answers 401 — which reads as "not logged in"
 *    rather than as a bug. It is set here and cannot be overridden by a caller;
 *    that is the whole reason this function exists rather than a bare fetch.
 *
 * 2. THE ORIGIN IS AN EXACT STRING. The server's allowed list has no wildcard,
 *    and in dev it is exactly `http://localhost:3000` — so opening the app at
 *    `http://127.0.0.1:3000` is a different origin to the browser, gets no
 *    Access-Control-Allow-Origin, and every call fails before our code sees the
 *    response. Use localhost.
 *
 * Only `Content-Type` and `Accept` may be sent as request headers; anything else
 * fails the CORS preflight before either side runs, and adding one is a backend
 * change.
 */

/**
 * Where the API lives. A build-time constant, because the API is a different
 * origin in every environment — there is no same-origin fallback to default to.
 */
export const apiBaseUrl = resolveBaseUrl()

function resolveBaseUrl(): string {
  const configured = import.meta.env.VITE_API_BASE_URL
  if (configured !== undefined && configured !== '') return configured.replace(/\/+$/, '')

  // A production bundle silently pointing at a laptop is the failure this
  // prevents: it builds, it loads, and every request dies in the browser with a
  // CORS error that says nothing about the missing variable.
  if (import.meta.env.PROD) {
    throw new Error(
      'VITE_API_BASE_URL is not set. A production build has no API to talk to; ' +
        'see web/.env.example.',
    )
  }
  return 'http://localhost:8080'
}

/** @param path an API path, leading slash included. */
export function apiUrl(path: string): string {
  return `${apiBaseUrl}${path}`
}

export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')

  // Set here, and on every method that is not a read, so no call site can
  // forget it — one that did is what broke sign-out. It is not a description of
  // a body (a request with none still needs it): a JSON content type is not
  // CORS-safelisted, and the preflight it forces is v1's CSRF gate, so the
  // API answers 415 to a state-changing request without it.
  const method = (init.method ?? 'GET').toUpperCase()
  if (method !== 'GET' && method !== 'HEAD')
    headers.set('Content-Type', 'application/json')

  return await fetch(apiUrl(path), { ...init, headers, credentials: 'include' })
}

/**
 * A failed request, reduced to the two things the client may act on: the HTTP
 * status, and `code`.
 *
 * `code` is the only member of the problem document anything branches on —
 * never `detail`, `title` or `type`. `detail` is an English diagnostic that may
 * change at any time and can quote the submitted value back, so rendering it
 * would paint attacker-supplied text onto the screen; it is deliberately not
 * kept here. Korean copy is chosen from `code`, and an unrecognised `code` is
 * handled as a generic failure of its status.
 */
export class ApiError extends Error {
  readonly status: number
  /** `null` when the response was not a problem document — see apiError. */
  readonly code: string | null

  constructor(status: number, code: string | null) {
    super(`API responded ${status}${code === null ? '' : ` (${code})`}`)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

/**
 * Read a failed response into an ApiError.
 *
 * A 4xx that is not `application/problem+json` did not come from the
 * application — the servlet container answered a request that was malformed
 * before it arrived. There is no `code` to switch on and it is a bug in the
 * caller, so it becomes an ApiError with `code: null` rather than a guess.
 */
export async function apiError(response: Response): Promise<ApiError> {
  const contentType = response.headers.get('Content-Type') ?? ''
  if (!contentType.startsWith('application/problem+json')) {
    return new ApiError(response.status, null)
  }

  const body: unknown = await response.json().catch(() => null)
  const code =
    typeof body === 'object' &&
    body !== null &&
    'code' in body &&
    typeof body.code === 'string'
      ? body.code
      : null
  return new ApiError(response.status, code)
}
