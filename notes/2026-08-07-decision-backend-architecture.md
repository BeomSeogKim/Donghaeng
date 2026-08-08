# Decision — backend architecture (2026-08-07)

Confirms the architecture read discussed earlier the same day and shown as a
draft artifact, and adds two pieces raised in the same conversation: where
tests live, and how responsibility stays separated as a domain thickens.

## Package structure — domain-based, not layer-based

`api/` packages are organized by domain (`wedding/`, `guest/`, `import/`,
`auth/`, ...), each self-contained with its own Controller, Service,
Repository, and Entity. Layer-first packages (`controllers/`, `services/`,
`repositories/`) were rejected: every new domain would widen all three
folders forever, and Wedding/Guest/Import files would keep interleaving in
each one. A domain-based package costs one new folder per new domain and
nothing else.

## Layers stay shallow

Controller (DTO in/out) → Service (transaction boundary, invariants,
aggregate recomputation, `GuestChange` writes) → Repository (Spring Data
JPA + native aggregation queries). No hexagonal/ports-and-adapters layer,
no separate domain module — this is a solo MVP with no plan to swap
persistence technology, and that ceremony has no payback here.

Entities are not fully anemic: invariant-preserving logic (e.g., writing a
`GuestChange` row when attendance changes) lives on the entity or at the
Service boundary. API responses are always a separate DTO — an entity
never serializes directly to a response.

Exceptions: a small set of domain exceptions (`NotFound`, `Conflict`, ...)
mapped to HTTP status by one global `@ControllerAdvice`. No per-controller
try/catch.

## Responsibility stays separated as a domain thickens

The rule for splitting a class: **split when a class starts doing two
distinct things, not before.** Neither extreme is acceptable — a
`GuestService` that accretes attendance changes, import matching, meal
counts, and change auditing into one file is a junk drawer; scaffolding
five empty single-method services for a domain that currently does one
thing is the premature abstraction the global guidelines already rule out.
The trigger is actual responsibility growth, not anticipated growth.

Two mechanical rules make the split real instead of aspirational:

- **Kotlin `internal` visibility is the default** for classes inside a
  domain package that no other domain should reach directly. Only the
  Controller — and any explicit cross-domain contract a domain chooses to
  expose — is `public`. This turns illegal cross-domain coupling into a
  compile error instead of something a reviewer has to catch by eye.
- **Cross-domain access goes through a narrow, explicitly public contract**
  the owning domain exposes (e.g., `import/` resolving a wedding calls a
  small lookup that `wedding/` exposes on purpose) — never by reaching into
  another domain's entities or repository directly.

This is the answer to "domains keep getting fatter": the package boundary
(domain, not layer) was already decided above; this is what keeps the
*inside* of a thickening domain package from turning into its own
layer-soup.

## Where tests live and what kind

**The test tree mirrors the domain tree, not the layer tree.**
`src/test/kotlin/.../guest/` mirrors `src/main/kotlin/.../guest/` —
`GuestServiceTest`, `GuestRepositoryTest`, `GuestControllerTest` sit
together under `guest/`, the same way the production classes do. A test
suite organized by layer would fight the package structure it's supposed
to verify.

Three kinds of test, one per layer, chosen by where a requirement's risk
actually lives:

1. **Service — unit tests.** Plain JUnit 5, no Spring context, no
   database. Fast, run on every build. Covers business rules and
   invariants.
2. **Repository — JUnit 5 + Testcontainers, real Postgres.** Unchanged
   from the existing rule: mandatory, not optional, for any wedding-scoped
   query, any aggregation, any import path, anything touching a token —
   this is where a wrong number ships quietly.
3. **Controller — contract tests.** Verify the request/response DTO shape
   against `docs/api-spec.md`, since spec drift breaks the frontend's only
   source of truth.

TDD's Red Gate starts at whichever of the three carries the requirement's
risk — a pure business rule starts Red in a Service unit test, a new
aggregation starts Red in a Testcontainers test, a new endpoint shape
starts Red in a contract test. Not every requirement needs all three; the
existing risk list
(`notes/2026-08-07-decision-backend-tdd-methodology.md`) still decides
which layer's test is mandatory versus which is just where this particular
requirement happens to live.

## Where it lives

- This note is the decision record.
- `AGENTS.md` carries the summary, under "Backend architecture".
- `.claude/agents/backend-implementor.md` carries the operative checklist.
- The draft artifact shown during discussion is now superseded by this
  note — the artifact is a view, not the source of truth.

## Still open

- [ ] Naming convention for the cross-domain "port" contracts.
- [ ] Whether a domain package ever needs sub-packages of its own (none
      has grown large enough yet to test this).
