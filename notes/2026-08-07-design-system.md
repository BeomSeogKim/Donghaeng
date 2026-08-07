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

## Colour: 백자 · 금박, and 나전칠기 for dark

### The first palette was wrong, and the ground is where it showed

The first version of this palette was 청실홍실 — the blue and red threads of a
traditional wedding — on a `#f4f5f7` ground. The founder rejected the
background, and was right: **the positioning is a high-end service for Korean
couples, and `#f4f5f7` is the default of every SaaS product ever shipped.**

The error was not the instrument thesis, which survives intact. It was
concluding that *restraint* meant *cheapness*. I derived "quiet" correctly and
then executed it with an inherited grey, which reads as unconsidered rather
than as chosen.

### What the research changed

Three findings from Joseon court and wedding dress, and each one moved the
palette:

**1. Colour in royal wedding dress was rank, not decoration.** The 원삼 was
colour-coded by station — 황원삼 (황후) → 홍원삼 (왕비) → 자적원삼 (비빈) →
초록원삼 (공주 · 옹주 · 반가부녀). The bride's 활옷 was 대홍색; so was the
king's 곤룡포 until 1897. So "a luxurious East Asian colour" is not a matter of
taste — it is a question of which rank's vocabulary to borrow.

**2. Gold was the one constant across every rank.** 금사 embroidery on the
king's 대홍 곤룡포, on the 황룡포, 금박 on the bride's 활옷. Colour changed with
station; gold never did. **The first palette had no gold at all** — and in this
tradition, luxury is carried by the presence of metal, not by saturated colour.

**3. Korea's whites are a family, and none of them is grey.** Joseon porcelain
moved 유백 → 설백 → 회백 → 청백 across the centuries, and hanji's white had its
own name, 지백색. All of them are warm or faintly tinted. The cool grey I used
is nowhere in that lineage — which is precisely what the founder was reacting
to.

### The palette

**Light — 백자 · 금박.** Ground is 유백색, the milky white of soft-paste
porcelain. Ink is 먹. Primary is **자적 `#73304e`**, the 비빈 rank colour —
borrowed rather than 대홍 because 대홍 has to stay available for destroying
data. Gold `#a8863f` appears as hairlines, the guarantee meter, and the brand
mark.

**Dark — 나전칠기.** 옻칠 ground `#14100e` with the iridescence of 자개 over it,
and **gold becomes the primary**. These are not two designs but one system's day
and night: 백자 and 나전칠기 are objects from the same house, so the vocabulary
never collides. Defined and shippable; **v1 still launches light-only**, because
a product scanned outdoors in daylight should not default to a dark ground.

### The gold asymmetry — the one rule a token alone will not tell you

Gold is **3.3:1 on porcelain and 7.8:1 on lacquer**. So in light it may never
carry text — hairlines and ornament only — and in dark it is the primary text
accent. The same token, opposite rules per theme. Anyone reading only the token
name will get this wrong, so it is stated on the colour card and here.

### 불참 is neutral, never red

The single most consequential colour decision, and a product decision rather
than a styling one. It survived the repalette unchanged.

Attendance is the most-coloured thing on the busiest screen. The reflex is a
traffic light — green 참석, red 불참. **A guest who cannot come is a fact, not an
error.** Colouring it red makes the couple read their own guest list as a column
of failures, on a screen they will open a hundred times before the wedding. And
it cannot be acted on: colouring an unactionable state as a problem is a lie
about what the user can do.

What *did* change: **참석 is now 초록**, the green of 초록원삼 — the robe of a
공주 or a 반가 bride, which is exactly who this product serves. The earlier
objection was to red-for-불참, never to green-for-참석, so adopting the
traditional green costs nothing and contradicts nothing.

So: 참석 = 초록, 미정 = 치자, 불참 = muted neutral, and 대홍 is spent on exactly
one thing — destroying data. Because 자적 (primary) and 대홍 (danger) are both
reds, **destructive actions always carry a verb and never rely on colour
alone**, and they are outlined rather than filled: the button that is easiest to
hit by accident must not be the heaviest thing on screen.

### Tokens are named for their role, never their colour

The first version used `--dh-cheong` / `--dh-hwang` / `--dh-hong`. One palette
change turned every one of those names into a lie. They are now `--dh-primary`,
`--dh-attention`, `--dh-danger`. A role name survives a repalette; a colour name
does not, and this palette changed within a day of being written.

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

### The display face is an open slot, and the number is why

The palette redesign raised the typeface question, because a gothic-only screen
does not carry "high-end" the way the porcelain-and-gold palette now asks it to.
The intended answer is a **pairing**: Pretendard keeps the UI, and a Korean
serif takes the headcount, screen titles, and the brand mark — a handful of
places, never the list. Korean serif at 15px in a 400-row column is measurably
harder to read than gothic, so the serif must stay quiet or it costs the density
the whole system was built around.

`--dh-font-display` exists as the slot. It currently aliases the sans, and the
headcount stays on Pretendard, because of one constraint that can veto the whole
idea: **the number must be tabular.** Free Korean serifs vary a lot in how much
attention their Latin figures got, and several have no tabular set at all. If
the chosen serif's digits are not tabular, the serif takes titles and the brand
mark only and the number stays gothic — **stability outranks elegance on the one
number this product exists to be trusted about.** That has to be measured, not
assumed, before the slot is filled.

Two more constraints on the choice, neither aesthetic: the face must be licensed
for commercial use (this ships to paying couples), and a second Korean family is
1–4MB, so the serif has to be subset to the glyphs actually used — which is
cheap here precisely because it appears in so few places.

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
