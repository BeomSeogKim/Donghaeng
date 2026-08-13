# Donghaeng (동행)

Wedding-journey companion web service for couples — centered on a guest
ledger (하객·좌석·축의금). See README.md for the product pitch. **동행** is
"walking together"; repo/folder slug `donghaeng`.

## Where the rules live

This file carries what binds **regardless of which tree you are in**, and
nothing else. Everything below loads on demand.

| File | Carries |
|---|---|
| `AGENTS.md` (here) | Product truth, agents, the rules about rules |
| `api/AGENTS.md` | Schema ownership, backend architecture/API/TDD, security posture, domain mechanisms |
| `web/AGENTS.md` | Frontend methodology/architecture, the token bridge, the value checker, screen rules |
| `design/AGENTS.md` | The design system — thesis, tokens, contrast, typography, components |
| `notes/README.md` | Index of the decision records — the *why* behind all of it |

`CLAUDE.md` symlinks to `AGENTS.md` at each level; edit `AGENTS.md` only.
**Read your subtree's file before writing code in it** — and re-read it after a
`/compact`, which re-injects only this root file.

## Pick up here (last session: 2026-08-13)

**Google login works end to end.** `#37` is merged, `V1`+`V2` are applied to
dev by hand, and a real browser round trip issues a `DH_SESSION` cookie that
`/auth/me` resolves. `api/src/main` now has `auth/` (composition root),
`auth/account/`, `auth/oauth/`, `auth/session/`, `config/` and `error/`.

⚠️ **DDL applied by hand must be applied as `donghaeng_app`.** Applying it as
a personal superuser leaves every table owned by that role and the app gets
`permission denied` — invisible until boot, and `#105` is open on it. After
any hand-applied migration: `select count(*) from pg_class where
relnamespace='public'::regnamespace and relkind in ('r','S') and
pg_get_userbyid(relowner) <> 'donghaeng_app';` must be `0`.

**What is next, open, and left is `gh` — not this file.**
`gh issue list --milestone v1`, `--label open-question` for the undecided. The
build order and why auth lands *after* login are in
`notes/2026-08-10-decision-auth-gate-and-sequence.md`. Design has no remaining
blocker; success criteria are deferred until after the MVP.

## Working style

**Talk design through; don't hand over option menus.** The founder is the
domain owner, and the biggest corrections have all come from domain facts that
could not be derived from these notes — 보증인원 is the venue's number,
attendance arrives via parents and KakaoTalk, the real import risk is
re-uploading the same file, the parents' sheet lists attendees and so never
states attendance. **State a read, ask one open question, converge.** Reserve
multiple-choice for operational forks.

## Stack (decided 2026-07-30)

`notes/2026-07-30-decision-tech-stack.md`. Separated frontend and backend:
`api/` is Kotlin + Spring Boot (JSON only), `web/` is React + TypeScript +
Vite built to static files. Auth is **네이버 · 카카오 · 구글** OAuth for the
couple, server-side session behind an HttpOnly cookie; **guests are never
authenticated.** Deployment follows the workspace standard
(`../../notes/infra-zones.md`): static → Cloudflare Pages, API → VPS docker
compose, managed Postgres. Per-tree detail is in the subtree files.

**Local infra**: DB `donghaeng`, role `donghaeng_app`, connection string in
sealbox at `donghaeng/DATABASE_URL` (never a `.env`). No `_test` database —
backend tests use Testcontainers. Ports 8080 (`api/`), 3000 (`web/`),
registered in `../../notes/local-infra.md`.

## Work tracking and build (decided 2026-08-08, amended 2026-08-13)

`notes/2026-08-08-decision-work-tracking.md`,
`notes/2026-08-08-decision-build-workflow.md`. **There is no development
ritual** — the stop, the mandatory review pipeline and the comprehension gate
were removed 2026-08-13
(`notes/2026-08-13-decision-drop-the-stop-pipeline.md`), which also records
what that costs. Everything below still binds.

- **GitHub Issues via `gh`** — no Jira, no board, no backlog file. **`notes/`
  is why; an Issue is what's left.** An issue never decides anything; rationale
  in an issue body that isn't in a record is a bug.
- **One issue = one requirement.** Branch off `main`, PR back into it.
- **A red check is never merged.** Not "unrelated", not "fix it after". Green
  means *deployable* — `prod-boot` boots the real config, `docker` asserts the
  shipped image answers 404 on `/v3/api-docs`, `/swagger-ui`, `/actuator`.
  **Merging is the founder's, never an agent's.**
- **Two milestones** (`v1`, `post-v1` — deferred, never cancelled) and **four
  labels** (`api`, `web`, `infra`, `open-question`); don't add more, labels stop
  meaning anything once they multiply. **`open-question` closes only by writing
  a `notes/` record.**
- **The seam is type-checked** — springdoc generates OpenAPI, `web/` generates
  its TS types from it. `docs/api-spec.md` stays authoritative for **meaning**,
  which OpenAPI cannot carry.
- **A requirement spanning the seam is one parent issue with two sub-issues**,
  backend first because the spec is the seam. Children are created when the
  parent is picked up, not up front.
- **An implementor may push back on a finding once**, in writing; if the
  reviewer holds, the founder settles it. Silent capitulation and silent
  dismissal look identical in a report.
- **The gates are local and leaky on purpose** — branch protection is
  unavailable on a private free-plan repo, so `.githooks/pre-push` and the
  `.claude/hooks/` guards stand in. **The GitHub web UI bypasses them.** Delete
  them when real branch protection arrives
  (`notes/2026-08-08-decision-merge-gate.md`).

## v1 scope (cut 2026-08-06)

> **A tool the couple operates for headcount and meal planning, plus a
> vendor-email parser that saves them typing.**

`notes/2026-08-06-decision-v1-scope-and-meals.md`. Intake is direct couple
entry and a parsed vendor RSVP email, plus CSV import — which builds the ledger
rather than answering it. **Our own RSVP links are deferred, not cancelled. No
guest meets the product in v1**, so the review *queue* has nothing to fill it,
but **matching still runs** at vendor-email paste and CSV import.

## Standing product facts

These shape how *any* requirement is read, so they are ambient. **Facts that
bind only while one requirement is built live in that requirement's `notes/`
record**, loaded when the issue is picked up — the only moment they mean
anything.

- **The Wedding, not the user, is the top-level unit.** The couple shares full
  access to one ledger, and one person may belong to several weddings.
- **보증인원 is the venue's number, never ours.** We never recommend it and
  never adjust counts statistically — the headcount sums real responses and the
  couple's own expected values, nothing else. **유아식 does not adjust it
  either**: we know a venue's child pricing exactly as well as we know its
  buffer — not at all. 유아 인원 stands as its own count beside the 식대 인원.
  Deciding it globally would hand half our couples a wrong number, and a wrong
  number here is money.
- **The ledger and the headcount are one screen** — tapping attendance moves the
  number in place. **Couple entry is the primary intake path**, not a fallback:
  attendance normally reaches them via parents and KakaoTalk.
- **Every mutation response carries the recomputed aggregate**, and the client
  handles out-of-order responses. A number lagging the tap by 100ms is fine, a
  number moving backwards is not.
- **Every intake channel converges on one matching pipeline**, and **ambiguity
  is never guessed** — 2+ candidates means `needs_review`, an unrecognized email
  template means `unsupported`.

## Product values (apply to every decision)

1. **정직함 · 믿음직함** — premium-service trust. Guest contacts and, later,
   축의금 money data are sensitive: security, privacy and never-wrong numbers
   are requirements, not polish.
2. **깔끔하되 핵심은 다 있게** — fewer things, each complete. When in doubt, cut
   scope, not quality.

## Agents and automation

**`backend-implementor`** (all `api/` code, sole owner of `docs/api-spec.md`)
and **`frontend-implementor`** (all `web/` code, reads the spec and never
`api/` source). **`reviewer`** and **`security-manager`** are available, not
scheduled — call them when the change warrants it, which on auth, sessions,
tokens, native SQL or wedding-scoped queries it does.

- **Delegation is automatic in this repo**, and **the main loop never writes
  `api/` or `web/` code itself.** This overrides any general default about not
  spawning agents unprompted.
- **Every agent reads this file plus its tree's `AGENTS.md`.** Prompts carry
  role and *own-behavior* rules only — never a copy of a tree file's rule.
- **The reviewers cannot write.** No `Edit`, no `Write` — that is what makes
  their verdict worth reading.
- **The guards in `.claude/hooks/` fail closed and are tested in CI**, along
  with an assertion that they are still *wired* — a passing suite says the hook
  works, not that anything runs it.

## Do not reference the prior attempt

This project restarts an earlier wedding-related service, archived at
`archive/experiments/2026-07/wedding-management`. Do not read, port, or take
design/architecture cues from that archive — a deliberate fresh start, not a
continuation. If historical context is needed, ask the founder directly.

## Rules about rules

- **Language**: this file, README.md, `notes/`, code comments and scripts are
  English (workspace convention). In-app user-facing copy is Korean.
- **Decisions are recorded as dated files in `notes/`** and indexed in
  `notes/README.md` — always, whether or not a standing rule changes.
- **A rule enters an `AGENTS.md` only if an agent would break it without the
  reminder**, and a *why* stays only if it stops a hand mid-violation. "We know
  a venue's child pricing as well as we know its buffer" earns its place; the
  glibc/ICU collation story does not, because the rule alone produces correct
  code.
- **A rule belongs at exactly one level — never two.** Both trees → root. One
  tree → that subtree. Something mechanically checkable → a hook or a test, and
  then *not* also prose. Two statements of
  one rule is how they drift, and it has already cost this repo a live bug
  (`notes/2026-08-11-decision-claude-setup.md`).
- **Budgets are enforced, not stated**: root ≤ 220 lines, each subtree ≤ 280.
  `.githooks/pre-push` refuses a push that exceeds them. Over budget, compress
  or relocate *before* adding.
