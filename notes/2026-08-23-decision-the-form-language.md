# Decision — the design system gains a form language (2026-08-23)

The founder rejected two rounds of screens as "고급스럽지 않다". The first
response was bigger type and more whitespace; the second added a paper-grain
texture, a gold hairline and a 자적 panel on 로그인. Both failed, and this
record says why, because the reason is a hole in the system rather than a
matter of taste.

The system was asked about directly — `codex` and `grok` were each given
`design/tokens.css`, `design/AGENTS.md` and the artboard sources, and reached
the same finding independently.

## The finding: this was a system in colour and meaning, and a mood board in form

`2026-08-07-design-system.md` settled a palette, a type ramp, a density and a
contrast rule. What it never settled was **shape**. The only form the tokens
actually forced was this:

    --dh-radius-control: 0.5rem     Tailwind's default
    --dh-radius-chip: 999px         a pill
    --dh-line / --dh-line-strong    both 1px; only the colour differs

백자, 비빈 rank, 초록원삼 live in the names and the prose. The hand that draws
a screen follows `8 / 1 / 999`, and that hand is indistinguishable from Linear's
or Notion's. **That is why raising the type and the whitespace produced an empty
SaaS instead of a premium one** — the grammar underneath never changed.

A second measurement made it concrete. Gold's area on the ledger screen was
1440px² of full-width decorative rule, 600px² of meter, 14px² of brand tick.
**The material was ornament by area, not by rule.** A gold hairline laid on top
of a beige hairline makes gold the beige line's decoration.

## What is now regulated

The direction is **기물 (器物)** — the screen is an object: a 백자 slab on a
보자기 ground, its outer edge a gold 구연, its 굽 the 자적 面 the headcount
lives in. Three candidates were drawn and the founder chose this one; the two
unchosen (각인 — the table alone; 원삼 — a 자적 band wearing the brand) are
kept on the canvas so a later reader can see what was traded away.

The rules, and each one is a deletion before it is an addition:

- **Radius is 0** for the table, fields, filters and buttons. `8px` is deleted
  from the system. A full circle survives only as a brand mark.
- **There is no pill.** 참석 is a word in a 48px column, not a badge. The
  capsule was 30×60 against a 44px row — 62% of the row height, repeated 400
  times, and it alone read the ledger as an issue tracker. **The tap target is
  the whole row**, which is what makes the badge unnecessary rather than
  merely smaller.
- **There are no horizontal row rules.** Separation is the 44px row plus 17px/500
  name against 13px/400 metadata. What replaces them is a **vertical 괘선**
  (`1px #eae5db`) between column groups — the ruling of a Joseon ledger, and the
  first thing in this system whose *form* is Korean rather than its name.
- **Column gaps are uneven** — 32 / 16 / 24. Even gaps are a spreadsheet.
- **Three type voices per screen**: the headcount (RIDIBatang, the only display),
  the name (17px/500), the apparatus (12–13px/400). **The ledger's screen title
  is deleted** and the 결혼식 이름 becomes a 13px running head: a second display
  face makes the headcount look smaller and tips the screen toward stationery.
- **Gold has exactly three jobs** — an 인장 (a 2px rule the width of the
  wordmark), the 구연 (the slab's 2px outer edge), and the 기준선 (a 10px meter
  面 filling toward 보증인원). Never a full-width rule, never text in light.
  The rule that gold may not carry text in light stands; what changes is that
  it now has area.
- **The paper grain is deleted.** At 0.05 it is invisible and above that it is
  stock paper. 백자 is prized for the thickness of its 구연, not for noise.

## What this costs

`--dh-radius-control: 0` changes every button and field in `web/` at once, and
deleting the chip radius changes `Tag.tsx` and `GuestRow.tsx`. This is not a
value to push in quietly; it is the reason the change is split across issues
rather than applied as one edit.

`#177` — the withdrawn three-state attendance control still drawn in the parts
library — is subsumed here: the control it draws no longer exists in any form.
