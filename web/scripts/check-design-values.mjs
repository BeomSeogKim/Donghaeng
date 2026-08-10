#!/usr/bin/env node
/*
 * CLI for scripts/design-values.mjs — see that file for what is checked and why.
 *
 * Same shape as scripts/check-tokens.mjs: no browser, no test infrastructure,
 * exit 1 with the offending lines. Run from `npm run lint`, and therefore from
 * CI, because a linter that is not in CI is a suggestion.
 *
 * WHAT IT SCANS. `src/` and `index.html` — Tailwind's scan root is web/, so a
 * hardcoded value in a pre-hydration splash compiles into the same stylesheet
 * as one in a component. `scripts/` is deliberately outside both this scan and
 * Tailwind's (`@source not '../scripts'` in src/index.css): its fixtures are
 * illegal classes on purpose.
 */
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { extname, join, relative, resolve } from 'node:path'
import { findHardcodedDesignValues } from './design-values.mjs'

/** @type {Record<string, 'js' | 'html'>} */
const LANGUAGES = {
  '.ts': 'js',
  '.tsx': 'js',
  '.js': 'js',
  '.jsx': 'js',
  '.mjs': 'js',
  '.html': 'html',
}

const root = join(import.meta.dirname, '..')
const targets = process.argv.length > 2 ? process.argv.slice(2) : ['src', 'index.html']

const SKIP = new Set(['node_modules', 'dist', 'coverage', '.git'])

/** @param {string} path @returns {string[]} */
function sourceFiles(path) {
  if (statSync(path).isFile()) return extname(path) in LANGUAGES ? [path] : []
  return readdirSync(path, { withFileTypes: true }).flatMap((entry) =>
    SKIP.has(entry.name) ? [] : sourceFiles(join(path, entry.name)),
  )
}

const paths = targets.map((target) => resolve(root, target))
const missing = paths.filter((path) => !existsSync(path))
if (missing.length > 0) {
  console.error(`check-design-values: no such path — ${missing.join(', ')}`)
  process.exit(1)
}

const files = paths.flatMap(sourceFiles)

/*
 * A gate that checked nothing must not report success. The sibling
 * check-tokens.mjs guards the same way ("no stylesheet in dist/assets — did the
 * build run?"): a passing check whose input was empty is the failure this repo
 * keeps having, and it always looks green.
 */
if (files.length === 0) {
  console.error(
    `check-design-values: no source files under ${targets.join(', ')} — nothing was ` +
      'checked, so this is a failure and not a pass.',
  )
  process.exit(1)
}

let violations = 0

for (const file of files) {
  const findings = findHardcodedDesignValues(readFileSync(file, 'utf8'), {
    language: LANGUAGES[extname(file)],
  })
  for (const finding of findings) {
    violations += 1
    console.error(
      `${relative(root, file)}:${finding.line}:${finding.column}  ${finding.token}\n` +
        `  ${finding.message}\n`,
    )
  }
}

if (violations > 0) {
  console.error(
    `check-design-values: ${violations} hardcoded design value(s). Everything reads a token ` +
      '(notes/2026-08-07-design-system.md). If a value genuinely cannot, put ' +
      '`// design-values-ignore: <reason>` on that line or the one above it.',
  )
  process.exit(1)
}

console.log(`check-design-values: ${files.length} file(s), no hardcoded design values.`)
