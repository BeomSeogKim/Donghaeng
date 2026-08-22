#!/usr/bin/env node
/*
 * One invariant about the invite token that FAILS SILENTLY, which is the only
 * reason it is a CI check and not a test.
 *
 * The token is bearer authority with a one-day life: whoever holds a live one
 * enters the ledger and reads every 하객's contact
 * (docs/api-spec.md § POST /weddings/{weddingId}/invite). Both rules below can
 * be broken by a plausible refactor that leaves the whole suite green, because
 * nothing in the suite is looking — which is what makes them the same class of
 * problem as a hardcoded colour, and why this is shaped like
 * scripts/check-design-values.mjs and runs from the same `npm run lint`.
 *
 * WEB STORAGE IS REACHED FROM EXACTLY ONE MODULE. `src/lib/invite.ts` owns
 *the token's whole life in the browser. The failure this closes is the tempting
 * one: a second call site reaching for `localStorage` "so it survives the tab"
 * silently converts a tab-scoped one-day credential into a persistent one on a
 * shared phone, and every test still passes.
 *
 * IT IS A SCRIPT BECAUSE NO TEST CAN SEE IT. The rule is about files that do
 * NOT exist yet — a test can assert what `lib/invite.ts` does, but nothing in a
 * suite notices a brand-new module reaching for `localStorage`. The sibling
 * rule, that `inviteLink()` puts the token only after the `#`, is the opposite:
 * it is one function's behaviour, so it is a test (`src/lib/invite.test.ts`)
 * rather than a second thing here.
 *
 * It scans `src/`, and skips `.test.ts`/`.test.tsx`: a test that reads storage
 * to assert what the app put there is the check working, not a violation.
 *
 * COMMENTS ARE STRIPPED BEFORE MATCHING, and that is not a nicety — this
 * codebase argues its decisions in prose, so the files that must NOT call
 * `sessionStorage` are exactly the files most likely to say the word. Matching
 * raw text made every correct file a violation. Strings are stripped too:
 * `sessionStorage` is reached as a global here, never as `window['...']`, so a
 * string containing it is prose and not a call.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

const root = join(import.meta.dirname, '..')
const src = join(root, 'src')
const SKIP = new Set(['node_modules', 'dist', 'coverage', '.git'])
const SOURCE = /\.(ts|tsx)$/
const TEST = /\.test\.(ts|tsx)$/

/**
 * The one module allowed to touch web storage, and the one test-only exception.
 *
 * `test/setup.ts` clears storage between tests — a jsdom window is shared by a
 * whole file, and a token left behind diverts the next test's empty ledger into
 * 초대 수락. It is named here rather than blanket-skipped so that adding a
 * SECOND piece of test infrastructure that reaches for storage still has to
 * argue for itself.
 */
const ALLOWED = new Set(['src/lib/invite.ts', 'src/test/setup.ts'])

/**
 * The code, with comments and string literals blanked out — newlines kept so a
 * reported line number still points at the right line.
 *
 * It is a scanner rather than a parser because the question is lexical: is this
 * identifier in code, or in prose? Template literals are treated as strings,
 * which is safe in the same way — a token reached through one would be
 * `window[...]`, and nothing in this app does that.
 *
 * @param {string} source @returns {string}
 */
function code(source) {
  let out = ''
  let index = 0

  /** @param {number} end @param {boolean} keep */
  const take = (end, keep) => {
    const chunk = source.slice(index, end)
    out += keep ? chunk : chunk.replace(/[^\n]/g, ' ')
    index = end
  }

  while (index < source.length) {
    const next = /\/\/|\/\*|'|"|`/.exec(source.slice(index))
    if (next === null) break

    take(index + next.index, true)
    const opener = next[0]
    const closer = opener === '//' ? '\n' : opener === '/*' ? '*/' : opener

    // An unterminated literal runs to the end of the file, which is what a
    // syntax error looks like — the typecheck is what reports that, not this.
    let end = index + opener.length
    while (end < source.length) {
      const at = source.indexOf(closer, end)
      if (at === -1) return out + source.slice(index).replace(/[^\n]/g, ' ')
      // A quote or a closer escaped with a backslash does not close anything.
      let backslashes = 0
      while (source[at - 1 - backslashes] === '\\') backslashes += 1
      if (backslashes % 2 === 0) {
        end = at + closer.length
        break
      }
      end = at + 1
    }
    take(Math.min(end, source.length), false)
  }

  take(source.length, true)
  return out
}

/** @param {string} path @returns {string[]} */
function sourceFiles(path) {
  if (statSync(path).isFile()) return SOURCE.test(path) && !TEST.test(path) ? [path] : []
  return readdirSync(path, { withFileTypes: true }).flatMap((entry) =>
    SKIP.has(entry.name) ? [] : sourceFiles(join(path, entry.name)),
  )
}

const files = sourceFiles(src)

// A gate that checked nothing must not report success — the same guard its two
// sibling scripts carry, for the same reason: a passing check whose input was
// empty always looks green.
if (files.length === 0) {
  console.error(
    'check-invite-token: no source files under src/ — nothing was checked, so this is ' +
      'a failure and not a pass.',
  )
  process.exit(1)
}

let violations = 0

for (const file of files) {
  const named = relative(root, file)
  if (ALLOWED.has(named)) continue

  const lines = code(readFileSync(file, 'utf8')).split('\n')
  lines.forEach((line, index) => {
    const found = /\b(localStorage|sessionStorage)\b/.exec(line)
    if (found === null) return
    violations += 1
    console.error(
      `${named}:${index + 1}  ${found[1]}\n` +
        '  Web storage is reached from src/lib/invite.ts and nowhere else. The invite ' +
        'token is a one-day bearer credential, and a second call site is how it ends ' +
        'up in localStorage on a shared phone. Put it behind a function there.\n',
    )
  })
}

if (violations > 0) {
  console.error(`check-invite-token: ${violations} violation(s).`)
  process.exit(1)
}

console.log(`check-invite-token: ${files.length} file(s), web storage in one module.`)
