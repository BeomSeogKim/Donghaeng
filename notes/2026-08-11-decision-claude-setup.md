# 2026-08-11 — The Claude Code setup gets a shape

> **PARTLY SUPERSEDED, 2026-08-13** —
> `2026-08-13-decision-drop-the-stop-pipeline.md`. The `/stop` skill and the
> `explainer` agent are deleted, so every passage placing a behaviour *in the
> skill* no longer applies. The layering itself — hook vs prose vs record,
> and prompts carrying own-behaviour rules only — still binds.

**Status**: decided. Extends `2026-08-11-decision-agents-md-hierarchy.md`,
which did the same job one level down. Related: `#87`.

## What was wrong

The setup was five subagents, two hooks and nothing else. No skills, no
commands, no `permissions`, one hook event out of eleven. The founder's read —
"we're not really using any of this" — was correct.

But the interesting failure was not the empty directories. It was that **the
one thing the whole project runs on was written down nowhere**: the per-stop
ritual — implementor → reviewer + security-manager → fix → explainer → founder
quiz → commit — lived only in the main loop's head and the founder's. Fifteen
vertical slices were about to run through it.

## The decision rule

Two questions place any behaviour:

1. **When does it bind — at a nameable moment, or unpredictably while writing
   anything?**
2. **At that moment, is compliance mechanically checkable, or does it need
   judgment?**

| | mechanically checkable | needs judgment |
|---|---|---|
| **nameable moment** | hook (or CI) | skill |
| **unpredictable** | — | `AGENTS.md` prose |

Plus: needs a *different context window* → subagent. Why and history →
`notes/`.

**Prose is the residual category, not the default.** It is the most expensive
medium — paid on every turn of every session — and the only one that can rot
without anything going red. Anything in prose with a nameable trigger is
mis-shelved.

## What that produced

### `/stop` — the ritual becomes a skill

`.claude/skills/stop/SKILL.md`. Issue → branch → delegate → file the leftover
concepts → review (both, parallel, on `main...<branch>`) → fix loop → explainer
→ PR → merge → verify closure.

**The shape is forced, not chosen.** Subagents cannot spawn subagents, so
orchestration can only run in the main loop; a sixth "orchestrator" agent is
structurally impossible. A hook cannot run a judgment-laden procedure. Prose
had already demonstrated it rots. A skill is the only durable home, and it
loads at exactly the moment it binds.

Two details earn their place:

- **The fix loop continues the same implementor via `SendMessage`, never a
  fresh spawn.** A new implementor has lost the Red/Blue/Green context of its
  own stop and re-derives it wrong.
- **The PR body checklist is the stop's durable state.** A stop spans days and
  sessions; its position previously lived in someone's head.

### The agent prompts lose their copy of the rules

The hierarchy decision established "a rule states itself at exactly one level,
never both" — and then left five prompt files restating dozens of rules
verbatim. **The 403/404 rot found that same day happened in exactly this
layer**, and the layer survived the restructure intact.

Prompts now carry role, boundaries, *own-behaviour* rules (stop sizing, spec in
the same change, pushback-once, report format) and pointers. Everything about
the code lives in the tree files alone. 782 → 539 lines, and the restatement
probe goes to zero.

The counter-argument — a subagent's first action may precede lazy loading — 
justifies *naming* the tree file in the prompt, which the prompts do. It never
justified copying its contents.

### The explainer document stops evaporating

`explainer` wrote its HTML to the session scratchpad while `AGENTS.md` claimed
the issue comment linking it made "the issue the index of explanations." The
scratchpad is disposable and the founder works across three machines, so that
index was going to be a list of dead links. Nobody noticed because zero
explanations exist yet; the first fifteen are imminent.

`/stop` now reads the file, publishes it as an Artifact, and comments the URL.

### Two prose checks become real checks

- **`closes-guard.sh`** — `Closes #33, #35` closes `#33` and silently leaves
  `#35` open. Found by hand on `#83`, after the fact. The test is not "more
  than one issue on the line" (`Closes #33, closes #35` is the correct fix) but
  **more issue references than closing keywords**, which is exactly what GitHub
  drops. Note the inversion versus `merge-gate.sh`: that hook strips heredoc
  bodies because prose is not something the shell runs; here the heredoc body
  *is* the subject, so the whole string is scanned and the gate moves to the
  command being one that writes a message.
- **`agents-budget.sh`** — the budget was written into `AGENTS.md` the same day
  the 908-line file was split, and nothing ran it. That is the 908-line file's
  own failure shape, one level up. Now enforced from `.githooks/pre-push` and
  CI. A budgeted file that has *vanished* fails too, or deleting it would be
  the cheapest way to satisfy its budget.

### `permissions`, finally

`settings.json` held only hook registrations. Fifteen slices of `./gradlew
test` and `npm run lint` would either prompt constantly or **train reflexive
approval — and reflexive approval makes the deliberately-leaky guards the only
line of defence.** Build/test/lint and read-only `gh` are allowed; `gh pr
merge` is `ask`, because merging is the founder's.

Deliberately not added: a `deny` for `git push --no-verify`. Flag-order
wildcards are fragile enough that the rule would read as covered while not
being, which is worse than its absence.

## Facts that shaped this, from the official docs

- **Target under 200 lines per CLAUDE.md.** Root now sits at 220 with a whole
  product's standing truth in it; the budget is set there deliberately.
- **`.claude/rules/*.md` with `paths:` globs is real** lazy loading, finer than
  directories. **Not adopted**: it is Claude-only, and the workspace requires
  `AGENTS.md` to be the one source every agent reads, Codex included. The
  boundary is now explicit — rules in `AGENTS.md`, Claude-specific wiring in
  `.claude/`.
- **Only the project-root `CLAUDE.md` is re-injected after `/compact`.** Nested
  files and path-scoped rules are not; they reload when a matching file is next
  read. So the four-level hierarchy has a compaction hole. It is mostly closed
  by the delegation architecture — subagents get a fresh context and re-read
  their prompt, which names the tree file — and by a line in the root file
  telling a compacted session to re-read. Worth knowing rather than
  restructuring for.

## What was deliberately not built

- **No MCP servers.** `gh` covers the tracker; the project already closed "no
  Jira, no board".
- **No orchestrator subagent.** Structurally impossible; `/stop` is the organ.
- **No ritual-enforcement hook.** A hook demanding quiz completion is theatre —
  the founder is present at merge, and the PR checklist makes a skipped step
  visible to the one person the gate serves. Hooks are for rules that must
  survive the founder's *absence*; the comprehension gate only means anything
  in their presence.
- **No semantic drift linter.** An AI job checking "does the prose still match
  the code" produces confidence, not correctness. The real fix for drift is
  structural: shrink the restatement surface to zero, which is what the prompt
  cleanup did.
- **No statusline, output styles, plugins, or more subagents.** Cost without
  return at n=1.
