/*
 * The design system's fourth rule, machine-enforced.
 *
 * "Nothing hardcodes a colour, size, radius, or duration"
 * (notes/2026-08-07-design-system.md). Three of the four are already enforced
 * without a linter: the @theme bridge in src/index.css clears every Tailwind
 * namespace to `initial`, so `bg-slate-100`, `text-sm` and `rounded-lg` do not
 * exist and a hardcode fails to compile.
 *
 * Two holes survive that, and this file is both of them:
 *
 * 1. Tailwind v4 has no duration namespace to clear, so `duration-300`
 *    compiles. Call sites must reach for the token: `duration-(--dh-dur-count)`.
 * 2. Arbitrary-value syntax bypasses the namespaces entirely. `bg-[#ff0000]`,
 *    `text-[13px]`, `rounded-[4px]` and `duration-[300ms]` all compile no
 *    matter what the bridge cleared. So does the CSS-variable shorthand
 *    `bg-(--brand-blue)`, which names a variable from outside tokens.css.
 *
 * WHERE IT LOOKS. Not at `className=` attributes — a class written once and
 * reused is exactly where a duration or a radius gets hardcoded:
 *
 *     const styles = { on: 'duration-300' }   // <div className={styles.on} />
 *
 * So it reads *every* string literal in the file and judges the ones that look
 * like a class list. Tailwind's own scanner works the same way, and that is the
 * bar: anything Tailwind will compile into the stylesheet is in scope.
 *
 * WHAT IT CANNOT SEE. Judging by shape means an all-lowercase English sentence
 * containing a hyphenated word can be mistaken for a class list, and a class
 * name assembled from fragments at runtime (`` `duration-${ms}` ``) is invisible.
 * The escape hatch for the first is a `design-values-ignore:` comment with a
 * reason; there is no answer to the second, and there is no answer in Tailwind
 * either — it does not compile such a class at all.
 *
 * Scope: class names. This does not read CSS — design/tokens.css is where
 * literal values are *supposed* to live, and src/index.css is checked by
 * scripts/check-tokens.mjs instead.
 */

/*
 * WHICH UTILITIES CARRY A DESIGN VALUE.
 * Scope and rationale: notes/2026-08-10-decision-design-value-enforcement.md.
 *
 * What a reader needs here: judgement is by *prefix*, and a prefix is on this
 * list because design/tokens.css has a token family behind it. Each entry names
 * its family. Matching is by segment, so `border` covers `border-t` and `gap`
 * covers `gap-x`.
 */
const DESIGN_PREFIXES = [
  // --dh-ground / surface / ink / line / primary / gold / attention / danger /
  // focus / att-*, reaching the utilities through @theme's --color-*.
  'bg',
  'border',
  'divide',
  'outline',
  'ring',
  'fill',
  'stroke',
  'caret',
  'accent',
  'decoration',
  'placeholder',
  'from',
  'via',
  'to',
  // --dh-shadow-overlay.
  'shadow',
  // --dh-text-* (size) and the colour family both land on `text`.
  'text',
  // --dh-font-sans / --dh-font-display, and --dh-weight-*.
  'font',
  // --dh-leading-*.
  'leading',
  // --dh-tracking-*.
  'tracking',
  // --dh-radius-*.
  'rounded',
  // --dh-space-* — the 4px grid, which @theme derives from --dh-space-1.
  'p',
  'px',
  'py',
  'pt',
  'pr',
  'pb',
  'pl',
  'ps',
  'pe',
  'm',
  'mx',
  'my',
  'mt',
  'mr',
  'mb',
  'ml',
  'ms',
  'me',
  'gap',
  'space',
  'inset',
  'top',
  'right',
  'bottom',
  'left',
  'indent',
  'scroll-m',
  'scroll-p',
  // Height has families of its own: --dh-row-h-mobile / --dh-row-h-desktop (row
  // height is a product decision, not a free number) and --dh-tap-min, the 44px
  // floor under everything tappable — the most-written size in the product, and
  // so the one most worth enforcing.
  //
  // There is no width family, and `w` and `size` are on this list by extension
  // rather than by a family of their own: a width is a step on the --dh-space-*
  // 4px grid like every other length here. Said plainly because the membership
  // test above is checkable, and for these two the height tokens are not the
  // reason.
  //
  // `min-h-[100dvh]` stays legal through VIEWPORT_VALUE below, not through a
  // carve-out: a viewport unit is a relationship to the device, not a step on a
  // scale.
  'w',
  'h',
  'size',
  'min-h',
  'min-w',
  // --dh-dur-fast / --dh-dur-count, and --dh-ease. `animate` belongs by the
  // same argument: an animation shorthand carries a duration and an easing.
  'duration',
  'ease',
  'animate',
]

/**
 * The same families, spelled as CSS properties, for a bare arbitrary property
 * (`[color:red]`) — which names its property directly and so has no prefix.
 */
const DESIGN_PROPERTIES = [
  'color',
  'background',
  'border',
  'outline',
  'fill',
  'stroke',
  'box-shadow',
  'font',
  'line-height',
  'letter-spacing',
  'padding',
  'margin',
  'gap',
  'width',
  'height',
  'inset',
  'transition-duration',
  'transition-timing-function',
  'animation-duration',
  'animation-timing-function',
]

/** Recognise an arbitrary value: `bg-[…]`, or a bare arbitrary property `[a:b]`. */
const ARBITRARY_ANCHOR = /-\[|^\[/
/** The CSS-variable shorthand, with Tailwind's optional type hint and modifier. */
const VAR_SHORTHAND = /-\((?:[a-z]+:)?(--[A-Za-z0-9_-]+)\)(?:\/\S*)?$/
/** `duration-300`, `duration-0`. */
const BARE_DURATION = /^duration-\d+$/
/** A background image is not a colour, a size, a radius or a duration. */
const URL_VALUE = /^url\(/
/**
 * A viewport unit means "as tall as the screen" — a relationship to the device,
 * not a size drawn from design/tokens.css, and not a token candidate under any
 * reading of the rule. `min-h-[100dvh]` is therefore legal for a reason, rather
 * than because `min-*` was carved out wholesale.
 *
 * Whole-value only, and that is the load-bearing part:
 * `min-h-[calc(100dvh-44px)]` stays flagged, because the 44px inside it is
 * exactly the tap floor that has a token.
 */
const VIEWPORT_VALUE = /^\d+(?:\.\d+)?(?:d|s|l)?v(?:h|w|min|max)$/

/**
 * @param {string} head the utility with its arbitrary value removed
 * @param {readonly string[]} family
 */
function carriesDesignValue(head, family) {
  return family.some((entry) => head === entry || head.startsWith(`${entry}-`))
}
/** A custom property named anywhere in a value. */
const ANY_CUSTOM_PROPERTY = /--[A-Za-z0-9_-]+/g
/** A suppression comment. The reason is mandatory — a bare marker is an error. */
const SUPPRESSION = /design-values-ignore\s*:([^\n]*)/g

/**
 * Split a Tailwind class into its variants and the utility they modify, so
 * `hover:md:duration-300` is judged on `duration-300`. A `:` inside brackets or
 * parens (`[&:focus]:`, `bg-(--dh-gold)`) is not a variant separator.
 *
 * Tailwind v4 marks important with a *trailing* `!` (`duration-300!`); v3's
 * leading `!` is stripped too, so a habit carried over from v3 is still judged.
 *
 * @param {string} token
 * @returns {string} the utility alone
 */
function utilityOf(token) {
  let depth = 0
  let start = 0
  for (let i = 0; i < token.length; i += 1) {
    const char = token[i]
    if (char === '[' || char === '(') depth += 1
    else if (char === ']' || char === ')') depth -= 1
    else if (char === ':' && depth === 0) start = i + 1
  }
  return token.slice(start).replace(/^!+/, '').replace(/!+$/, '').replace(/^-/, '')
}

/**
 * The utility's arbitrary value split from the prefix that introduces it: the
 * text between the outermost `[` and its matching `]`, plus everything before
 * the `-[`. `head` is `''` for a bare arbitrary property, which has no prefix.
 *
 * The `[` must follow a `-` (`bg-[#fff]`) or open the token (`[color:red]`, an
 * arbitrary property, which then needs a `:` to be one). Without that anchor an
 * ordinary string like `items[0]` or a selector like `[aria-label]` would be
 * read as a hardcoded design value.
 *
 * @param {string} utility
 * @returns {{ head: string, value: string } | null}
 */
function arbitraryValueOf(utility) {
  if (!ARBITRARY_ANCHOR.test(utility)) return null
  const open = utility.indexOf('[')
  let depth = 0
  for (let i = open; i < utility.length; i += 1) {
    if (utility[i] === '[') depth += 1
    else if (utility[i] === ']') {
      depth -= 1
      if (depth === 0) {
        const value = utility.slice(open + 1, i)
        if (open === 0 && !value.includes(':')) return null
        return { head: utility.slice(0, Math.max(open - 1, 0)), value }
      }
    }
  }
  return null
}

/**
 * Is this value built only out of our own tokens? `var(--dh-gold)` is, and so
 * is a chain of fallbacks between two tokens. `var(--dh-gold, #c9a227)` is not:
 * the fallback is a hardcoded colour that renders the moment the token is
 * renamed.
 *
 * @param {string} value
 * @returns {{ tokenSourced: boolean, foreign: string | undefined }}
 */
function tokenSourcing(value) {
  const named = value.match(ANY_CUSTOM_PROPERTY) ?? []
  const foreign = named.find((name) => !name.startsWith('--dh-'))
  if (named.length === 0 || foreign !== undefined) return { tokenSourced: false, foreign }
  const skeleton = value
    .replace(/var\(/g, '')
    .replace(ANY_CUSTOM_PROPERTY, '')
    .replace(/[\s,)]/g, '')
  return { tokenSourced: skeleton === '', foreign: undefined }
}

/**
 * Judge one whitespace-separated class name.
 *
 * @param {string} token
 * @returns {{ token: string, message: string } | null}
 */
export function inspectClassToken(token) {
  const utility = utilityOf(token)
  if (utility === '') return null

  if (utility === 'dh-num') {
    return {
      token,
      message:
        '`.dh-num` is not the mechanism for tabular figures — use the `tabular-nums` utility ' +
        '(AGENTS.md, decided 2026-08-08: the same mandatory rule must not be expressed twice).',
    }
  }

  const arbitrary = arbitraryValueOf(utility)
  if (arbitrary !== null) {
    const { head, value } = arbitrary
    const designCarrying =
      head === ''
        ? carriesDesignValue(value.slice(0, value.indexOf(':')), DESIGN_PROPERTIES)
        : carriesDesignValue(head, DESIGN_PREFIXES)
    // The two values a prefix cannot decide on its own: `bg` carries a colour
    // family but `bg-[url(…)]` carries no design value, and `min-h` carries the
    // tap floor but `min-h-[100dvh]` is a relationship to the device.
    if (!designCarrying || URL_VALUE.test(value) || VIEWPORT_VALUE.test(value))
      return null

    const { tokenSourced, foreign } = tokenSourcing(value)
    if (tokenSourced) return null
    if (foreign !== undefined) return foreignVariable(token, foreign)
    return {
      token,
      message:
        `arbitrary value \`[${value}]\` hardcodes a design value. The @theme bridge in ` +
        'src/index.css cannot see this syntax, so it compiles whatever you write. Use a token ' +
        'utility, or `(--dh-…)` if the token has no namespace.',
    }
  }

  const shorthand = VAR_SHORTHAND.exec(utility)
  if (
    shorthand !== null &&
    !shorthand[1].startsWith('--dh-') &&
    carriesDesignValue(utility.slice(0, shorthand.index), DESIGN_PREFIXES)
  ) {
    return foreignVariable(token, shorthand[1])
  }

  if (BARE_DURATION.test(utility)) {
    return {
      token,
      message:
        'Tailwind v4 has no duration namespace for the @theme bridge to clear, so this ' +
        'hardcoded duration compiles. Read the token instead: `duration-(--dh-dur-count)` ' +
        'or `duration-(--dh-dur-fast)`.',
    }
  }

  return null
}

/**
 * @param {string} token
 * @param {string} name
 */
function foreignVariable(token, name) {
  return {
    token,
    message:
      `\`${name}\` is not a design token. Every value comes from design/tokens.css, which ` +
      'means a `--dh-` name.',
  }
}

/**
 * Does this string literal look like a list of Tailwind classes? Judged by
 * shape, because there is nothing else to judge by: outside an arbitrary value
 * a class is lowercase and drawn from a narrow alphabet, and no token in a
 * class list ends a sentence.
 *
 * @param {string} value
 * @returns {boolean}
 */
function looksLikeClassList(value) {
  const tokens = value.split(/\s+/).filter((token) => token !== '')
  return tokens.length > 0 && tokens.every(isClassShaped)
}

/** @param {string} token */
function isClassShaped(token) {
  if (/[.,;]$/.test(token)) return false
  let depth = 0
  for (const char of token) {
    if (char === '[' || char === '(') depth += 1
    else if (char === ']' || char === ')') depth -= 1
    else if (depth === 0 && !/[a-z0-9@:!\-_./%*&>~+]/.test(char)) return false
  }
  return depth === 0
}

/**
 * Every hardcoded design value in one file's source.
 *
 * @param {string} source
 * @param {{ language?: 'js' | 'html' }} [options]
 * @returns {{ token: string, message: string, line: number, column: number }[]}
 */
export function findHardcodedDesignValues(source, options = {}) {
  const { strings, comments } =
    options.language === 'html'
      ? { strings: htmlClassAttributes(source), comments: htmlComments(source) }
      : scanSource(source)

  const findings = []
  for (const { value, index } of strings) {
    if (!looksLikeClassList(value)) continue
    let offset = 0
    for (const raw of value.split(/(\s+)/)) {
      if (raw.trim() !== '') {
        const finding = inspectClassToken(raw)
        if (finding !== null) {
          findings.push({ ...finding, ...positionOf(source, index + offset) })
        }
      }
      offset += raw.length
    }
  }

  return applySuppressions(source, comments, findings)
}

/**
 * Drop findings on the line a `design-values-ignore: <reason>` marker sits on or
 * precedes, and turn a reasonless marker into a finding of its own. A gate
 * people cannot get past legitimately is a gate they edit out.
 *
 * The marker is read **only from comments**, which is why the lexer hands them
 * over: matched against raw source, a marker inside a string literal — or in
 * this project's own error message describing the marker — would silently
 * disable the gate, which is the exact inverse of what the reasonless rule is
 * for.
 *
 * Both placements work. `# noqa` and `eslint-disable-line` are same-line, and a
 * marker that quietly does nothing is worse than no marker at all: the
 * violation still fires and the author concludes the hatch is broken.
 *
 * @param {string} source
 * @param {{ value: string, index: number }[]} comments
 * @param {{ token: string, message: string, line: number, column: number }[]} findings
 */
function applySuppressions(source, comments, findings) {
  const suppressed = new Set()
  const reasonless = []

  for (const comment of comments) {
    for (const match of comment.value.matchAll(SUPPRESSION)) {
      const { line, column } = positionOf(source, comment.index + match.index)
      if (isReason(match[1])) {
        suppressed.add(line)
        suppressed.add(line + 1)
      } else {
        reasonless.push({
          token: 'design-values-ignore',
          message:
            'a suppression needs a reason — at least three characters, one of them a ' +
            'letter — saying why this value cannot read a token. Without one it ' +
            'suppresses nothing.',
          line,
          column,
        })
      }
    }
  }

  return [
    ...findings.filter((finding) => !suppressed.has(finding.line)),
    ...reasonless,
  ].sort((a, b) => a.line - b.line || a.column - b.column)
}

/**
 * What counts as a reason. Deliberately weak — this cannot judge whether a
 * sentence is a good reason, only that somebody wrote words rather than a
 * placeholder. Stated exactly, because the message quotes it.
 *
 * @param {string | undefined} text
 */
function isReason(text) {
  return text !== undefined && text.trim().length >= 3 && /\p{L}/u.test(text)
}

/**
 * @param {string} source
 * @returns {{ value: string, index: number }[]}
 */
function htmlComments(source) {
  return [...source.matchAll(/<!--[\s\S]*?-->/g)].map((match) => ({
    value: match[0],
    index: match.index,
  }))
}

/**
 * `class="…"` in markup. Tailwind scans index.html too, so a hardcoded value in
 * a pre-hydration splash compiles into the same stylesheet.
 *
 * @param {string} source
 * @returns {{ value: string, index: number }[]}
 */
function htmlClassAttributes(source) {
  const found = []
  for (const match of source.matchAll(/\bclass\s*=\s*(["'])(.*?)\1/gs)) {
    found.push({
      value: match[2],
      index: match.index + match[0].length - match[2].length - 1,
    })
  }
  return found
}

/*
 * A small JS lexer. Comments, strings, template literals and regex literals all
 * contain characters that would derail a regex-based scan of the source — the
 * reason this is hand-written rather than a pattern.
 *
 * Every literal it yields keeps its original length, so `index` stays exact:
 * a template hole is blanked with the same number of spaces it occupied, and
 * the string literals inside that hole are yielded separately at their true
 * offsets.
 */

/**
 * @param {string} source
 * @param {number} [base] offset added to every index, for recursive calls
 * @returns {{ strings: { value: string, index: number }[], comments: { value: string, index: number }[] }}
 */
function scanSource(source, base = 0) {
  const strings = []
  const comments = []
  let i = 0
  let previous = ''

  while (i < source.length) {
    const char = source[i]
    if (char === '/' && (source[i + 1] === '/' || source[i + 1] === '*')) {
      const end =
        source[i + 1] === '/' ? endOfLineComment(source, i) : endOfBlockComment(source, i)
      comments.push({ value: source.slice(i, end), index: base + i })
      i = end
    } else if (char === '/' && REGEX_MAY_FOLLOW.has(previous)) {
      i = endOfRegex(source, i)
      previous = '/'
    } else if (char === '"' || char === "'") {
      const quoted = readQuoted(source, i)
      strings.push({ value: quoted.value, index: base + i + 1 })
      i = quoted.end
      previous = '"'
    } else if (char === '`') {
      const template = readTemplate(source, i, base)
      strings.push(template.quasi, ...template.inner.strings)
      comments.push(...template.inner.comments)
      i = template.end
      previous = '`'
    } else {
      if (!/\s/.test(char)) previous = char
      i += 1
    }
  }

  return { strings, comments }
}

/**
 * Positions where a `/` starts a regex rather than a division. `)` and `]` and
 * identifier characters are deliberately absent — those are divisions.
 */
const REGEX_MAY_FOLLOW = new Set([
  '',
  '(',
  ',',
  '=',
  ':',
  '[',
  '!',
  '&',
  '|',
  '?',
  '{',
  '}',
  ';',
  '+',
  '-',
  '*',
  '%',
  '^',
  '~',
  '<',
  '>',
])

/** @param {string} source @param {number} start */
function endOfLineComment(source, start) {
  const newline = source.indexOf('\n', start)
  return newline === -1 ? source.length : newline
}

/** @param {string} source @param {number} start */
function endOfBlockComment(source, start) {
  const close = source.indexOf('*/', start + 2)
  return close === -1 ? source.length : close + 2
}

/** @param {string} source @param {number} start */
function endOfRegex(source, start) {
  let inCharacterClass = false
  for (let i = start + 1; i < source.length; i += 1) {
    const char = source[i]
    if (char === '\\') i += 1
    else if (char === '\n') return start + 1
    else if (char === '[') inCharacterClass = true
    else if (char === ']') inCharacterClass = false
    else if (char === '/' && !inCharacterClass) return i + 1
  }
  return start + 1
}

/**
 * @param {string} source
 * @param {number} start index of the opening quote
 * @returns {{ value: string, end: number }} raw contents, and the index past the close
 */
function readQuoted(source, start) {
  const quote = source[start]
  for (let i = start + 1; i < source.length; i += 1) {
    if (source[i] === '\\') i += 1
    else if (source[i] === quote) return { value: source.slice(start + 1, i), end: i + 1 }
    else if (source[i] === '\n') return { value: source.slice(start + 1, i), end: i }
  }
  return { value: source.slice(start + 1), end: source.length }
}

/**
 * @param {string} source
 * @param {number} start index of the opening backtick
 * @param {number} base
 * @returns {{ quasi: { value: string, index: number }, inner: ReturnType<typeof scanSource>, end: number }}
 */
function readTemplate(source, start, base) {
  let text = ''
  const inner = { strings: [], comments: [] }
  let i = start + 1

  while (i < source.length) {
    const char = source[i]
    if (char === '\\') {
      text += '  '
      i += 2
    } else if (char === '`') {
      i += 1
      break
    } else if (char === '$' && source[i + 1] === '{') {
      const end = endOfHole(source, i + 2)
      const scanned = scanSource(source.slice(i, end), base + i)
      inner.strings.push(...scanned.strings)
      inner.comments.push(...scanned.comments)
      text += ' '.repeat(end - i)
      i = end
    } else {
      text += char
      i += 1
    }
  }

  return { quasi: { value: text, index: base + start + 1 }, inner, end: i }
}

/**
 * @param {string} source
 * @param {number} from first index inside the hole
 * @returns {number} index just past the matching `}`
 */
function endOfHole(source, from) {
  let depth = 1
  let i = from
  while (i < source.length) {
    const char = source[i]
    if (char === '/' && source[i + 1] === '/') i = endOfLineComment(source, i)
    else if (char === '/' && source[i + 1] === '*') i = endOfBlockComment(source, i)
    else if (char === '"' || char === "'") i = readQuoted(source, i).end
    else if (char === '`') i = readTemplate(source, i, 0).end
    else {
      if (char === '{') depth += 1
      else if (char === '}') {
        depth -= 1
        if (depth === 0) return i + 1
      }
      i += 1
    }
  }
  return source.length
}

/**
 * @param {string} source
 * @param {number} index
 * @returns {{ line: number, column: number }}
 */
function positionOf(source, index) {
  const before = source.slice(0, index)
  const line = before.split('\n').length
  return { line, column: index - (before.lastIndexOf('\n') + 1) + 1 }
}
