# Decision — work tracking (2026-08-08)

Follows `notes/2026-08-08-decision-development-tempo.md` on the same day.
The tempo record settled how work is *cut*; this settles where the list of
uncut work lives. The founder's stated motivation was wanting the work
managed systematically, not merely recorded.

## Two things were being conflated, and one needs nothing

**A stop's progress through the pipeline** (implementor → review →
explanation → commit) starts and finishes inside a single session. A ticket
opened and closed the same hour is pure overhead. This needs no tool.

What is left is **the backlog** — the ordered list of requirements not yet
built. That was the only real question.

## GitHub Issues

Not Jira. Everything Jira does better than a plain list — workflows, epics,
sprints, reporting — is **team-coordination machinery**, and for one person
those are fields to fill. The disqualifying detail is narrower: agents
reach Jira only through an MCP hop, while `gh` is already in the
environment and already authenticated.

The first instinct was a file in the repo (`notes/backlog.md`), on the
grounds that it stays inside the single source of truth. That was the right
answer to the founder's *first* framing and the wrong one to the actual
goal. A file is weak in exactly the three places systematic management
needs: no stable ID to reference from a commit, no state that updates
itself, and unreachable away from the machine.

**The deciding argument:** a tracker rots when it demands an act of
discipline the work does not already produce. Editing a checkbox in a
markdown file is a separate act, and separate acts are the first thing
dropped under pressure. Writing `Closes #12` sits inside a commit message
that gets written anyway — so the state is a **by-product of the work**
rather than a chore beside it. That, not tooling quality, is why Issues
beat the file here.

## The single-source-of-truth line

The drift risk raised against Jira applies to any external tracker, so it
gets a rule rather than a hope:

- **`notes/` = why.** Decisions and their reasoning. Permanent.
- **Issue = what's left, and where it stands.** Perishable, derived.

**An issue never decides anything.** A rationale appearing in an issue body
that is not in `notes/` is a defect: the rationale moves to a record and
the issue links to it.

## Shape

- **One issue = one requirement, not one stop.** Stops are cut at build
  time by new-concept count, which cannot be known weeks ahead; a
  pre-enumerated list of stops would be wrong within a week and would
  itself become maintenance. So the plannable unit is the requirement, and
  stops are the commits that reference it.
- **Milestones: `v1`, `post-v1`.** Deferred work (RSVP links, the response
  model, seating, 축의금, dark theme) is filed rather than remembered —
  nothing is lost, and the working list stays clean.
- **Labels: `api`, `web`, `infra`, `open-question`.** Four, deliberately.
  Labels stop carrying information the moment they multiply. Priority is
  milestone plus issue order, not a label.
- **No Project board yet.** Ordering by issue number and milestone is
  enough until "what's next" is actually ambiguous — the same
  escalate-only-on-observed-pain rule the frontend architecture runs on.
- **No PRs yet.** Solo, with no CI, a PR opened and merged by the same
  person is ceremony. Revisit when CI exists.

## What it does on day one

The `open-question` label gives the four small undecided items a home with
a stable ID — the import file hash's owner, the 관계 synonym table's initial
contents, `GuestChange` retention, and whether 유아식 counts toward
보증인원. These had been living as a bullet list in AGENTS.md, which is
where small open questions go to evaporate. **Closing one requires writing
a `notes/` record**, which is the same rule as everywhere else here.

It also closes a loop the tempo decision opened: an implementor that stops
after the first concept reports what it left behind, and that report now
has somewhere to go instead of scrolling out of a session.

## Initial contents

35 issues filed the same day, derived from
`notes/2026-07-26-mvp-v1-requirements.md`,
`notes/2026-08-06-decision-v1-scope-and-meals.md`, and the eight-screen
table in `notes/2026-08-07-design-screens-and-flow.md`. `#1`–`#5` are the
substrate (scaffolding, baseline schema, ProblemDetail wiring, session
resolution) and are the one horizontal block; everything after is a
vertical requirement.
