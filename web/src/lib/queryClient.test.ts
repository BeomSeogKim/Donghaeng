import { MutationObserver as QueryMutationObserver } from '@tanstack/react-query'
import { delay, HttpResponse, http } from 'msw'
import { expect, it } from 'vitest'
import { server } from '../test/server'
import { ApiError, apiError, apiFetch } from './api'
import type { paths } from './api-types.gen'
import { createQueryClient } from './queryClient'

/*
 * The client's defaults, asserted through behaviour rather than by reading the
 * config object back — a test that compares `retry` to `1` passes even when the
 * value means nothing (notes/2026-08-21-decision-query-defaults-and-mutation-ordering.md).
 */

const API = 'http://localhost:8080'
const LEDGER = `${API}/weddings/9/guests`

// Doubles are typed from the generated types: a mock the API outgrew must stop
// compiling rather than stay green (web/AGENTS.md).
type GuestMutation =
  paths['/weddings/{weddingId}/guests']['post']['responses'][201]['content']['*/*']
type Ledger =
  paths['/weddings/{weddingId}/guests']['get']['responses'][200]['content']['*/*']

function created(name: string): GuestMutation {
  return {
    guest: {
      id: 41,
      name,
      side: 'GROOM',
      groupCategory: 'OTHER',
      groupLabel: null,
      contact: null,
      accessibilityNote: null,
      expectedAttending: true,
      expectedPartySize: 1,
    },
  }
}

function problem(status: number, code: string) {
  return HttpResponse.json(
    { code },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

async function readLedger(): Promise<Ledger> {
  const response = await apiFetch('/weddings/9/guests')
  if (!response.ok) throw await apiError(response)
  return (await response.json()) as Ledger
}

async function addGuest(name: string): Promise<GuestMutation> {
  const response = await apiFetch('/weddings/9/guests', {
    method: 'POST',
    body: JSON.stringify({ name, side: 'GROOM' }),
  })
  if (!response.ok) throw await apiError(response)
  return (await response.json()) as GuestMutation
}

it('does not retry a query the server answered with a 4xx', async () => {
  let requests = 0
  server.use(
    http.get(LEDGER, () => {
      requests += 1
      return problem(404, 'WEDDING_NOT_FOUND')
    }),
  )

  const client = createQueryClient()
  await expect(
    client.fetchQuery({ queryKey: ['ledger'], queryFn: readLedger }),
  ).rejects.toBeInstanceOf(ApiError)

  expect(requests).toBe(1)
})

it('retries a query once when the request never reached an answer', async () => {
  let requests = 0
  server.use(
    http.get(LEDGER, () => {
      requests += 1
      return HttpResponse.error()
    }),
  )

  const client = createQueryClient()
  // retryDelay is the stock backoff and is not what this asserts; a second of
  // real waiting in the suite buys nothing.
  await expect(
    client.fetchQuery({ queryKey: ['ledger'], queryFn: readLedger, retryDelay: 0 }),
  ).rejects.toThrow()

  expect(requests).toBe(2)
})

it('refetches rather than serving what it already has', async () => {
  let requests = 0
  server.use(
    http.get(LEDGER, () => {
      requests += 1
      return HttpResponse.json([] satisfies Ledger)
    }),
  )

  const client = createQueryClient()
  await client.fetchQuery({ queryKey: ['ledger'], queryFn: readLedger })
  await client.fetchQuery({ queryKey: ['ledger'], queryFn: readLedger })

  expect(requests).toBe(2)
})

it('never retries a mutation', async () => {
  let requests = 0
  server.use(
    http.post(LEDGER, () => {
      requests += 1
      return problem(500, 'INTERNAL_ERROR')
    }),
  )

  const client = createQueryClient()
  const observer = new QueryMutationObserver(client, { mutationFn: addGuest })
  await expect(observer.mutate('김영수')).rejects.toBeInstanceOf(ApiError)

  expect(requests).toBe(1)
})

it('runs mutations one at a time, in the order they were fired', async () => {
  const reached: string[] = []
  let releaseFirst: () => void = () => {}
  const firstHeld = new Promise<void>((resolve) => {
    releaseFirst = resolve
  })

  server.use(
    http.post(LEDGER, async ({ request }) => {
      const { name } = (await request.json()) as { name: string }
      reached.push(name)
      if (name === 'first') await firstHeld
      return HttpResponse.json(created(name), { status: 201 })
    }),
  )

  const client = createQueryClient()
  const settled: string[] = []
  const fire = (name: string) =>
    new QueryMutationObserver(client, {
      mutationFn: addGuest,
      onSuccess: (data) => {
        settled.push(data.guest.name)
      },
    }).mutate(name)

  const first = fire('first')
  const second = fire('second')

  await expect.poll(() => reached).toEqual(['first'])
  // Give the second every chance to overtake before claiming it cannot.
  await delay(20)
  expect(reached).toEqual(['first'])

  releaseFirst()
  await Promise.all([first, second])

  expect(reached).toEqual(['first', 'second'])
  expect(settled).toEqual(['first', 'second'])
})

it('would let the second response land first without that serialisation', async () => {
  // The control. Without it the test above passes on a client that does nothing,
  // because a fast local mock is not a race.
  let releaseFirst: () => void = () => {}
  const firstHeld = new Promise<void>((resolve) => {
    releaseFirst = resolve
  })

  server.use(
    http.post(LEDGER, async ({ request }) => {
      const { name } = (await request.json()) as { name: string }
      if (name === 'first') await firstHeld
      return HttpResponse.json(created(name), { status: 201 })
    }),
  )

  const client = createQueryClient()
  const settled: string[] = []
  const fire = (name: string) =>
    new QueryMutationObserver(client, {
      scope: undefined,
      mutationFn: addGuest,
      onSuccess: (data) => {
        settled.push(data.guest.name)
      },
    }).mutate(name)

  const first = fire('first')
  const second = fire('second')

  await expect.poll(() => settled).toEqual(['second'])
  releaseFirst()
  await Promise.all([first, second])

  expect(settled).toEqual(['second', 'first'])
})
