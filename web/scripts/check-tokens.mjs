/*
 * Post-build guard for the token bridge.
 *
 * src/index.css maps every Tailwind theme value onto a var(--dh-*) from
 * design/tokens.css. That indirection is what keeps utilities and tokens from
 * disagreeing, but it is invisible to tsc and to Vitest: rename a token in
 * design/tokens.css and typecheck, test and build all stay green while the
 * page renders with no ground colour, because an undefined custom property is
 * not a CSS error.
 *
 * So: read the emitted stylesheets, collect every --dh-* that is defined and
 * every one that is referenced, and fail the build on a reference with no
 * definition. Cheap on purpose — no browser, no test infrastructure.
 */
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const assetsDir = join(import.meta.dirname, '..', 'dist', 'assets')

const stylesheets = readdirSync(assetsDir).filter((name) => name.endsWith('.css'))
if (stylesheets.length === 0) {
  console.error('check-tokens: no stylesheet in dist/assets — did the build run?')
  process.exit(1)
}

const defined = new Set()
const referenced = new Map()

for (const name of stylesheets) {
  const css = readFileSync(join(assetsDir, name), 'utf8')
  for (const [, token] of css.matchAll(/(--dh-[\w-]+)\s*:/g)) defined.add(token)
  for (const [, token] of css.matchAll(/var\(\s*(--dh-[\w-]+)/g)) {
    if (!referenced.has(token)) referenced.set(token, name)
  }
}

const missing = [...referenced].filter(([token]) => !defined.has(token))

if (missing.length > 0) {
  console.error(
    `check-tokens: ${missing.length} token(s) referenced but never defined —\n` +
      missing.map(([token, file]) => `  ${token}  (in ${file})`).join('\n') +
      '\nEither the token was renamed in design/tokens.css, or the bridge in ' +
      'src/index.css points at a name that does not exist.',
  )
  process.exit(1)
}

console.log(
  `check-tokens: ${referenced.size} token references, all defined (${defined.size} tokens in the bundle).`,
)
