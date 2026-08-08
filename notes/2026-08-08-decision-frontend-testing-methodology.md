# Decision — frontend testing methodology (2026-08-08)

Closes "Frontend methodology is a separate, unopened question" from
`notes/2026-08-07-decision-backend-tdd-methodology.md`. Backed by a
same-day research pass across 123 frontend engineering sources (production
case studies from Toss, Kakao, Woowahan, Kakao Mobility, Kakao Style, and
the Testing Library / Kent C. Dodds canon), distilled to the conclusions
below. The founder does not have a strong existing frontend intuition to
check this against, so the request was explicit: pick a methodology and
enforce it, don't leave it to per-session judgment.

## Decision

`frontend-implementor` runs the same three-gate discipline as the backend
(`notes/2026-08-07-decision-backend-tdd-methodology.md`), scoped to where
frontend risk actually concentrates — not applied uniformly to every
component. Uniform 100% coverage has diminishing returns past roughly 70%,
and a solo project pays that tax with no team to amortize it against.

**Mandatory Red→Blue→Green, one requirement at a time, for:**

- The ledger and headcount/meal-count display — any component rendering an
  aggregate the API returned.
- Every mutation flow — attendance tap, guest edit, CSV import, vendor-email
  paste/conflict resolution.
- Anything branching on `docs/api-spec.md`'s error `code` field to decide
  what to show — a wrong mapping here is a silent wrong message, not a
  crash.

**Not mandatory — write only when the code will be touched again, or a bug
there would genuinely hurt:** static layout, one-off screens, pure display
components with no logic. Chasing coverage on these is pure cost with no
return.

1. **Red Gate** — an integration test (below) written before the
   component/hook exists, confirmed failing for the right reason.
2. **Blue Gate** — the minimum implementation that turns it green.
3. **Green Gate** — refactor with the suite green throughout.

## What kind of test — integration by default, thin E2E on top

- **Default: integration tests, not isolated unit tests.** Render the real
  component with Vitest + React Testing Library. A frontend's job is
  mediating between the user, the backend, and props, so most of its real
  risk is in how pieces work together, not in isolated pure functions.
  Pure, dependency-free functions (a formatter, a validator) still get
  plain unit tests.
- **Mock only the network boundary, with MSW.** Never mock the app's own
  data-layer module, request wrapper, or a hook directly — that is exactly
  the "isolating your code from your own code" pattern that produces tests
  which stay green while the real code is broken. MSW intercepts at the
  actual HTTP layer, so the request-sending code stays fully exercised and
  only the response is faked.
- **Query like a user, not like an implementation.** Priority order:
  `getByRole` / `getByLabelText` / `getByText` first, semantic HTML
  attributes next, `data-testid` only when nothing semantic works. Never
  `container.querySelector` on a class name. Use
  `@testing-library/user-event`, not `fireEvent` — it will not fire an
  interaction a real user couldn't cause (e.g. typing into a disabled
  input).
- **E2E stays thin: Playwright, 2-5 flows, no more.** Critical cross-page
  paths only — mark attendance end to end, import and resolve a conflict,
  sign in. Not a coverage target. E2E's cost is a slow, flaky feedback
  loop, not a lack of realism; keep the surface small enough to babysit by
  hand.
- **Snapshot tests are not a substitute for behavior assertions.** Fine
  only for output that rarely changes on purpose and that a reviewer will
  actually read the diff of.

## Why

Backend risk concentrates where a wrong number ships silently. Frontend
risk concentrates differently: a mutation that doesn't fire, an attendance
tap that shows the wrong state, an error `code` mapped to the wrong Korean
message, an import conflict resolved the wrong way. The mandatory list
above is exactly the "wrong thing happens and nobody notices" surface —
the same standard the backend's Testcontainers mandate uses, translated to
where frontend actually breaks quietly.

## Where it lives

- This note is the decision record.
- `AGENTS.md` carries the summary, under "Frontend development methodology".
- `.claude/agents/frontend-implementor.md` carries the operative checklist.

## Still open

- [ ] Whether Playwright's component-testing mode ever replaces a
      Vitest+RTL test for something specifically hard to render in jsdom —
      none hit yet.
- [ ] CI wiring — none exists yet; this note governs authoring discipline,
      not a pipeline.
