# Decision — build workflow (2026-08-08)

The last methodology gaps found by auditing the day's decisions before
scaffolding. Three real ones, closed here, plus the smaller items settled
with them. Companion to `notes/2026-08-08-decision-development-tempo.md`
(how work is cut) and `notes/2026-08-08-decision-work-tracking.md` (where
uncut work lives).

## A stop is a branch, and it lands by PR

The gap: `reviewer` and `explainer` are both handed "the diff", and nothing
defined what a stop's diff *is*. With work landing directly on `main`, a
stop that takes three fix commits after review has no single boundary — and
the comprehension gate's input would have been ambiguous exactly when the
stop was messiest.

**One stop = one branch = one pull request into `main`.** The diff is
`main...<branch>`, mechanically. Fix commits during review stay on the
branch and never touch `main`'s history. `Closes #N` goes in the PR.

This reverses the same day's earlier call that PRs were solo ceremony. The
condition named for revisiting it was CI, and CI is now in scope — a PR
with a red check is a merge that cannot happen, which is worth more than
the ceremony costs.

**CI red is never merged.** Not "fix it after", not "it's unrelated" — the
whole point of a check is that it is not subject to judgment in the moment.

## The seam is type-checked, not just documented

The gap: `docs/api-spec.md` is prose maintained by hand, and the frontend's
MSW handlers are written from it by hand. A backend field rename updates the
spec and the backend's own contract tests in the same change — and leaves
the frontend's mocks green against a shape the API no longer returns. Tests
passing while the two sides disagree is the precise failure mode this
product cannot afford.

**springdoc generates OpenAPI from the controllers; `web/` generates TS
types from that OpenAPI.** A rename then fails the frontend typecheck
instead of surviving to runtime.

The markdown spec stays, and stays authoritative for *meaning* — what an
endpoint is for, which invariant it protects, why a status code was chosen.
OpenAPI carries shapes; it cannot carry any of that, which is why generating
it does not make `docs/api-spec.md` redundant. Ownership is unchanged:
`backend-implementor` writes the markdown in the same change as the code.

This is the same principle the tracker decision ran on — **convert a
discipline into a mechanism**. It is also only cheap now: retrofitting
codegen means reopening both build configs later.

## A requirement spanning the seam is one issue with two children

**Parent issue = the requirement. Two sub-issues = the backend half and the
frontend half**, each its own stop, its own review, its own explanation, its
own PR. The backend child goes first, because the spec is the seam and the
frontend builds against it. The parent closes when both children do.

This keeps the tracking unit a requirement (per the work-tracking decision)
without pretending a cross-seam requirement is one stop.

**The children are created when the parent is picked up, not up front.**
Eighteen of the v1 issues span the seam, and splitting them all now would
add thirty-six issues describing work whose shape is still weeks away —
which is the same mistake as pre-enumerating stops, rejected in the
work-tracking record for the same reason. `#6` (OAuth) is split as the
worked example: `#37` backend, `#38` frontend. The rest get their children
at pickup. Post-v1 parents are not split at all until they enter a
release.

## Smaller items settled at the same time

- **CI**: GitHub Actions on every push and PR — `api/` build + test,
  `web/` typecheck + test, ktlint. Filed as its own `infra` issue, along
  with the branch protection that makes "never merge red" mechanical rather
  than remembered.
- **The explanation is linked from its issue.** `explainer` publishes to an
  Artifact; the URL is posted as a comment on the issue. The issue then
  doubles as the index of explanations, which is the only durable answer to
  "which document covered the aggregation?" — the documents themselves are
  deliberately not committed.
- **Reviewer/implementor disagreement**: the implementor may push back
  **once**, in writing, stating why the finding is wrong. If the reviewer
  holds, the founder settles it. Without this an auto-delegated implementor
  either capitulates to a wrong finding or silently ignores a right one, and
  both look identical in the report.
- **Local infra**: database `donghaeng`, role `donghaeng_app`, connection
  string in sealbox as `donghaeng/DATABASE_URL`. No `_test` database —
  backend tests use Testcontainers. Ports 8080 (`api/`) and 3000 (`web/`),
  registered in `../../notes/local-infra.md`.
