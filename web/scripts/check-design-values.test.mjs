import { execFileSync } from 'node:child_process'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { expect, it } from 'vitest'

const cli = join(import.meta.dirname, 'check-design-values.mjs')

/** @param {string[]} args @returns {{ status: number, output: string }} */
function run(args) {
  try {
    const output = execFileSync(process.execPath, [cli, ...args], { encoding: 'utf8' })
    return { status: 0, output }
  } catch (error) {
    return { status: error.status, output: `${error.stdout}${error.stderr}` }
  }
}

/** @param {Record<string, string>} files @returns {string} */
function fixtureDir(files) {
  const dir = mkdtempSync(join(tmpdir(), 'dh-design-values-'))
  for (const [name, contents] of Object.entries(files)) {
    writeFileSync(join(dir, name), contents)
  }
  return dir
}

it('fails when it scanned no files at all', () => {
  const { status, output } = run([fixtureDir({})])

  // A check that examined nothing and exited 0 is indistinguishable from a
  // check that passed, which is how a gate goes quietly dead.
  expect(status).toBe(1)
  expect(output).toContain('nothing was checked')
})

it('fails on a hardcoded value, naming the file and line', () => {
  const dir = fixtureDir({
    'a.tsx': 'export const c = <div className="duration-300" />\n',
  })

  const { status, output } = run([dir])

  expect(status).toBe(1)
  expect(output).toContain('a.tsx:1:34')
  expect(output).toContain('duration-300')
})

it('fails on a hardcoded value in markup', () => {
  const dir = fixtureDir({ 'index.html': '<div id="root" class="bg-[#fff]"></div>\n' })

  expect(run([dir]).status).toBe(1)
})

it('passes on a class list that reads tokens', () => {
  const dir = fixtureDir({
    'a.tsx': 'export const c = <div className="bg-ground duration-(--dh-dur-count)" />\n',
  })

  const { status, output } = run([dir])

  expect(status).toBe(0)
  expect(output).toContain('1 file(s), no hardcoded design values')
})
