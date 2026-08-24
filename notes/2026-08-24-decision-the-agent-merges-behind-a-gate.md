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

## The fourth condition, added after the first merge

The gate as first written enforced three things — green, not behind `main`, not
a reserved surface — and **all three were already mechanical before the agent
started merging.** The one condition that actually replaced the founder, "a
reviewer has cleared it", was the one left as prose. The reviewer said so; this
record's own quotation of `AGENTS.md` says so.

So the gate now also asks the PR. A comment or a review carrying a line of its
own that reads `Reviewed-at: <sha>`, plus at least one more line saying what was
found, must match the head's **tree**.

**The tree, not the commit — and the first cut got that wrong.** Keying to the
commit id defeats itself, because the merge-order rule four days earlier
mandates a rebase of every open PR after every merge, and a rebase always yields
a new commit id. The gate would then demand a fresh review of a diff that had not
changed by one byte, several times a day, with the block message handing over the
exact line to paste. It would have manufactured the false signature it exists to
make meaningful, as the *normal* path. A control everyone learns to satisfy
without performing is worse than a documented absence, because it reads as
coverage. Found in review before it merged.

**And the reviewer writes it, not the implementor.** As first delivered nothing
said who produces the artifact, so the default was: the implementor attempts a
merge, is blocked, and pastes the suggested line itself — the same hand signing
that its own work was read. `reviewer.md` now owns it.

What it is worth, stated honestly because the first version of this paragraph
overstated it: the line asserts a tree, not an act. Whoever writes it could have
skipped the review, and every agent here shares one `gh` identity, so no author
check can tell. What the gate buys is that the claim is **on the PR** — durable
past the session that made it, and stale the moment the content moves. It does
not make skipping impossible; it makes skipping something you can look up.

No label was added: `AGENTS.md` caps them at four and means it.

## How the slice disagreement was settled

The reviewer held that `#228` should have been two PRs — the gate repairs first,
green, then the policy — and by the one-issue-one-requirement rule it was right.
The counter offered at the time was that the policy is inert while Actions cannot
run, so "repairs first and green" was not available to buy. Actions came back
before the merge, which took that counter away.

Settled by merging `#228` whole anyway, for a reason that is about cost and not
about principle: the review had already run against the whole thing and produced
seven findings, all fixed, so splitting afterwards does not un-review anything.
What it would cost is reconstructing five commits by hand — `rebase -i` is
unavailable in the agent environment — seven days before launch.

**The principle was taken prospectively instead**, which is why this fourth
condition arrived as its own issue and its own PR rather than as another commit
on that branch.

## Still open

- **The Actions block is a quota, not a failed payment.** GitHub's annotation
  offers both readings; the billing usage says which. `Donghaeng` consumed
  **exactly 2,000 minutes** in August — the Free plan's private-repo allowance to
  the minute — and stopped dead on 2026-08-23, the same hour `seam` on `c548171`
  was refused. Every run since has been turned away at zero minutes.

  The burn is recent and steep: ~50–100 minutes a day until 2026-08-19, then 299
  / 376 / 516 across 08-20 to 08-22. Roughly **10 billable minutes per run**
  across the six jobs, and 20–46 runs a day. `Partitur` shows 21,267 minutes in
  the same account and consumes none of the allowance — it is public, and public
  repos run free. That is the whole difference.

  **Included minutes reset on the 1st, which is after the 2026-08-31 launch.**
  Waiting is not one of the options. At ~30 runs a day for seven days the
  overage is about 2,100 minutes — **roughly $13** at the Linux rate. Raising the
  spending limit is the cheap, boring answer, and it is a browser action: there
  is no API for it, and reading usage at all needs `gh auth refresh -s user`.

- **Not trimming CI to save it.** The obvious lever is the `edited` trigger,
  which re-runs the whole pipeline when a PR title or body changes. It is not
  being pulled. It exists because a retargeted PR shows a green merge button with
  *zero* checks, which reads as passed at a glance — it cost a red `main` on
  2026-08-20 — and `merge-gate.sh` only covers that for an agent's merge, not for
  a human's in the web UI. Trading a safety trigger for two dollars is the wrong
  side of "green means deployable".

- **Branch protection is still the honest fix.** GitHub answers the protection
  endpoint with *"Upgrade to GitHub Pro or make this repository public."* Both
  routes also solve the minutes problem — public repos run Actions free, Pro
  raises the allowance — and either would retire `.githooks/pre-push` and half of
  `merge-gate.sh`. Not decided here: publishing a commercial product's source
  seven days before launch is not a build-workflow question.
