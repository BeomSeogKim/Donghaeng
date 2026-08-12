---
name: stop
description: 스톱 하나를 끝까지 진행한다 — 구현 → 리뷰 → 설명 → PR. Runs one development stop end to end for a GitHub issue: branch, delegate to the implementor, file leftover concepts, run reviewer and security-manager, drive the fix loop, publish the explainer document, and open the PR. Use whenever a requirement is picked up ("#37 시작", "스톱 시작", "이 이슈 진행해줘") and whenever a stop is resumed mid-flight.
argument-hint: <issue-number>
---

One stop = one requirement = one branch = one PR = one commit
(`notes/2026-08-08-decision-development-tempo.md`,
`notes/2026-08-08-decision-build-workflow.md`). This skill is the procedure.
The *rules* it obeys live in `AGENTS.md` and the tree files; do not restate
them here, and do not let this file decide anything.

**Why this is a skill and not an agent**: subagents cannot spawn subagents, so
the orchestration can only run in the main loop. **You never write `api/` or
`web/` code yourself** — that is what the implementors are for, always.

## 0. Read the issue

```
gh issue view $1 --json number,title,body,labels,milestone,comments
```

Read every `notes/` record the issue names, plus any it must *respect* that it
does not name. If the issue's rationale is not in a record, that is a bug in
the issue — move it to a record before building.

**If it is a cross-seam parent**, create the two children now (`api` first —
the spec is the seam), and run this skill on the backend child. Children are
created when the parent is picked up, never up front.

**Resuming?** If a branch for this issue already exists, read its PR body
checklist (§7) — that is where a stop's position lives, not in anyone's head.
Pick up at the first unchecked line.

## 1. Branch

```
git checkout main && git pull
git checkout -b <label>/<slug>          # label = api | web | infra | docs
```

## 2. Delegate

Route by tree — `backend-implementor` for `api/`, `frontend-implementor` for
`web/`. Hand it:

1. what to build or change,
2. **the domain facts the notes don't already carry** (ask the founder if you
   don't have them — this is where the biggest corrections come from),
3. anything the other side already depends on.

Require back: a summary, **the exact `docs/api-spec.md` delta** (backend), the
**tier** it thinks its stop is, and **what it left for the next stop.**

## 3. File the leftovers — immediately, before anything else

The implementor's leftover-concepts report is the intake path for most new
work. Each becomes an issue *now*; a leftover carried in a message is lost at
the end of the session.

```
gh issue create --title "..." --label <api|web|infra> --milestone v1 --body "... Refs #$1"
```

## 4. Review — both, in parallel, on `main...<branch>`

Always `reviewer`. Add `security-manager` when the diff touches **auth,
sessions, tokens, native SQL, wedding-scoped queries, email parsing, rate
limits, logging, or secrets** — which is most backend stops. Both are
read-only and cold by design; spawn them in one message so they run together.

## 5. Fix loop

**Continue the same implementor with SendMessage — do not spawn a fresh one.**
A new implementor has lost the Red/Blue/Green context of its own stop and will
re-derive it wrong.

An implementor may **push back on a finding once**, in writing, stating why it
is wrong. If the reviewer holds, stop and let the founder settle it. Silent
capitulation and silent dismissal look identical in a report — surface the
exchange either way.

Re-run whichever reviewer had findings. Do not proceed while any finding is
open or unanswered.

## 6. Explain — last, cold, and only if it earns it

Tier is the implementor's proposal and the founder's call:

- **new concept** → `explainer` runs. It gets the diff and `notes/`, never this
  session's context.
- **established pattern repeated** → `reviewer`'s report is the gate; skip.

`explainer` returns a path to an HTML file in the scratchpad. **That location
is disposable and this founder works across three machines**, so:

1. **Read the file completely** before publishing it — you did not write it.
2. Publish it with the `Artifact` tool (private; load `artifact-design` first).
3. `gh issue comment $1 --body "설명 문서: <url>"`

The URL is what makes the issue an index of explanations. A scratchpad path is
not.

## 7. PR

```
gh pr create --title "..." --body "..."
```

The body is the stop's **durable state** — a stop spans days and sessions, and
this checklist is the only thing that survives them:

```markdown
Closes #<issue>

## Stop
- tier: new concept | established pattern
- [ ] implementor done
- [ ] reviewer resolved
- [ ] security-manager resolved (or: not applicable — no named surface touched)
- [ ] explanation published: <url>
- [ ] founder quiz
```

**One keyword closes exactly one issue.** `Closes #33, #35` closes `#33` and
silently leaves `#35` open. Repeat the keyword or split across lines.

## 8. Stop here

The founder reads the explanation, takes the quiz, and merges. **Merging is
never yours.** A red check is never merged — the hook enforces it, so do not
work around a red check, fix it.

## 9. After the merge

```
gh pr view <n> --json body -q .body | grep -oE '#[0-9]+'
gh issue view <n> --json state
```

Verify **every** issue the PR named actually closed. This is the failure the
tracker mechanism was chosen to avoid, and nothing goes red when it happens.
