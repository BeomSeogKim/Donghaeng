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

## What the review sent back

The first cut of these repairs went to `reviewer` before merging, which is the
policy this record introduces, applied to itself. It came back with a
**regression in the fix**, which is the finding worth keeping:

The heredoc strip that stopped `closes-guard.sh` refusing documents was
line-scoped — it decided by looking at the heredoc's opener. But a file can
*become* a message. `cat > /tmp/m.txt <<EOF … EOF` followed by `git commit -F
/tmp/m.txt` is a commit message that never appears on a command line, and the
strip dropped it: the exact `#83` failure, reintroduced by the fix for a false
positive. The strip now reads the redirect target off the opener and stands down
if that path is later handed to `-F`, `--body-file` or `--file`. Both shapes are
in the suite.

Four more, all fail-open, all now closed: a flag-first `gh pr merge --squash 220`
extracted no number and fell back to *the current branch's* PR — with worktrees
that is routinely a different, greener one; two merges in one command left the
second unchecked; `gh api -X PUT "repos/$R/pulls/$N/merge"` still passed because
the new branch wanted literal digits; and a failed `jq` turned the whole gate off
silently.

And the observation behind all of them: **the suite could not have caught any of
it.** Every "expected 2" case reached 2 through `gh` being unauthenticated in
CI, so the entire staleness and reserved-surface path could have been deleted
with the suite still green. The calls now route through `GH=${GH:-gh}` and the
suite drives a stub — green-and-current, green-and-behind, unreadable base,
non-numeric `behind_by`, unreadable file list, a reserved path, and which PR
number each invocation actually asks about. The `pre-push` suite had the same
disease in a different form: it ran against the real repo, whose root
`AGENTS.md` sits at exactly 220/220, so adding one line there would have turned
four ref-check cases red for the wrong reason. It builds its own tree now.

One finding stands unresolved by agreement: the reviewer holds that this should
have been two PRs — repairs first, green, then the policy — and by the repo's
own one-issue-one-requirement rule it is right. The counter, recorded rather
than argued: the policy is inert until Actions can run at all, so landing the
repairs "first and green" is not available to buy. The founder settles it.

## What this costs

The founder stops seeing each merge. What replaces that is a reviewer verdict
the founder does not read and a check the founder does not watch — so the honest
statement is that **the blast radius of a bad review just grew**, and the three
things holding it are `merge-gate.sh`, the carve-out below, and the reviewer's
independence.

That independence is weaker than the root `AGENTS.md` claimed. It said the
reviewers *cannot* write; they hold `Bash`, so they can. The line now says they
*do not*, which is a rule rather than a wall. Dropping `Bash` would make it a
wall again at the cost of the reviewer reading a diff, and that trade was not
taken today.

Reversal is one line: put `Bash(gh pr merge*)` back under `ask` in
`.claude/settings.json`.

## Four surfaces stay the founder's

Decided the same day, on the second pass. **Auth, sessions, tokens and
migrations are carved out of agent merging.** Everywhere else a bad merge
announces itself — a wrong screen is visible, a wrong endpoint fails a test. On
these four it does not: a session bug is a quiet leak, a bad migration is quiet
data, and both are the shape the product values name outright (정직함·믿음직함 —
a wrong number, a lost edit, a leaked contact).

The list is `reserved-surfaces.sh`, not prose and not a regex buried in
`merge-gate.sh`: one home, and a suite that exercises it without needing a live
green PR. It reads paths on stdin and refuses `api/`'s `auth/` tree, every file
under `db/migration/`, and `InviteToken`. It is scoped to `api/` deliberately —
the client being wrong about a token costs a failed request; the server being
wrong about one costs the token.

A PR that touches one of them is not split up. The whole PR goes to the founder.

## Still open

- **Billing.** Until the Actions spending limit is raised, `gh pr checks` cannot
  come back green, so `merge-gate.sh` refuses every merge — including the one
  that introduces this policy. Auto-merge is inert, and correctly so. There is no
  API for raising it: `PATCH`-ing a spending limit does not exist, and reading
  usage needs an interactive `gh auth refresh -s user`. It is a browser action.
- **Branch protection is still the honest fix.** GitHub answers the protection
  endpoint with *"Upgrade to GitHub Pro or make this repository public."* Both
  routes also solve the minutes problem — public repos run Actions free, Pro
  raises the allowance — and either would retire `.githooks/pre-push` and half of
  `merge-gate.sh`. Not decided here: publishing a commercial product's source
  seven days before launch is not a build-workflow question.
