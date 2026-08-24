# Decision — the agent merges, and the gate grows teeth first (2026-08-24)

Merging was the founder's, never an agent's
(`2026-08-08-decision-build-workflow.md`). It is now the agent's, once a
reviewer has cleared the change and the check is green. The reason is speed:
seven days to launch, and every stop had a human in it whose only input was
"yes".

Handing that over is only safe if the check means what it says. An audit the
same day says it did not, so this record covers both halves — what the gate
refuses now, and the merge rule that stands on it.

## What the audit found

Eight most recent `main` commits, four of them never certified:

| commit | what happened |
|---|---|
| `7c76831` (#219) | run cancelled, **zero jobs** |
| `93cc2b6` (#218) | run cancelled, **zero jobs** |
| `e6fe1bd` (#221) | `seam` red — committed types stale, from a merge made behind `main` |
| `c548171` (#220) | `seam` never started: *"recent account payments have failed or your spending limit needs to be increased"* |

And three of those eight merges went in **behind `main`** — `merge-base(main,
branch) != main` for #218, #221 and #220 — four days after the rule that
forbids it was written (`2026-08-20-decision-merge-order-gate.md`). A rule
this mechanical does not survive as prose. That is the whole finding.

## What was repaired

**The queue holds one.** `ci.yml` set `cancel-in-progress: false` on `main` and
its comment claimed that stopped a quick second merge from cancelling the first
run. It does not: the flag protects a run that has *started*, while GitHub keeps
at most one *queued* run per concurrency group and drops the older one when the
next arrives. Three merges inside 47 seconds left two `main` commits with zero
jobs. `main` now takes `github.sha` into its group, so every commit gets a group
of its own and nothing can evict anything.

**A stale branch is refused at the merge.** `merge-gate.sh` asked one question —
are the checks green — and green cannot tell you *what it ran against*.
It now also asks GitHub for `compare/{base}...{head}` and refuses any branch with
`behind_by > 0`, naming the rebase. Unknown counts as stale: if the comparison
cannot be made, the merge is refused rather than assumed current.

**Two guards matched a spelling, not an act.** `gh api --method PUT
.../pulls/{n}/merge` is the REST call underneath `gh pr merge`, and it walked
through `merge-gate.sh` without the hook ever asking about a check. A closing
trailer added with `gh pr edit --body` after the PR exists closes just as
silently as one written at creation, and `gh pr edit` is on the allow list, so
nothing stood in front of it at all. `db-guard.sh` had already learned this and
written it down; the other two had not been given the lesson.

**`closes-guard.sh` also leaked the other way.** Writing a document that quotes
the broken form tripped it — it blocked the audit page that reported its own
bug. `merge-gate.sh` fixed the same false positive for itself long ago. A
heredoc that writes a *file* is now stripped; a heredoc that carries a *message*
still is not, because that one is the subject.

**The push guard is finally tested.** `.githooks/pre-push` is the only thing
between a session and a direct push to `main`, and it was the one guard with no
suite and no mention in CI — including its budget path, which had never been
exercised at all. It has both now. What CI still cannot see is whether a given
clone ran `git config core.hooksPath .githooks`; that stays a per-machine fact
and there are three machines.

## What this costs

The founder stops seeing each merge. What replaces that is a reviewer verdict
the founder does not read and a check the founder does not watch — so the honest
statement is that **the blast radius of a bad review just grew**, and the two
things holding it are `merge-gate.sh` and the reviewer's independence.

That independence is weaker than the root `AGENTS.md` claimed. It said the
reviewers *cannot* write; they hold `Bash`, so they can. The line now says they
*do not*, which is a rule rather than a wall. Dropping `Bash` would make it a
wall again at the cost of the reviewer reading a diff, and that trade was not
taken today.

Reversal is one line: put `Bash(gh pr merge*)` back under `ask` in
`.claude/settings.json`.

## Still open

- **Billing.** Until the Actions spending limit is raised, `gh pr checks` cannot
  come back green, so `merge-gate.sh` refuses every merge. Auto-merge is inert
  and correctly so. This is the launch's first blocker, not this repo's.
- **Whether a carve-out is wanted** — auth, sessions, tokens and migrations are
  the surfaces where a wrong merge is expensive and quiet, which is the shape the
  product values single out. Nothing here carves them out; the founder asked for
  merging to move and that is what moved. Say so and it becomes one line.
