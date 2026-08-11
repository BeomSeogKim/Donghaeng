# 2026-08-11 — AGENTS.md becomes a hierarchy, and gains an eviction rule

**Status**: decided. Amends the `Rules` section of the root `AGENTS.md`.
Related: `2026-08-08-decision-work-tracking.md` (the `notes/` = why split it
extends), `#85`.

## What was wrong

The root `AGENTS.md` had reached **908 lines / 56KB** with **zero domain code
in the repo**. `#8~` is "vertical, all of it" — fifteen-plus requirements, each
plausibly adding a rule. On the existing trajectory it reaches ~1,400 lines by
v1.

Size alone is not the argument. Three specific costs:

1. **Every session and every subagent read the whole file.**
   `backend-implementor` read its own 217-line prompt plus 908 lines before
   writing its first line, and ~130 of those lines (Design system, Frontend
   architecture) govern a tree it is forbidden to touch.
2. **The file did not hold to its own division of labour.** It states that
   `notes/` is *why* and `AGENTS.md` is the rule that binds — then carried
   multi-paragraph rationale that its `notes/` records already carried
   verbatim. The verified-email section ran 25 lines to state a 3-line rule;
   the `ErrorReportValve` section did the same.
3. **The append rule had no counterpart.** The old `Rules` section said
   decisions are "reflected here when they change standing rules" and never
   said what leaves, or when. Thirty individually-correct additions produced a
   file nobody would have written on purpose.

## The decision

### Three levels, lazily loaded

    AGENTS.md          — binds regardless of tree
    api/AGENTS.md      — binds inside api/
    web/AGENTS.md      — binds inside web/
    design/AGENTS.md   — binds inside design/
    notes/README.md    — the index of records

`CLAUDE.md` is a symlink to `AGENTS.md` at each level, per the workspace
convention, now applied per directory rather than once per repo.

The mechanism is Claude Code's nested-`CLAUDE.md` loading: a subtree's file is
picked up when files in that subtree are read. **The boundary was not chosen
for tidiness — it is chosen because it coincides with the agent boundary.**
`backend-implementor` and `frontend-implementor` already never cross it.

### One rule, one level

A rule states itself at exactly one level: both trees → root, one tree → that
subtree, **never both**. Two statements of one rule is how they drift, and the
old file already had drift of this shape (`.dh-num` vs `tabular-nums` was the
2026-08-08 instance, caught by hand).

Applying this removed real duplication in the split: `ambiguity is never
guessed`, `every mutation response carries the recomputed aggregate`, the
soft-delete stance and the deleted-guest-in-import question had each been
written twice.

### The admission test

> **A rule enters an `AGENTS.md` only if an agent would break it without the
> reminder. A *why* stays only if it stops a hand mid-violation.**

Worked both ways, because the line is not "short good, long bad":

- **Stays**: "we know a venue's child pricing exactly as well as we know its
  buffer — not at all." Without it an agent will helpfully fold 유아 인원 into
  the 보증인원 comparison, and a wrong number here is money.
- **Goes**: the glibc/ICU collation-invalidation story behind
  `lower(email collate "C")`. The rule alone — *use this expression, and use
  the same one in the lookup* — produces correct code. The story is why the
  expression is what it is, which is what `2026-08-11-decision-baseline-schema-
  calls.md` is for.

### A budget, checked

Root ≤ 350 lines, each subtree ≤ 280. Over budget, the next change compresses
or relocates **before** it adds. `wc -l AGENTS.md */AGENTS.md` is the check.

The numbers are not derived from anything; they are a ceiling chosen to force
the choice. The value is in the forcing, not the number — a rule that only ever
admits produced 908 lines once and will do it again.

## What this deliberately did not do

**No rule was deleted.** Every rule in the 908-line file is in one of the four
new files or in the `notes/` record it points at. This was a redistribution.
Losing a hard-won rule here is expensive and mostly unrecoverable, so the split
erred toward keeping text that a stricter reading of the admission test might
have evicted.

## Known gaps

- **Lazy loading is reliable for the main session and less so for a subagent's
  *first* action.** Closed deterministically by naming the subtree file in each
  implementor's prompt rather than relying on the mechanism.
- **`design/` is a fourth level, and the split there is authoring vs
  consuming** (added later the same day, at the founder's call). The system's
  own rules — thesis, palette, contrast, typography, component inventory,
  the build — are `design/AGENTS.md`; how Tailwind consumes them stays in
  `web/AGENTS.md`. The boundary is real rather than cosmetic: `text-gold`
  not existing is a fact about our `@theme` bridge, while gold measuring
  3.3:1 on porcelain is a fact about the colour.
  **This is the one place lazy loading does not reach the agent that needs
  it most** — `frontend-implementor` works in `web/` and so never touches
  `design/` on its own. Closed the same way as the other subagent gap, by
  naming the file in its prompt; noted here because it is the first case
  where the load boundary and the need boundary genuinely differ.
- **Root sits at 323 of its 350.** The remaining pressure is the
  `Standing product facts` section, roughly half of which is import rules that
  bind only when import is being built. Whether requirement-scoped domain facts
  belong in ambient context at all is **open** — moving them to their `notes/`
  record and the issue would cut ~40 lines, at the cost of an agent no longer
  meeting them by default. Not decided here.
