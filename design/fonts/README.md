# Fonts

Two faces, decided 2026-08-07. Rationale in
[`notes/2026-08-07-design-system.md`](../../notes/2026-08-07-design-system.md).

| Role | Face | Where it is used |
|---|---|---|
| UI / body | **Pretendard** | everything — list rows, labels, buttons, running text |
| Display | **RIDIBatang** | the headcount, screen titles, the brand mark. **Never the list.** |

## Why these two

**Pretendard** covers Hangul and Latin on consistent metrics, which matters on
every screen here because guest names and Arabic numerals share a line.

**RIDIBatang** was made by Sandoll for RIDI's e-book reader — designed for long
reading *on a screen*, which is closer to this product's condition than a
print-oriented brand serif. It was chosen over Gowun Batang and Nanum Myeongjo
(both of which also passed) on that provenance.

It was chosen **against** Arita Buri and Noto Serif KR, which were measured and
failed: neither has a `tnum` feature and both default to proportional figures,
so neither can carry a headcount that must not jitter. That measurement is the
whole reason the choice was made from font tables rather than from taste — see
the note.

## Measured properties

Read from the font tables, not assumed:

| Face | `tnum` | default figures | Hangul |
|---|---|---|---|
| Pretendard | yes | proportional (30% spread) | 11,172 |
| RIDIBatang | no | **tabular** | 11,172 |

RIDIBatang needs no `tnum` because its digits are already uniform-width. The
system rule (`font-variant-numeric: tabular-nums` on anything that changes in
place) still applies to both — it is what keeps the rule true if the face is
ever swapped.

## Files

- `Pretendard.subset.woff2`, `RIDIBatang.subset.woff2` — committed subsets, cut
  to exactly the characters the preview cards render (`charset.txt`). These are
  what `design/components/build.py` inlines.
- `.src-*` — the full upstream faces, kept so the subsets can be regenerated.
- `subset.sh` — regenerates the subsets. Only needed when preview text changes;
  `build.py` warns when a part uses a character outside `charset.txt`.

**`web/` will need its own subset** cut to the app's real strings, not these —
these are sized for the design-system previews only.

## Sources and licence

- **Pretendard** — <https://github.com/orioncactus/pretendard>, SIL Open Font
  Licence 1.1. Redistribution permitted.
- **RIDIBatang** — <https://ridicorp.com/ridibatang/>, free for personal and
  commercial use, modification and redistribution permitted; **selling the font
  itself is prohibited.** Bundling it with the product is explicitly allowed.
  (The official download blocks automated fetching; it was retrieved by hand.)

Both licences permit shipping the face inside the product, which is what the
web client will do.
