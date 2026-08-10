import { describe, expect, it } from 'vitest'
import { findHardcodedDesignValues } from './design-values.mjs'

/**
 * Every fixture here is a class string that compiles today — that is the whole
 * point: a class Tailwind would reject needs no check behind it.
 */

/** @param {string} classes */
function jsx(classes) {
  return `export function C() {\n  return <div className="${classes}">x</div>\n}\n`
}

/** @param {string} source @param {{language?: 'js'|'html'}} [options] */
function tokensFlagged(source, options) {
  return findHardcodedDesignValues(source, options).map((finding) => finding.token)
}

describe('hole 1 — Tailwind v4 has no duration namespace', () => {
  it.each([
    'duration-300',
    'duration-0',
    'duration-[300ms]',
    'hover:duration-150',
    // Important moved to the trailing position in v4. The leading form is v3's,
    // kept because the habit outlives the syntax.
    'duration-300!',
    'md:duration-300!',
    '!duration-300',
  ])('flags %s', (token) => {
    expect(tokensFlagged(jsx(`transition ${token}`))).toEqual([token])
  })

  it.each([
    'duration-(--dh-dur-count)',
    'duration-(--dh-dur-fast)',
    'duration-[var(--dh-dur-count)]',
    'motion-safe:duration-(--dh-dur-count)',
    'duration-(--dh-dur-count)!',
  ])('accepts %s', (token) => {
    expect(tokensFlagged(jsx(`transition ${token}`))).toEqual([])
  })
})

describe('hole 2 — arbitrary values walk past the cleared namespaces', () => {
  it.each([
    'bg-[#ff0000]',
    'text-[13px]',
    'rounded-[4px]',
    'p-[3px]',
    'shadow-[0_1px_2px_rgba(0,0,0,.2)]',
    '[color:red]',
    'md:hover:border-[#c9a227]',
    '-mt-[2px]',
    'ease-[cubic-bezier(0,0,1,1)]',
    'h-[61px]',
    // An animation shorthand carries a duration and an easing, which is what
    // puts `duration` and `ease` on the list in the first place.
    'animate-[spin_2s_linear_infinite]',
  ])('flags %s', (token) => {
    expect(tokensFlagged(jsx(token))).toEqual([token])
  })

  it.each(['bg-(--dh-gold)', 'border-[var(--dh-gold)]', 'bg-(--dh-gold)/50'])(
    'accepts %s, which reads a token',
    (token) => {
      expect(tokensFlagged(jsx(token))).toEqual([])
    },
  )
})

describe('the three syntaxes agree', () => {
  // Same prefix, same intent, three spellings. A variable from outside
  // design/tokens.css is a value from nowhere however it is written.
  it.each(['bg-[#fff]', 'bg-[var(--brand-blue)]', 'bg-(--brand-blue)'])(
    'flags %s',
    (token) => {
      expect(tokensFlagged(jsx(token))).toEqual([token])
    },
  )

  it('accepts a fallback chain between two tokens', () => {
    expect(tokensFlagged(jsx('bg-[var(--dh-primary,var(--dh-ink))]'))).toEqual([])
  })

  it('flags a fallback that ends in a literal', () => {
    expect(tokensFlagged(jsx('bg-[var(--dh-primary,#c9a227)]'))).toEqual([
      'bg-[var(--dh-primary,#c9a227)]',
    ])
  })
})

describe('only prefixes with a token family behind them are judged', () => {
  // Scope: notes/2026-08-10-decision-design-value-enforcement.md.
  it.each([
    'grid-cols-[1fr_auto]',
    'z-[60]',
    'aspect-[4/3]',
    "content-['']",
    'bg-[url(/splash.png)]',
    'translate-x-[3px]',
    'max-w-[65ch]',
    'basis-[30%]',
    'grid-rows-[auto_1fr]',
  ])('accepts %s, which carries no design value', (token) => {
    expect(tokensFlagged(jsx(token))).toEqual([])
  })

  // The pair that pins the decision: the same shape of value, one prefix with a
  // token family and one without. Widening the list back out breaks this.
  it('flags a design prefix and accepts a layout prefix given the same value', () => {
    expect(tokensFlagged(jsx('w-[60px] max-w-[60px]'))).toEqual(['w-[60px]'])
    expect(tokensFlagged(jsx('gap-[7px] grid-cols-[7px_1fr]'))).toEqual(['gap-[7px]'])
    expect(tokensFlagged(jsx('bg-(--x) grid-cols-(--x)'))).toEqual(['bg-(--x)'])
  })

  it('accepts an arbitrary property that names no design property', () => {
    expect(tokensFlagged(jsx('[mask-type:luminance]'))).toEqual([])
  })

  // The tap floor (--dh-tap-min, 44px) is written as `min-h`/`min-w`, so those
  // prefixes are on the list and `min-h-[100dvh]` stays legal through the
  // viewport-unit exception rather than a carve-out.
  it.each([
    'min-h-[100dvh]',
    'min-h-[100vh]',
    'min-w-[100vw]',
    'h-[100svh]',
    'min-h-[50.5lvh]',
    'w-[100dvw]',
  ])('accepts %s, a relationship to the device', (token) => {
    expect(tokensFlagged(jsx(token))).toEqual([])
  })

  it.each([
    'min-h-[44px]',
    'min-w-[44px]',
    'min-h-[2.75rem]',
    // Whole-value only. The viewport unit does not launder the 44px beside it —
    // and that 44px is precisely the one with a token behind it.
    'min-h-[calc(100dvh-44px)]',
    'min-h-[calc(100dvh_-_44px)]',
    'min-h-[100dvh_44px]',
  ])('flags %s, which has a token behind it', (token) => {
    expect(tokensFlagged(jsx(token))).toEqual([token])
  })

  // The two lists describe the same families in two spellings, so a value that
  // one catches and the other waves through is a bug in whichever is shorter.
  it('agrees with the CSS-property list about an animation duration', () => {
    expect(tokensFlagged(jsx('animate-[spin_2s]'))).toHaveLength(1)
    expect(tokensFlagged(jsx('[animation-duration:300ms]'))).toHaveLength(1)
  })
})

/*
 * The heuristic that decides whether a string literal is a class list at all.
 * It carries the whole false-positive surface of scanning every literal in the
 * file, and a mutant that makes it always-true is caught by almost nothing
 * else — so these pin it directly rather than through a neighbour.
 */
describe('what is not a class list', () => {
  it.each([
    ['Korean copy', '이 하객은 참석으로 표시되어 있습니다'],
    ['a mixed sentence', '보증인원 duration-300 아님'],
    ['a date', '2026-08-10'],
    ['an error code', 'guest_not_found'],
    ['a URL', 'http://api.test/scaffold'],
    ['a two-word label', 'close menu'],
    ['lowercase prose quoting a class', 'a well-known guest-list problem'],
    ['a sentence with a capital', 'Use duration-300 nowhere'],
    ['a sentence ending in a period', 'the duration-300 rule applies.'],
  ])('ignores %s', (_name, value) => {
    expect(tokensFlagged(`const s = ${JSON.stringify(value)}\n`)).toEqual([])
  })

  // The other side of the same line: these are class lists and must be judged,
  // so tightening the heuristic to kill the residual above breaks these.
  it.each(['duration-300', 'flex gap-[7px]', 'p-4 md:p-6 bg-[#eee]'])(
    'still judges %s',
    (value) => {
      expect(tokensFlagged(`const s = '${value}'\n`).length).toBeGreaterThan(0)
    },
  )
})

describe('the utilities the @theme bridge does expose stay legal', () => {
  it('accepts the classes the scaffold already ships', () => {
    const source =
      'export function App() {\n' +
      '  return (\n' +
      '    <main className="min-h-screen bg-ground text-ink">\n' +
      '      <h1 className="font-display text-title tracking-display">동행</h1>\n' +
      '    </main>\n' +
      '  )\n' +
      '}\n'
    expect(tokensFlagged(source)).toEqual([])
  })

  it('accepts tabular-nums, the mandated mechanism for digits', () => {
    expect(tokensFlagged(jsx('text-display tabular-nums'))).toEqual([])
  })

  it('flags dh-num, which it replaced', () => {
    expect(tokensFlagged(jsx('text-display dh-num'))).toEqual(['dh-num'])
  })
})

describe('where class names are found', () => {
  it('reads a className expression container', () => {
    expect(tokensFlagged('<div className={"gap-[7px]"} />')).toEqual(['gap-[7px]'])
  })

  it('reads every string in a composed className', () => {
    const source =
      "<div className={clsx('p-4', selected && 'bg-[#eee]', `duration-300`)} />"
    expect(tokensFlagged(source)).toEqual(['bg-[#eee]', 'duration-300'])
  })

  // A class written once and reused is exactly where a hardcode gets made, so a
  // class list is judged wherever it is written — not only in a className.
  it('reads a hoisted class table', () => {
    const source = "const styles = { on: 'duration-300', off: 'bg-[#eee]' }\n"
    expect(tokensFlagged(source)).toEqual(['duration-300', 'bg-[#eee]'])
  })

  it('reads a class list inside a template hole', () => {
    // biome-ignore lint/suspicious/noTemplateCurlyInString: the placeholder is the fixture
    const source = '<div className={`p-4 ${bad ? "bg-[#f00]" : ""}`} />'
    expect(tokensFlagged(source)).toEqual(['bg-[#f00]'])
  })

  it('reads a class attribute in markup', () => {
    const source = '<body>\n  <div id="root" class="bg-[#fff] p-4"></div>\n</body>\n'
    expect(tokensFlagged(source, { language: 'html' })).toEqual(['bg-[#fff]'])
  })

  it('ignores prose, which is not a class list', () => {
    const source = "const help = 'Use duration-300 nowhere; read the token instead.'\n"
    expect(tokensFlagged(source)).toEqual([])
  })

  it('ignores a comment, and is not derailed by an apostrophe in one', () => {
    const source =
      "// duration-300 is what this exists to stop; don't write it\n" +
      "const ok = 'p-4'\n" +
      '/* bg-[#f00] */\n'
    expect(tokensFlagged(source)).toEqual([])
  })

  it('is not derailed by a comment inside an expression container', () => {
    expect(tokensFlagged("<div className={/* } */ 'duration-300'} />")).toEqual([
      'duration-300',
    ])
  })

  it('is not derailed by a regex literal containing a quote', () => {
    const source = "const q = /['\"]/g\nconst c = 'bg-[#f00]'\n"
    expect(tokensFlagged(source)).toEqual(['bg-[#f00]'])
  })

  it('ignores a selector or an index, which are not arbitrary values', () => {
    const source = "const a = 'items[0]'\nconst b = '[aria-label]'\n"
    expect(tokensFlagged(source)).toEqual([])
  })

  it('leaves an arbitrary variant alone — a selector is not a design value', () => {
    expect(tokensFlagged(jsx('[&>*]:border-t [&>*]:border-line'))).toEqual([])
  })
})

describe('the position it reports', () => {
  it('points at the offending class, not at the attribute', () => {
    const [finding] = findHardcodedDesignValues(jsx('transition duration-300'))
    expect(finding).toMatchObject({ line: 2, column: 37 })
  })

  // A hole collapsed to one character shifts every later offset, which sends
  // the reader to the wrong line — and line:column is the whole CLI output.
  it('survives an interpolation earlier in the same class list', () => {
    // biome-ignore lint/suspicious/noTemplateCurlyInString: the placeholder is the fixture
    const source = ['const a = `', '  ${size}', '  duration-300', '`'].join('\n')
    const [finding] = findHardcodedDesignValues(source)
    expect(finding).toMatchObject({ line: 3, column: 3 })
  })
})

describe('the escape hatch', () => {
  it('suppresses the next line when given a reason', () => {
    const source =
      '// design-values-ignore: the venue logo is a fixed asset size\n' +
      "const a = 'w-[117px]'\n"
    expect(tokensFlagged(source)).toEqual([])
  })

  // The placement people reach for first, by analogy with `# noqa` and
  // eslint-disable-line. Accepting it silently doing nothing is the worst
  // outcome: the violation fires and the hatch looks broken.
  it('suppresses its own line too', () => {
    const source = "const a = 'w-[117px]' // design-values-ignore: fixed asset size\n"
    expect(tokensFlagged(source)).toEqual([])
  })

  it('suppresses only the line it precedes', () => {
    const source =
      '// design-values-ignore: first one is deliberate\n' +
      "const a = 'w-[117px]'\n" +
      "const b = 'w-[118px]'\n"
    expect(tokensFlagged(source)).toEqual(['w-[118px]'])
  })

  it('works in a block comment and in markup', () => {
    expect(
      tokensFlagged("/* design-values-ignore: fixed asset */\nconst a = 'w-[117px]'\n"),
    ).toEqual([])
    expect(
      tokensFlagged(
        '<!-- design-values-ignore: fixed asset -->\n<div class="w-[117px]">',
        {
          language: 'html',
        },
      ),
    ).toEqual([])
  })

  // A marker is a comment. Read from raw source it would let a string literal —
  // including this checker's own message describing the marker — turn the gate
  // off with nothing in the output to say so.
  it('ignores a marker inside a string literal', () => {
    const source = "const doc = 'design-values-ignore: see docs'\nconst a = 'w-[117px]'\n"
    expect(tokensFlagged(source)).toEqual(['w-[117px]'])
  })

  it('ignores a marker in JSX text', () => {
    const source =
      '<p>design-values-ignore: see docs</p>\n' + "<div className='w-[117px]' />\n"
    expect(tokensFlagged(source)).toEqual(['w-[117px]'])
  })

  it.each(['', ' ', ' .', ' 12'])('refuses the reason %o', (reason) => {
    const source = `// design-values-ignore:${reason}\nconst a = 'w-[117px]'\n`
    expect(tokensFlagged(source)).toEqual(['design-values-ignore', 'w-[117px]'])
  })

  it('reports findings in line order', () => {
    const source =
      "const a = 'w-[117px]'\n" + '// design-values-ignore:\n' + "const b = 'w-[118px]'\n"
    expect(findHardcodedDesignValues(source).map((finding) => finding.line)).toEqual([
      1, 2, 3,
    ])
  })
})
