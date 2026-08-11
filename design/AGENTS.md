# design/ — the design system itself

Tokens, component sources, previews and fonts. **The root `AGENTS.md` still
binds**; this file carries what applies when you are **authoring the system**.
How the system is *consumed* in the app — the `@theme` bridge, the
design-value checker, Tailwind utilities — lives in `web/AGENTS.md` and is not
repeated here. Full reasoning: `notes/2026-08-07-design-system.md`.

    design/tokens.css              the only definition of every value
    design/components/parts/       component sources, one HTML file each
    design/components/dist/        built previews — generated, never hand-edited
    design/components/build.py     the builder; embeds font subsets
    design/fonts/                  the two faces, licences, measurements, subset.sh

The rendered library is mirrored to the claude.ai/design project *Donghaeng
Design System*. **That is a view, not a store** — anything decided there comes
back to this repo or it does not survive.

## The thesis

**동행 is an instrument, not a celebration.** The thing to beat is a
spreadsheet with a SUM in the next column, so we win by being as calm as one
and requiring less work — never by being prettier. That rules out the
wedding-stationery register entirely.

But **restraint is not cheapness.** Learned the hard way 2026-08-07: the first
ground was `#f4f5f7`, the SaaS default, and it did not carry the high-end
positioning at all. **Premium here is carried by material, not saturation** —
the same reason 백자 is prized for being *almost* white.

## Tokens

- **Named for their role, never their colour.** The first version used
  `--dh-cheong` / `--dh-hwang` / `--dh-hong` and one palette change made every
  name a lie. Now `--dh-primary` / `--dh-attention` / `--dh-danger`. A role name
  survives a repalette; a colour name becomes a lie the first time the palette
  moves.
- **`design/tokens.css` is the only definition of any value.** `web/` imports
  this file rather than copying it. A second copy is how the app and the
  library start disagreeing about what 자적 is.
- **Light is 백자 · 금박, dark is 나전칠기.** 유백색 ground, 먹 ink, **자적
  `#73304e`** primary (the 비빈 rank colour), gold as metal. Dark inverts to
  옻칠 ground with gold as primary. **One system's day and night, not two
  designs** — which is why both themes live in this one file and dark is never
  a retrofit. **v1 ships light only.**
- **Adding a token means adding it to both themes.** A token defined only under
  `:root` silently keeps its light value in dark.
- **Nothing hardcodes a colour, size, radius, or duration** — anywhere, in this
  directory or in `web/`. Everything reads a token. In `web/` this is enforced
  mechanically (`npm run check:design`); here it is enforced by you.

## Contrast is a measured fact, not a preference

- **Gold is 3.3:1 on porcelain and 7.8:1 on lacquer.** So in light it may
  **never carry text** — hairlines, meter, brand mark only — and in dark it is
  the primary text accent. **The same token, opposite rules per theme; a token
  name alone will not warn you.**
- **불참 is neutral, never red; 참석 is 초록** (초록원삼, the robe of a 반가
  bride). A guest who cannot come is a fact, not an error.
- **Red belongs to destroying data only** — and destructive actions always
  carry a verb and are outlined, not filled, because 자적 and 대홍 are both
  reds and a filled 자적 button reads as destructive at a glance.

## Typography

- **Body text never goes below 15px.** Hangul packs more strokes into the em
  than Latin. **13px is for metadata fragments, never sentences.**
- **Korean running text gets 1.65 leading**, not the Latin-typical 1.5.
- **No italics.** Korean has no italic tradition and synthesised obliques look
  broken.
- **Every digit that can change in place is tabular** — headcount, meal counts,
  축의금 later. This is 정직함·믿음직함 in typography: a number whose width
  shifts as it counts reads as unstable.
- **Two faces: Pretendard for UI, RIDIBatang for display.** RIDIBatang appears
  in **exactly three places** — the headcount, screen titles, the brand mark —
  and **never the list**: Korean serif at 15px across 400 rows is slower to
  scan.
- **Typeface candidates are decided by measuring the font, not by taste.** Arita
  Buri and Noto Serif KR were rejected for having no `tnum`, so they default to
  proportional figures and cannot carry the headcount; Song Myung and Hahmlet
  for omitting most of the 11,172 Hangul syllables, and this product's content
  is people's names. **Read `GSUB`/`hmtx` before recommending a face.**

## Layout

- **Ledger rows are flush, hairline-separated — never cards.** Per-row cards
  cost ~8px of vertical rhythm each and break scanning at 400 rows.
- **Radius is for things genuinely detached**: chips, buttons, sheets.

## Components

Ten components plus four foundation cards cover v1 — the inventory is
`design/components/parts/`, and it is a **closed set for v1**. Before adding an
eleventh, say which requirement it serves and why an existing part cannot carry
it. Search is a **Field variant**; the filter chips are a **Tag with a selected
state** — that is what kept the inventory at ten when search landed.

## Building

```
python3 design/components/build.py     # parts/ → dist/
design/fonts/subset.sh                 # regenerate subsets when preview text changes
```

- **`dist/` is generated. Never hand-edit it** — edit the part and rebuild.
- **The builder embeds the committed font subsets**, cut to exactly the
  characters the previews use, so a preview is one self-contained file.
- **`build.py` warns and names the character when a part renders something
  outside the subset.** That warning is the signal to re-run `subset.sh` and
  commit the result — not to ignore it, because the preview silently falls back
  to a system face and stops showing what it is meant to show.
- Licences and measurements are in `design/fonts/README.md`. Check the licence
  before adding a face.
