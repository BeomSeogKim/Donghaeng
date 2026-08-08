import { setupServer } from 'msw/node'

/*
 * The one place the network boundary is faked. Nothing else may be mocked:
 * mocking our own data layer or a hook produces tests that stay green while
 * the real code is broken (notes/2026-08-08-decision-frontend-testing-methodology.md).
 *
 * No default handlers yet — the first real endpoint adds them here. Until
 * then every request is unhandled, and setup.ts makes that an error.
 */
export const server = setupServer()
