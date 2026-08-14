import { HttpResponse, http } from 'msw'
import { expect, it } from 'vitest'
import { server } from '../test/server'
import { apiFetch } from './api'

/*
 * The content-type rule, asserted on the wrapper rather than on each caller —
 * because a caller that has to remember is precisely what broke sign-out
 * (docs/api-spec.md § Every POST, PUT and PATCH must send
 * `Content-Type: application/json`).
 */

const API = 'http://localhost:8080'

/** Records what reached the network for one method, and answers 204. */
function record(method: 'get' | 'post' | 'put' | 'patch' | 'delete') {
  const seen: Request[] = []
  server.use(
    http[method](`${API}/probe`, ({ request }) => {
      seen.push(request)
      return new HttpResponse(null, { status: 204 })
    }),
  )
  return seen
}

it.each(['post', 'put', 'patch', 'delete'] as const)(
  'sends Content-Type: application/json on a %s, so a future mutation cannot forget it',
  async (method) => {
    const seen = record(method)

    await apiFetch('/probe', { method: method.toUpperCase() })

    expect(seen[0]?.headers.get('Content-Type')).toBe('application/json')
  },
)

it('sends no Content-Type on a GET, which carries no body to describe', async () => {
  const seen = record('get')

  await apiFetch('/probe')

  expect(seen[0]?.headers.get('Content-Type')).toBeNull()
})
