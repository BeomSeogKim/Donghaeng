import { http, HttpResponse } from 'msw'
import { expect, it } from 'vitest'
import { server } from './server'

it('intercepts a real fetch at the network boundary', async () => {
  server.use(
    http.get('http://api.test/scaffold', () => HttpResponse.json({ ok: true })),
  )

  const response = await fetch('http://api.test/scaffold')

  expect(response.status).toBe(200)
  await expect(response.json()).resolves.toEqual({ ok: true })
})
