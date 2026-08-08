# Decision — development tempo and the comprehension gate (2026-08-08)

Decided immediately before scaffolding `web/` and `api/`, because it governs
how every unit of implementation work is cut, reviewed, and accepted from
here on.

The founder's stated goal: **minimize the cognitive debt of AI-written
code.** Not "review more" — the founder intends to keep an actual working
model of the backend, and wants the flow paced so that stays possible.

## Cut vertically by requirement, not horizontally by layer

The first proposal was to split a feature by layer — entity design, then
API contract, then service layer — pausing between each. Rejected, for two
reasons.

**Cognitive debt does not accumulate per layer; it accumulates in the seams
between them.** A slice touching four files leaves no debt if it can be
restated in one sentence ("참석을 탭하면 숫자가 그 자리에서 움직인다"). Four
layers approved separately but never seen working together leave the debt
in the joints — and the joints are exactly where an AI silently disagrees
with itself: each piece is plausible, and each assumed something different.

**A horizontal slice defeats both TDD and the review that matters.** There
is no Red Gate test for "entity design" — a test written there asserts
mapping mechanics, and passing tells you nothing about whether the product
behaves correctly
(`notes/2026-08-07-decision-backend-tdd-methodology.md`). And `reviewer`'s
second pass — the domain question, the one thing only the founder can
settle — cannot fire on a layer in isolation. "Is this the right response
when 보증인원 isn't set yet?" is invisible from the entity alone.

So: **one requirement = one Red/Blue/Green cycle = one review = one
explanation = one commit = one stop.** "웨딩 생성" and "웨딩 정보 수정" are
separate stops.

**One honest exception — the substrate.** Scaffolding, the Flyway baseline
schema, ProblemDetail/error-handling wiring, and session→membership→wedding
resolution are not requirements and cannot be cut vertically. These are
done horizontally, once, at the front, and the flow stops hard after them.
Everything after is vertical.

## Slice size is measured in new concepts, not lines

The founder reads at survey level, aided by an explanation document (below)
rather than by reading raw diffs. That moves the size constraint.

A raw diff is read in file order, which is not concept order, so past ~20
files it degrades into skimming — that is what would have forced ~100-line
stops. An explanation walks the change in **conceptual groups**, so the
binding constraint becomes how many unfamiliar ideas appear at once.

**One stop = one explanation = one or two new concepts.** A "웨딩 생성" that
also introduces session resolution, membership, and the first migration is
three concepts and gets split. A second CRUD endpoint following an
established pattern introduces zero and can be larger.

## The comprehension gate — Geoffrey Litt's explain-diff

Adopted from <https://gist.github.com/geoffreylitt/a29df1b5f9865506e8952488eac3d524>:
a self-contained document per change, structured **Background → Intuition →
Code → Quiz**, ending in five multiple-choice questions.

The quiz is the load-bearing part, and not as a study aid. It is a
**measuring instrument**: it separates "read it" from "understood it," which
nothing in the flow could distinguish before. A missed question names the
location of the debt, which makes it actionable instead of ambient.

### The explanation is written by an agent that did not write the code

**An implementor explaining its own change explains its intent, not its
code.** If the code has a bug, the document describes the bug-free version
the agent meant to write, and the founder passes the quiz with confidence.
That is worse than no gate at all: unknown debt becomes false confidence.

Hence a fifth agent, **`explainer`** (`.claude/agents/explainer.md`) —
read-only against the repo, given the diff and `notes/`, carrying none of
the implementation session's context. `reviewer` was considered for the job
and rejected: reviewer looks for what is wrong, explainer writes why the
code has the shape it has. Those are opposite postures, and combining them
turns the explanation into a list of review comments.

### Order

```
implementor → reviewer + security-manager → fix → explainer → founder reads + quiz → commit
```

The explanation is written **after** review findings are resolved, so it
describes verified code. An explanation of code that is about to change is
wasted reading.

### Tiered, so the ritual survives

Running the full gate on every stop costs enough that it would be abandoned
within weeks, and an abandoned ritual is worse than a correctly scoped one.

- **New concept present** → full explanation + quiz.
- **Established pattern repeated** (the fifth endpoint shaped like the
  first four) → `reviewer`'s report only, no explanation.

The implementor states which tier its stop is when it reports; the founder
overrides freely.

### Operational details

- **Korean prose, English identifiers.** The explanation is a human
  orientation artifact, not an agent-read document, so the workspace's
  English rule does not apply to it (same carve-out as
  `../../HANDBOOK.ko.md`).
- **Never committed.** It describes one diff and goes stale on the next
  one. Written as self-contained HTML to the session scratchpad and
  published as an Artifact — phone-readable, and matches the gist's
  single-file-with-inline-CSS/JS format.
- **Quiz options are shuffled deterministically**, so the correct answer
  is not always in the same position, and distractors are balanced in
  length — per the gist's own follow-up corrections.

## Still open

- Whether a missed quiz question triggers anything mechanical (a follow-up
  explanation, a re-slice) or stays the founder's judgement call. Left
  unmechanized until there are real misses to look at.
