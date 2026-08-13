# Decision — the stop pipeline is removed (2026-08-13)

**Supersedes** `2026-08-08-decision-development-tempo.md` in full, and the
procedural half of `2026-08-08-decision-build-workflow.md`.

Decided immediately after `#37` shipped. The founder's words: the stop
concept is not needed; keep it simple.

## What is removed

The whole orchestration, not one part of it:

- **`/stop` as a ritual** — `.claude/skills/stop/` is deleted.
- **The vertical-slice rule** — no standing requirement that a change be one
  requirement cut vertically, or that slice size be counted in new concepts.
- **The mandatory review pipeline** — `reviewer` and `security-manager` no
  longer run as a required step before every commit.
- **The comprehension gate** — no explanation document, no quiz, no tier.
  `.claude/agents/explainer.md` is deleted; it existed only for this.
- **"One stop = one branch = one PR"** — a branch is now sized by judgement.

## Why

**`#37` is the evidence, and it cuts both ways.**

The pipeline caught real defects that nothing else would have: an argument
resolver that let `?id=42` become an identity, a logout that completed a
session-fixation takeover, a revoke a concurrent read could silently undo,
three architecture rules that were inert, and two rule files asserting
guarantees nothing provided. None of those were visible in a green suite.

And it cost ten commits, six review rounds, forty-eight findings and two
published documents for **one login endpoint**. The ritual did not cause that
— the slice was cut badly and then grew — but a process whose ceremony
survives a bad slice by absorbing it is a process that hides bad slices.

The founder's original goal was to **minimize the cognitive debt of
AI-written code**. That goal is not retracted. What is retracted is the claim
that a fixed sequence is how to reach it. A ritual that has to be invoked is
also a ritual that can be performed while the thinking it stands for does
not happen.

## What survives, and where it now lives

Nothing below depends on the pipeline, and none of it is loosened:

- **The guards in `.claude/hooks/` and `.githooks/pre-push`** — mechanical,
  fail-closed, tested in CI. They were never part of the ritual
  (`2026-08-08-decision-merge-gate.md`).
- **`notes/` is why; an Issue is what's left** — unchanged
  (`2026-08-08-decision-work-tracking.md`). Records, milestones, the four
  labels, and `open-question` closing only by a record all stand.
- **A red check is never merged, and merging is the founder's** — unchanged.
- **`docs/api-spec.md` is the seam**, written in the same change as the code —
  unchanged.
- **The implementors still own their trees.** `backend-implementor` writes
  `api/`, `frontend-implementor` writes `web/`, and the main loop writes
  neither. That is about code ownership, not tempo.
- **`reviewer` and `security-manager` remain available**, invoked when a
  change warrants it rather than on every commit. A change touching auth,
  sessions, tokens, native SQL or wedding-scoped queries is the obvious case;
  `#37` is the standing evidence that on those surfaces a green suite is not
  a verdict.

## What this costs, stated plainly so it is not discovered later

**The founder no longer has a mechanism that guarantees they understand a
change before it merges.** The explanation documents twice caught prose that
contradicted correct code — a stale `90 days` in the seam spec, and a test
comment the record had deliberately corrected — and both had survived four
review passes. A cold read finds what a review of *the change* structurally
cannot, because to a reviewer holding the change in mind, the wrong sentence
reads as the thing it meant.

That capability is now gone. If the debt shows up — as a founder who cannot
say what a subsystem does, or as a bug that traces to a change nobody
understood — this record is where the trade was made, and reinstating a
lighter version of the gate is the obvious response.
