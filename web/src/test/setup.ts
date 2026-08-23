import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { forgetAlreadyInAWedding } from '../lib/alreadyInAWedding'
import { server } from './server'

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))

/*
 * jsdom implements no CSSOM view module, so `window.matchMedia` does not exist
 * — and `hooks/useWideLayout` reads it, because 하객 추가 is a column of the
 * slab on a laptop and an overlay on a phone, and `aria-modal` has to say which.
 *
 * IT ANSWERS "NARROW" AND THAT IS THE MEANINGFUL CHOICE. A phone is the primary
 * device and the overlay is the case with something to assert, so the suite
 * exercises the layout with the stronger claim in it. There is no CSS in jsdom
 * either way, so this decides one attribute and nothing else on screen.
 */
if (typeof window.matchMedia !== 'function') {
  window.matchMedia = (media: string) =>
    ({
      media,
      matches: false,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList
}

afterEach(() => {
  cleanup()
  server.resetHandlers()
  // The invite token waits out the Google round trip in `sessionStorage`
  // (lib/invite.ts), and a jsdom window is shared by every test in a file. One
  // left behind diverts the next test's empty ledger into 초대 수락.
  sessionStorage.clear()
  // Same class of leak, in a module variable rather than in storage: the 409
  // verdict lives as long as the session does (lib/alreadyInAWedding.ts), and
  // a test file's modules outlive its tests.
  forgetAlreadyInAWedding()
})

afterAll(() => server.close())
