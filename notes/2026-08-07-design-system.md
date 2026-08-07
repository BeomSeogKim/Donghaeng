# Design system — foundations (2026-08-07)

Built before screen design, at the founder's call. The reasoning that carried
it: the model changed substantially on 2026-08-06 — the response model was
dropped, groups were rewritten twice, meals became a typed axis — and a shared
visual baseline stops later work from drifting while that settles.

My initial read was the opposite: extract the system from the ledger screen
rather than invent it ahead. That read was wrong about *who* a design system
coordinates. It coordinates across **time**, not just across people. There is
one person on this project but many sessions, and a session that has to invent
a spacing scale invents a different one each time.

Tokens live in [`design/tokens.css`](../design/tokens.css) — that file is the
artifact, this note is the reasoning.

## The thesis: an instrument, not a celebration

The wedding is the occasion. The product is the steady hand — that is what 동행
names. Every visual decision below follows from one fact recorded on 2026-08-06:
**the thing we have to beat is a spreadsheet with a SUM in the next column.**

A spreadsheet is calm, dense, and legible, and it never lies about a number. It
loses only on effort — retyping, re-summing, no state per guest. So we do not
win by being prettier than a spreadsheet. We win by being *as calm as one* and
requiring less work. That rules out the wedding-stationery register — script
faces, blush pink, florals — which would read as marketing sitting on top of the
couple's real numbers, and would violate 정직함·믿음직함 before a single figure
was wrong.

Warmth therefore lives in the neutrals, not in decoration.

## Colour: 청실홍실

In a traditional Korean wedding the groom's 청 (blue) and the bride's 홍 (red)
are bound together — 청실홍실, the blue and red threads. It is the same image the
product's name already reaches for, and it hands us a palette whose origin is
specific to this product rather than to weddings in general.

- **청 `#24406b` — primary.** Deep, ink-like, slightly desaturated. 9.9:1 on
  white. Carries the headcount, primary actions, and the 참석 state.
- **홍 `#a3283c` — destructive only.** Appears almost nowhere in v1 (deleting a
  guest). Deliberately reserved rather than spent on decoration.
- **황 `#7a5510` — 미정 / attention.** The third traditional colour, and the one
  the ledger genuinely needs.

Neutrals carry a faint blue bias toward 청. A pure grey reads as inherited; a
grey pulled a few degrees toward the accent reads as chosen.

### 불참 is neutral, never red

The single most consequential colour decision, and it is a product decision
rather than a styling one.

Attendance is the most-coloured thing on the busiest screen. The reflex is a
traffic light — green 참석, red 불참. That is wrong here. **A guest who cannot
come is a fact, not an error.** Colouring it red makes the couple read their own
guest list as a column of failures, on a screen they will open a hundred times
in the months before the wedding.

So: 참석 = 청, 미정 = 황, 불참 = muted neutral. Red is spent on exactly one
thing — destroying data — which also keeps it unambiguous when it does appear.
Because 홍 and a conventional error red occupy neighbouring hues, **destructive
actions always carry a verb and never rely on colour alone.**

## Type: Korean-first, and the numerals are load-bearing

**Pretendard** is the app face. It covers Hangul and Latin on consistent
metrics, which matters on every screen here — guest names and Arabic numerals
share a line everywhere.

Two rules that are not stylistic preferences:

**The body floor is 15px.** Hangul packs more strokes into the em than Latin, so
13–14px running text loses its counters on a phone. 13px survives for metadata
fragments and never for a sentence. Korean running text also gets more leading
than Latin — 1.65 against a Latin-typical 1.5.

**Every digit that can change in place is tabular.** This is the typographic
expression of 정직함·믿음직함. With proportional figures, `245 → 246` shifts the
number's width, and a headcount that jitters as it counts reads as unstable —
on the one screen whose entire claim is that its numbers are not. Tabular
figures are a two-line CSS rule that buys the product's core promise.

No italics. Korean has no italic tradition and synthesised obliques look broken;
emphasis is weight and colour.

## Density: rows are flush, not cards

The ledger is 200–800 rows scanned on a phone. Rounding each row into its own
card — the reflex of every current component library — costs roughly 8px of
vertical rhythm per row and turns a scannable column into a stack of separate
objects. At 400 rows that is a different product.

So ledger rows are **edge to edge, separated by hairlines**, at 60px on mobile
(name line plus meta line, comfortably above the 44px tap floor) and 44px in the
desktop table. Radius is reserved for things that are genuinely detached:
chips, buttons, sheets. Elevation is one shadow, for overlays only — instruments
do not float.

## Where the system lives

Two places, and the split is deliberate:

- **This repo is the source of truth.** `design/tokens.css` is what the app
  compiles against; `design/components/parts/*.html` are the component sources;
  `design/components/build.py` inlines the tokens into standalone previews at
  `design/components/dist/`. Editing a token and rebuilding updates every
  preview, so the tokens are never transcribed twice.
- **claude.ai/design holds the rendered library** — project *Donghaeng Design
  System* (`c6d556a8-41df-418a-b072-64c5db3052da`), 13 cards under Foundations
  and Components. It is a view, not a store: anything decided there has to come
  back here or it does not survive.

Re-syncing is `python3 design/components/build.py` followed by a DesignSync
`finalize_plan` → `write_files` against `design/` as the local dir. Cards are
registered explicitly via `register_assets` because this bundle is
hand-authored — without the `/design-sync` skill nothing compiles the
`_ds_manifest.json` that `@dsCard` markers would otherwise feed.

The previews name Pretendard but fall back to the system Korean face:
embedding the 2.7MB webfont in each of 13 cards would cost ~30MB for a
rendering nicety. The published spec page carries the real face.

## The component inventory

Ten components cover every v1 screen, plus three foundation cards. This is
깔끔하되 핵심은 다 있게 applied to the system itself: fewer components, each
complete.

| # | Component | Why v1 needs it |
|---|---|---|
| 1 | `Button` | primary / secondary / ghost / destructive, two sizes |
| 2 | `Field` | text, number, select — label, hint, error |
| 3 | `GuestRow` | card on mobile, table row on PC — the split decided 2026-07-30 |
| 4 | `AttendanceControl` | 참석 / 미정 / 불참 — the most-used control in the product |
| 5 | `Stat` | the headcount against 보증인원 |
| 6 | `Tag` | group category and side |
| 7 | `Sheet` | guest detail, meal-count entry |
| 8 | `Toast` | mutation feedback, including failure after an optimistic tap |
| 9 | `EmptyState` | pre-import ledger, filtered-to-nothing |
| 10 | `ConflictRow` | the import review screen — the one screen still unsolved |

`ConflictRow` is built but deliberately incomplete. Its atom is settled — how
one conflict is shown, what the two choices are, and why each pair was flagged
— but **how the list behaves when one file arrives with forty conflicts is
not.** Judging forty one at a time makes a single list just another shape of
punishment; the certain ones have to fold away so only the genuinely ambiguous
few remain. That is the next knot in screen design, and the component carries
the note saying so.

## Two rules this system inherits from the architecture

**The number and the tap are on one screen, so the number must never move
backwards.** The 2026-08-06 review already forced the API shape — every mutation
response carries the recomputed aggregate, and the client tolerates out-of-order
responses. The design side of that: the count animates over 260ms while the chip
responds in 120ms, so the couple can see *which* number moved. A number that
lags the tap slightly is fine. A number that jumps without a visible cause is
not.

**Dark theme is defined, not shipped.** v1 is light only. Structuring the tokens
for both now costs nothing; retrofitting a theme later costs every component.

## Still open

- [ ] **Screen and flow design** — still the blocker, and still gated on the
      import conflict screen at scale.
- [ ] Whether 유아식 counts toward the venue's 보증인원.
- [ ] When the couple configures meal types.
- [ ] Retention policy for `GuestChange`.

Closed the same day: the components now exist as previews and are synced to
claude.ai/design. What is *not* yet built is any of this as React — that waits
for `web/`, and for the screens these components have to assemble into.
