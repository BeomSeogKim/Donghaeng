# Decision — how the design-value rule gets enforced (2026-08-10)

The design system's rule is *"nothing hardcodes a colour, size, radius, or
duration"* (`notes/2026-08-07-design-system.md`). This note records how that
rule became machine-checkable, why the checker is hand-written rather than a
linter rule, and one scope call the founder made. Issue #45.

## What was already enforced, and by what

Three of the four were **already** mechanical before this stop — and not by a
linter. `web/src/index.css` clears each Tailwind namespace to `initial` in the
`@theme` bridge, so `bg-slate-100` and `rounded-lg` do not exist and a
hardcode fails to compile. That is a stronger guarantee than a lint rule: it
is the build refusing, not a checker complaining.

Two holes, both named in `AGENTS.md` before this stop:

1. **Tailwind v4 has no duration namespace**, so `duration-300` compiles no
   matter what the bridge does.
2. **Arbitrary-value syntax escapes the bridge entirely** — `bg-[#ff0000]`,
   `text-[13px]`, `rounded-[4px]` compile regardless.

So the deliverable was never "add a linter". It was: make the fourth value
machine-enforced, and close the arbitrary-value hole in the other three.

## The linter: Biome, and that was not a preference

The obvious 2026 default is ESLint + typescript-eslint + Prettier. **It is
uninstallable in this repo**, and the reason is worth writing down because it
will look like an unexplained deviation otherwise:

- `web/` pins `typescript@^7` — the native port. Its package exports only
  `./lib/version.cjs` and an `./unstable/*` surface. There is no compiler API:
  `require('typescript').createSourceFile` is `undefined`.
- typescript-eslint's `typescript-estree` needs that API, and its peer range on
  both `latest` and `canary` is `typescript: >=4.8.4 <6.1.0`.
- ESLint core has no TypeScript language of its own.

Biome ships its own parser and never touches the `typescript` package. The
alternatives were oxlint (same category, thinner ruleset, no formatter) and
aliasing a second TypeScript 5 into `node_modules` purely to feed the linter —
i.e. linting against a compiler the build does not use. Biome, recommended
preset, matching the `api/` precedent where ktlint's default ruleset was
chosen to remove a class of bikeshedding rather than as a design decision.

**Known cost, filed as #71:** Biome has no type-aware rules — no
`noFloatingPromises`, no `noMisusedPromises`. Mutation flows are the mandatory
-test area, and a floating promise in a mutation handler swallows its
rejection silently.

## The rule is a hand-written checker, and it had to be

`web/scripts/design-values.mjs`, not a Biome plugin. Biome's only extension
point is GritQL, whose regexes run on Rust's `regex` crate, which has **no
lookaround**. The rule needs "flag `[...]` **unless** its content is
`var(--dh-*)`", and that exception is load-bearing: `border-[var(--dh-gold)]`
must pass, because gold is deliberately absent from the `--color-*` namespace
(`notes/2026-08-07-design-system.md` — gold is 3.3:1 on porcelain and 7.8:1 on
lacquer, so `text-gold` must not exist). The exception cannot be expressed, so
the rule cannot live there.

**This is the note's load-bearing sentence:** a hand-rolled checker sitting
next to a linter looks like duplication, and a future session will want to
fold it in. It cannot be folded in. The linter cannot express the rule.

## The founder's call — design values only, layout passes

**Only utility prefixes that carry a design value are checked.**
`grid-cols-[1fr_auto]`, `z-[60]`, `aspect-[4/3]`, `content-['']`,
`bg-[url(…)]` and `translate-*-[…]` pass.

The rule names *colour, size, radius, duration*. A grid track ratio and a
stacking index are none of those, and `content-['']` is an incantation rather
than a value. The ledger row is specified as a flush hairline-separated grid,
so `grid-cols-[1fr_auto]` is close to inevitable on the first real screen —
refusing it would have produced a wave of suppressions on day one, and **a
gate people route around is worse than no gate.**

**Membership is decided by prefix, and a prefix qualifies only if
`design/tokens.css` has a token family behind it.** That is what makes the
list checkable instead of arguable: "is `100dvh` a size?" is a debate, "is
`min-h` on the list?" is not. A prefix with no family behind it has nowhere to
send the call site, which is why `max-*` and `basis` are off, and why
`delay-<n>` is off despite having the identical shape to `duration-<n>`
(filed as #73 — the order there is token first, prefix second).

## Viewport units are a value-shape exception, not a carve-out

`--dh-tap-min` (44px, the floor under everything tappable) is a token by
exactly the membership test, but it is only ever written `min-h-[44px]`. The
first cut excluded `min-*` wholesale so that `min-h-[100dvh]` could pass — and
that left the accessibility floor unenforceable while `h-[61px]` was caught.

Resolved without reversing the scope call: **`min-h`/`min-w` are on the list,
and viewport units are a value shape that passes** — the same mechanism
`url(` already used. `100dvh` is a relationship to the device, not a step on
any scale, and could never be a token. So the exception carries a reason
instead of the carve-out carrying a hole.

The regex is anchored at both ends, and that anchor is the whole thing:
unanchored, `min-h-[calc(100dvh-44px)]` would launder the hardcoded 44px
sitting beside the legal viewport unit.

## What the four review rounds established

Every round found the same species of defect — **the rule's reach was narrower
than its claim, and its intent was never wrong once.** Recorded because the
species will recur the next time a written rule is turned into a checker:

- `duration-300!` escaped. The script stripped a *leading* `!` (Tailwind v3);
  v4 moved important to the trailing position, so **the canonical spelling was
  the one that got through**.
- `bg-(--brand-blue)` escaped. The `(--var)` shorthand was validated only for
  `duration`, so the same meaning was caught in two of three syntaxes.
- A hoisted class table (`const styles = { on: 'duration-300' }`) escaped
  entirely — and **a test asserted that hole as intended behaviour**. Fixed by
  scanning every string literal and judging one only if it looks like a class
  list, which required a real JS lexer.
- The suppression marker was matched against raw source, so
  `design-values-ignore:` inside a *string* silently disabled the gate — and
  the checker's own output printed that string.

Two operational rules came out of it and now hold:

- **A gate that checked nothing fails.** `check-design-values.mjs` exits
  non-zero when it scanned zero files, matching `check-tokens.mjs`.
- **Warnings are errors in CI.** `biome check` exits 0 on warning-severity
  diagnostics, so the invocation carries `--error-on-warnings`.

## One thing the checker cannot see, and one it nearly caused

- **A class name assembled at runtime** (`` `duration-${ms}` ``) is invisible.
  Tailwind does not compile such a class either, so today it is a silent
  dead-class bug rather than a hardcode. Filed as #72.
- **The illegal test fixtures were being compiled into the shipped
  stylesheet.** Tailwind scans the whole project, so `#ff0000` from
  `web/scripts/` really appeared in `dist/assets/*.css` — adding this check
  would have injected hardcoded colours into production CSS. Closed with
  `@source not '../scripts'` in `src/index.css`.

## Residual, accepted knowingly

The class-list heuristic can fire on prose: an all-lowercase-ASCII literal
whose every token is class-shaped and which quotes Tailwind syntax —
`'use duration-300 here'`. Korean copy is **structurally** immune rather than
luckily so: one Hangul syllable disqualifies the whole literal, because the
shape test whitelists ASCII class characters and requires every token to pass.
The escape hatch (`// design-values-ignore: <reason>`, read from comments
only, reason mandatory) exists for this residual.
