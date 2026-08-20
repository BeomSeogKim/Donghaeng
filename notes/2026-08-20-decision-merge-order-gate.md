# Decision — what we buy against the merge-order hole, and what we do not (2026-08-20)

`#138`. `#136`/`#137` fixed the symptom — a red `main`. This records the hole and
the deliberately small purchase.

## The hole

`#130` added a test asserting the **exact set** of endpoints the API serves. `#129`
added an endpoint. Both were cut from the same commit, both were green, neither
could see the other. The failure exists only in the merge result, and nothing ran
against that.

Three things that looked like safety nets and none of which fired:

1. **GitHub does not re-run a PR's checks when its base advances.** The setting
   that forces it is branch protection's "require branches to be up to date before
   merging", unavailable on a private free-plan repo
   (`2026-08-08-decision-merge-gate.md`).
2. **`.githooks/pre-push` cannot see it**, because at push time the branch really
   is green. The defect is created by the merge, not by the push.
3. **Retargeting does not trigger CI.** When a base merges, GitHub retargets its
   children — firing `pull_request.edited`, and the default `types` are
   `[opened, synchronize, reopened]`. The PR then shows a green merge button with
   **zero checks**, which at a glance is indistinguishable from "passed".

And a fourth, worse on the day: **the retarget never happened**, because merging
did not delete the source branches. Two PRs sat pointing at branches that were
already merged, so pressing merge would have merged into a dead branch instead of
`main`.

## The second failure was masked by the first

`seam` is `needs: api`. With `api` red, `seam` was **skipped, not failed** — so the
committed generated types, stale from the same pair of merges, were never reported.
Fixing only the visible failure would have turned `main` red again on the next run,
for a different reason. That is why `#137` carried both halves in one commit.

**A `needs:` edge does not only order jobs — it converts every downstream failure
into a silent skip for as long as the upstream is broken.** When upstream and
downstream share a root cause, which is exactly what happens when a document and its
generated artifact drift together, the dependency guarantees you see them one at a
time. The edge is still correct (`seam` consumes the artifact `api` uploads); what
changes is that a skipped job is now read as *unknown*, never as passed.

## What we bought

Nothing structural. `#128` diagnosed this cut as *발견과 지불 사이에 관문이 없었던
것*, and redesigning CI topology while the ledger holds zero guest rows would be
that mistake again. Only the near-zero-cost items:

- **Delete source branch on merge** (repository setting, on). The dead-branch
  targeting state stops existing.
- **`edited` added to `pull_request.types`.** A retarget now runs the pipeline. It
  also fires on title and body edits, which re-runs for nothing — `cancel-in-progress`
  bounds that, and at this repo's PR volume it is cheaper than the failure.
- **One operational rule in the root `AGENTS.md`**, because the case that actually
  bit us is the one no automation catches on this plan: two PRs both targeting
  `main` from the start, with `main` moving underneath them. *After every merge,
  re-run the open PRs before merging the next one.*

## What we did not buy, and the condition for revisiting

Branch protection with "require up to date" — the real fix — needs a paid plan or a
public repo. When either arrives, this record and most of
`2026-08-08-decision-merge-gate.md` are superseded together: the operational rule
becomes automation, and `.githooks/pre-push` can go.

Splitting `seam` off its `needs: api` edge is **not** the answer and was considered:
the job certifies generated types against the document that `api` produces, so
running it without that artifact would certify against nothing — which the workflow
already says in its own comment.
