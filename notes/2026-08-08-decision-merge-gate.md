# Decision — the merge gate, where branch protection cannot go (2026-08-08)

Companion to `notes/2026-08-08-decision-build-workflow.md`, which set the rule
this record is about enforcing. Written while landing CI (issue #36).

## The gap

The build workflow made two things rules:

> One stop = one branch = one pull request into `main`.
> A red check is never merged.

And the point of writing them down was that **the machine enforces them, not
memory** — issue #36's own words. Landing CI closed the first half: the checks
now exist. The second half, refusing the merge, was to be GitHub branch
protection.

That half turns out to be unavailable:

```
$ gh api repos/BeomSeogKim/Donghaeng/branches/main/protection
403: Upgrade to GitHub Pro or make this repository public to enable this feature.
```

**A private repository on the free plan gets neither branch protection nor
rulesets.** A rule that is written but unenforced is not a decision, it is a
preference — and a green check named "deployable" that nothing stops you from
merging past is a claim the system does not back. That makes it a
정직함·믿음직함 problem, not only a process one.

## Options

- **GitHub Pro ($4/month)** — stay private, server-side enforcement works as
  designed.
- **Make the repository public** — protection becomes free, at the cost of
  publishing the code, the issues and `notes/`, and of gaining fork PRs as a
  new surface to audit in the workflow.
- **Replace it with a local gate** — give up server-side enforcement, block it
  on the client instead.
- Defer and rely on habit.

## The decision

**Replace it with a local gate.** The only actors touching this repository are
the founder and the agents he starts, so the threat being defended against is
not a hostile contributor but "merged it anyway because waiting was annoying" —
and client-side friction genuinely stops that one. Neither publishing the repo
nor starting a subscription is worth that difference today.

Two pieces:

**1. `.githooks/pre-push` refuses a direct push to `main`.** This enforces the
PR flow itself. It needs `git config core.hooksPath .githooks` once per clone;
that line lives in README's Development section.

**2. `.claude/hooks/merge-gate.sh` blocks `gh pr merge` unless the checks are
green.** A Claude Code `PreToolUse` hook on Bash: when the command is a merge,
it runs `gh pr checks` against the PR that command would merge, and blocks with
the check output unless it exits 0. **It fails closed** — an unresolvable PR or
a `gh` error blocks rather than passes.

It matches a **command position**, not the words. The first version tested for
the substring anywhere in the command, and it blocked its own commit — the
message *described* the rule, and so would every notes edit that mentions it.
The gate now drops heredoc bodies (prose is not something the shell runs) and
requires the invocation to start a line or follow a shell separator. Both
narrowings can only ever reject text the shell would not execute as a merge.
`.claude/hooks/merge-gate.test.sh` pins the distinction with four invocations
and four mentions; a gate with no test is the same failure family it exists to
catch.

It is wired in **`.claude/settings.json`, which is committed**, not
`settings.local.json`. This is the repository's first committed agent-behaviour
setting, so the choice is worth stating: a gate that only exists on one machine
is not a gate, and the same reasoning that puts the rules in AGENTS.md rather
than in per-tool memory applies to the thing that enforces them. The cost is
that every agent session in this repo now runs a hook on every Bash call; the
hook exits 0 immediately for anything that is not a merge.

## What this gate does not cover

This is not a substitute for server-side protection; it is a thinner thing, and
the holes have to be written down or they become a boundary that is trusted
without existing.

- **A merge from the GitHub web UI is not blocked at all.** Both hooks only see
  commands run on this machine. The green Merge button in a browser ignores
  them, red checks and all.
- **Codex has no equivalent gate.** The workspace treats Codex as a first-class
  tool, and the threat model above says "the founder and the agents he starts" —
  but the merge hook is Claude Code's `PreToolUse` mechanism specifically. A
  `gh pr merge` from a Codex session is the same size hole as the web UI. The
  `pre-push` hook is git's, so that half does hold there.
- **`--no-verify` bypasses the push hook.** That is a deliberate act rather than
  an accident, which is the only reason it is acceptable.
- **A fresh clone has no pre-push hook until `core.hooksPath` is set.** Git does
  not clone hooks.
- **Git skips pre-push entirely when there is nothing to push** (observed while
  verifying this: `Everything up-to-date` never invokes the hook). No practical
  consequence, but do not assume the hook always runs.

## When to revisit

Either condition:

1. **The repository goes public or moves to a paid plan** — switch to branch
   protection immediately and delete the local gate rather than leaving the same
   rule stated in two places.
2. **A second person gets commit access** — the premise ("one person touches
   this") is what the whole trade rests on, and their machine has none of this.

Issue #36 closes with this record. Its real conclusion is not "branch protection
is configured" but "branch protection could not be configured, here is what
replaced it, and here is what is still open."
