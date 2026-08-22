import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './server'

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))

afterEach(() => {
  cleanup()
  server.resetHandlers()
  // The invite token waits out the Google round trip in `sessionStorage`
  // (lib/invite.ts), and a jsdom window is shared by every test in a file. One
  // left behind diverts the next test's empty ledger into 초대 수락.
  sessionStorage.clear()
})

afterAll(() => server.close())
