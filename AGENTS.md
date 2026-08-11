# Donghaeng (동행)

Wedding-journey companion web service for couples — centered on a guest
ledger (하객·좌석·축의금). See README.md for the product pitch. **동행** is
"walking together"; repo/folder slug `donghaeng`.

## Where the rules live

This file carries what binds **regardless of which tree you are in**.
Tree-specific rules load lazily from the subtree you touch.

| File | Carries |
|---|---|
| `AGENTS.md` (here) | Product truth, tempo, work tracking, build workflow, agents |
| `api/AGENTS.md` | Schema ownership, backend TDD/architecture/API conventions, security posture, domain mechanisms |
| `web/AGENTS.md` | Frontend methodology/architecture, the token bridge, the design system, screen rules |
| `notes/README.md` | Index of the decision records — the *why* behind all of it |

`CLAUDE.md` is a symlink to `AGENTS.md` at each level; edit `AGENTS.md` only.
**Read your subtree's file before writing code in it.**

## Pick up here (last session: 2026-08-11)

**The substrate is one item from closed.** Scaffolding, CI (`prod-boot` +
`docker`), the local merge gate, schema ownership, the RFC 9457 error
contract, `web/`'s linter and design-value checker, and the **v1 baseline
schema** (`#3`) are all merged; `main` is clean.

There is application code and a schema but **no domain code**: `api/src/main`
is four config guards, the error contract and one migration file; `web/src` is
App, the query client and the test harness. **The first entity is written in
`#37`.**

⚠️ **The schema is merged but has not been applied to any real database.**
Flyway runs in tests only, so `#83` changed nothing in dev or prod — the
founder types `V1__baseline_schema.sql` by hand, wrapped in an explicit
`BEGIN; … COMMIT;` (Flyway supplies that transaction in tests, which is why
the file omits it). **Read the column sizes while typing**: `ddl-auto:
validate` compares JDBC type codes only, so a mistyped `varchar(20)` boots
fine and fails later on a long 하객 이름. Until this is done, dev has no
tables and `#37` cannot run against it.

**The order from here** (`notes/2026-08-10-decision-auth-gate-and-sequence.md`):

    #80      wedding_id allowlist meta test    ← next, and small
    #6/#37   social login — session issuance + CurrentUser
    #7       웨딩 만들기 — the first membership exists here
    #5       CurrentWedding resolution + cross-tenant 404
    #8~      vertical, all of it

`#80` goes first because `#3` created the exception it must carry
(`guest_meal_count`'s integrity-only `wedding_id`) and `#37` is where the next
new table appears — the checker should be watching when that table is written,
not after. **Auth lands after login** because no v1 requirement can be built
ahead of it anyway: every one of `#7`–`#24` sits behind "who is asking" and
"which wedding".

**Design has no remaining blocker.** Open items live as `open-question` issues
(`gh issue list --label open-question`), never as a list here. Success criteria
are deliberately deferred until after the MVP is built.

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
couple (widened 2026-08-06), server-side session behind an HttpOnly cookie;
**guests are never authenticated.** Deployment follows the workspace standard
(`../../notes/infra-zones.md`): static → Cloudflare Pages, API → VPS docker
compose, managed Postgres. Per-tree detail is in the subtree files.

**Local infra**: DB `donghaeng`, role `donghaeng_app`, connection string in
sealbox at `donghaeng/DATABASE_URL` (never a `.env`). No `_test` database —
backend tests use Testcontainers. Ports 8080 (`api/`), 3000 (`web/`),
registered in `../../notes/local-infra.md`.

## Development tempo (decided 2026-08-08)

`notes/2026-08-08-decision-development-tempo.md`. The goal it serves is the
founder's: **minimize the cognitive debt of AI-written code.**

- **Cut vertically, by requirement — never horizontally by layer.** One
  requirement = one Red/Blue/Green cycle = one review = one explanation = one
  commit = **one stop**. "웨딩 생성" and "웨딩 정보 수정" are separate stops. A
  layer slice has no Red Gate test worth writing, and the seams between
  separately-approved layers are exactly where an AI silently disagrees with
  itself.
- **One honest exception — the substrate**, done horizontally, once, at the
  front. Everything after is vertical.
- **Slice size is measured in new concepts, not lines: one or two per stop.**
  The founder reads via the explanation document rather than the raw diff.
- **Order at every stop:** implementor → `reviewer` + `security-manager` → fix
  → `explainer` → founder reads and takes the quiz → commit. The explanation is
  written only after findings are resolved, so it describes verified code.
- **The comprehension gate is tiered**, so it survives past week two. A new
  concept gets the full explanation + quiz; a repeated pattern gets
  `reviewer`'s report only. The implementor proposes the tier; the founder
  overrides freely.
- **Never let the author explain its own work.** An implementor explaining its
  own change explains its *intent*, so a bug gets described as the bug-free
  version it meant to write — unknown debt turned into false confidence, which
  is worse than no gate.

## Work tracking (decided 2026-08-08)

**GitHub Issues on `BeomSeogKim/Donghaeng`, reached with `gh` — no Jira, no
board, no backlog file.** `notes/2026-08-08-decision-work-tracking.md`.

- **`notes/` is why; an Issue is what's left and where it stands.** An issue
  never decides anything. Rationale in an issue body that isn't in `notes/` is
  a bug — move it to a record and link.
- **One issue = one requirement, not one stop.** It closes when the requirement
  is done, however many commits that took.
- **`Closes #N` (or `Refs #N`) in the commit body** — state updates as a
  by-product of a message that gets written anyway. **One keyword closes
  exactly one issue**: `Closes #33, #35` closes `#33` and silently leaves `#35`
  open. Repeat the keyword or split across lines, and **verify closure after a
  merge** whenever more than one issue is named. Nothing goes red; the tracker
  just goes quietly wrong, which is the one failure this mechanism exists to
  avoid.
- **Two milestones** — `v1` and `post-v1` (deferred, never cancelled). **Four
  labels** — `api`, `web`, `infra`, `open-question`. Don't add more; labels stop
  meaning anything the moment they multiply.
- **`open-question` closes only by writing a `notes/` record.**
- **Leftover concepts become issues.** When an implementor stops after the first
  concept and reports what it left, file those before moving on — that report is
  the intake path for most new work.

## Build workflow (decided 2026-08-08)

`notes/2026-08-08-decision-build-workflow.md`.

- **One stop = one branch = one PR into `main`.** The diff `reviewer` and
  `explainer` are handed is `main...<branch>` — that is what makes "the diff of
  a stop" mechanical instead of a judgment call. Fix commits stay on the branch.
- **A red check is never merged.** Not "unrelated", not "fix it after". CI runs
  `api/` build + test, `web/` typecheck + test and ktlint — plus two jobs that
  exist because **green has to mean deployable, not compiling**: `prod-boot`
  boots the committed `application-{dev,prod}.yml` for real, and `docker` runs
  the packaged image under the prod profile and asserts it serves HTTP, reaches
  Postgres, and **answers 404 on `/v3/api-docs`, `/swagger-ui`, `/actuator`** —
  a negative assertion on the shipped artifact, the only kind that survives a
  profile-precedence mistake.
- **The merge gate is local, and leaky on purpose**
  (`notes/2026-08-08-decision-merge-gate.md`). Branch protection is unavailable
  — private repo on the free plan returns 403 — so `.githooks/pre-push` refuses
  a direct push to `main` and a `PreToolUse` hook blocks `gh pr merge` unless
  `gh pr checks` is green, failing closed. **A merge from the GitHub web UI
  bypasses both.** Revisit the moment the repo goes public/paid or a second
  person gets commit access; delete the local gate then rather than stating the
  rule twice.
- **The seam is type-checked, not just documented.** springdoc generates OpenAPI
  from the controllers; `web/` generates its TS types from that. A renamed field
  then breaks the frontend build instead of leaving MSW mocks green against a
  shape the API no longer returns. `docs/api-spec.md` stays authoritative for
  **meaning**, which OpenAPI cannot carry.
- **A requirement spanning the seam is one parent issue with two sub-issues**,
  backend and frontend. Each child is its own stop, review, explanation and PR;
  the backend child goes first because the spec is the seam. **Children are
  created when the parent is picked up, not up front** — `#6` → `#37`/`#38` is
  the worked example.
- **`explainer`'s document is linked from its issue** as a comment, so the issue
  is the index of explanations — the documents are deliberately not committed.
- **An implementor may push back on a review finding once**, in writing, stating
  why it is wrong. If the reviewer holds, the founder settles it. Silent
  capitulation and silent dismissal look identical in a report, which is why
  this is a rule and not a vibe.

## v1 scope (cut 2026-08-06)

> **A tool the couple operates for headcount and meal planning, plus a
> vendor-email parser that saves them typing.**

`notes/2026-08-06-decision-v1-scope-and-meals.md`. Intake is exactly two paths
— the couple entering it directly, and a parsed vendor RSVP email — plus CSV
import, which builds the ledger rather than answering it. **Our own RSVP links
are deferred, not cancelled. No guest meets the product in v1.**

The review *queue* therefore has nothing to fill it — but **matching still
runs**, at vendor-email paste and at CSV import. Both resolve on a screen the
couple is already looking at rather than queuing. Direct entry targets a
specific guest, so it needs no matching at all.

## Standing product facts

These bind both trees. The mechanisms are in `api/AGENTS.md` and
`web/AGENTS.md`; a fact is stated **here only**, never twice.

- **The Wedding, not the user, is the top-level unit.** The couple shares full
  access to one ledger, and one person may belong to several weddings.
- **보증인원 is the venue's number, never ours.** We never recommend it and
  never adjust counts statistically — the headcount sums real responses and the
  couple's own expected values, nothing else. **유아식 does not adjust it
  either** (2026-08-11): we know a venue's child pricing exactly as well as we
  know its buffer — not at all. So 유아 인원 is neither added nor subtracted; it
  stands as **its own count beside** the 식대 인원 and the couple applies their
  own contract. Deciding it globally would hand half our couples a wrong number,
  and a wrong number here is money.
- **The ledger and the headcount are one screen** — tapping attendance moves the
  number in place. **Couple entry is the primary intake path**, not a fallback:
  attendance normally reaches them via parents and KakaoTalk.
- **Meal types are configured per Wedding, not fixed by us** — venues differ
  (유아식, 글루텐프리, 뷔페 with no distinction at all). Default is a single
  type, so the simple case configures nothing. A type in use cannot be deleted.
  **Dietary needs are meal types, never a separate field.** Meal is a
  party-level boolean on a response but **per-type integer counts** on the
  ledger — that asymmetry is where all meal detail lives.
- **Accessibility needs (휠체어 etc.) are a guest attribute**, free text — they
  belong to the person and carry forward to seat assignment later.
- **Guest groups are seven fixed categories plus a free label**: 가족 · 친척 ·
  사촌 · 혼주 손님 · 친구 · 직장동료 · 기타. Aggregation splits by **category
  only** — free labels fracture on typing variants. Family is one bucket
  deliberately: a finer list keeps producing members that fit nowhere (조부모 was
  the first), and every family category is single digits while 혼주 손님 / 친구 /
  직장동료 run to a hundred.
- **A row whose 관계 is not one of the seven does not import**
  (`notes/2026-08-11-decision-import-row-rejection.md`) — the rest of the file
  does, and the couple fixes those rows and uploads again. **Rejection is per
  row, never per file**, so an empty 이름 and a non-positive 참석 인원 are the
  same case. That record **supersedes the synonym table and the map-by-distinct-
  value screen**, because **the couple is a reviewer, not a courier**: they open
  the file before uploading and can classify their own 이모. The .xlsx dropdown
  stays — the person filling the column is a parent who never sees our screens,
  and its validation is advisory, so **the importer never assumes the column is
  clean.**
- **"Not sure" must never block** — an ambiguous *identity* imports as a separate
  guest, because merging is lossless. No conflict with rejecting a bad 관계: the
  two differ in whether anyone *can* answer. Identity ambiguity has nobody; a
  malformed 관계 has the couple, holding the mouse.
- **Import is a workflow, not an upload.** We hand the couple a template
  (**이름 · 관계 · 참석 인원 · 연락처 선택** — deliberately no attendance
  column), they distribute it to both sets of parents, and files come back
  several at a time. **The file has no opinion about attendance**: a returning
  file is a stale name list, not a fresh claim that everyone on it is coming.
- **Never overwrite the couple's edits — alert and let them choose**, guarded by
  two rules that keep it quiet: **silence is not disagreement** (a blank cell or
  absent column is not a claim) and **a resolved question is not asked again**.
  Comparison is by multiset, not row: two guests may legitimately share a name
  and a group.
- **A deleted guest reappearing in an import is asked about, not skipped** —
  되살리기 / 그대로 두기. A **deliberate exception** to "a returning file is a
  stale name list": attendance has a screen to fix it on and deletion does not,
  so the import is the only place the couple will ever be told that guest exists.
- **Every mutation response carries the recomputed aggregate**, and the client
  handles out-of-order responses. Forced by "one screen" + "all computation
  server-side": a number lagging the tap by 100ms is fine, a number moving
  backwards is not.
- **Every intake channel converges on one matching pipeline**, and **ambiguity
  is never guessed** — 2+ candidates means `needs_review`, an unrecognized email
  template means `unsupported`.

## Product values (apply to every decision)

1. **정직함 · 믿음직함** — premium-service trust. Guest contacts and, later,
   축의금 money data are sensitive: security, privacy and never-wrong numbers
   are requirements, not polish.
2. **깔끔하되 핵심은 다 있게** — fewer things, each complete. When in doubt, cut
   scope, not quality.

## Agents (set up 2026-08-07, `explainer` added 2026-08-08)

Five subagents in `.claude/agents/`: **`backend-implementor`** (all `api/`
code, sole owner of `docs/api-spec.md`), **`frontend-implementor`** (all `web/`
code, reads the spec and never `api/` source), **`reviewer`** (correctness, the
domain question, convention, the refactoring gate), **`security-manager`**
(audits against `api/AGENTS.md` and
`notes/2026-07-30-decision-network-security.md`), **`explainer`** (the
comprehension gate — Background/Intuition/Code/Quiz, in Korean, as a
self-contained HTML file outside the repo).

- **Delegation is automatic in this repo.** Route implementation work to the
  implementors and review work to the reviewers without being asked. This
  overrides any general default about not spawning agents unprompted.
- **Every agent reads this file plus the `AGENTS.md` of the tree it works in.**
- **The API contract is a shared asset, not a backend artifact.**
  `docs/api-spec.md` is written by the backend **in the same change as the
  code**, and the frontend builds against it without reading `api/`. When the
  spec is silent or wrong the frontend stops rather than guessing.
- **The reviewers cannot write.** No `Edit`, no `Write` — that is what makes
  their verdict worth reading. They report; the implementors fix. `explainer`
  has `Write` for exactly one purpose: its own HTML file, in the scratchpad.
- **`explainer` runs last and cold.** After review findings are resolved, never
  before. It gets the diff and `notes/`, never the session that produced the
  code.

## Do not reference the prior attempt

This project restarts an earlier wedding-related service, archived at
`archive/experiments/2026-07/wedding-management`. Do not read, port, or take
design/architecture cues from that archive — a deliberate fresh start, not a
continuation. If historical context is needed, ask the founder directly.

## Rules

- **Language**: this file, README.md, `notes/`, code comments and scripts are
  English (workspace convention). In-app user-facing copy is Korean.
- **Full engineering discipline applies** per workspace rules for `products/`:
  git repo, README, AGENTS.md/CLAUDE.md, tests.
- **Decisions are recorded as dated files in `notes/`** and indexed in
  `notes/README.md` — always, whether or not a standing rule changes.
- **A rule enters an `AGENTS.md` only if an agent would break it without the
  reminder.** Everything else stays in its `notes/` record. A *why* stays only
  if it stops a hand mid-violation: "we know a venue's child pricing as well as
  we know its buffer" earns its place because without it an agent will 'improve'
  the count; the glibc/ICU collation story does not, because the rule alone
  produces correct code.
- **A rule belongs at exactly one level.** Both trees → root. One tree → that
  subtree. **Never both** — two statements of one rule is how they drift.
- **Each file holds a budget: root ≤ 350 lines, each subtree ≤ 280.** Over
  budget, the next change compresses or relocates *before* it adds. This exists
  because the append rule ran unchecked to 908 lines
  (`notes/2026-08-11-decision-agents-md-hierarchy.md`); a rule that only ever
  admits will do it again. The budget is a ceiling that forces the choice, not
  a target — `wc -l AGENTS.md */AGENTS.md` is the check.
