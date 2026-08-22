import { expect, it } from 'vitest'
import { inviteLink, readInviteToken } from './invite'

/*
 * The link a couple pastes into KakaoTalk, and the one thing that must be true
 * of it: **the token appears only after the `#`.**
 *
 * THE FRAGMENT IS THE ONLY PART OF A URL NEVER SENT TO A SERVER — not in a
 * request line, so not in an access log; not in a `Referer` to any third party;
 * not in an error document's `instance`
 * (notes/2026-08-22-decision-the-invite-link.md §2, closing `#69`). The spec
 * puts it as a prohibition: "Never put the token in a path or a query string,
 * ours or anyone's."
 *
 * IT IS ASSERTED ON THE PRE-FRAGMENT HALF because nothing else looks there. A
 * refactor to `?t=` would break the decision record and the spec while every
 * other test in the suite stayed green — the accept screen reads the token
 * back out either way.
 */

const TOKEN = 'sel3ct0r.v3r1f13r'

it('carries the token only after the fragment marker', () => {
  const [beforeFragment, ...fragment] = inviteLink(TOKEN).split('#')

  expect(beforeFragment).not.toContain(TOKEN)
  expect(beforeFragment).toBe(`${window.location.origin}/invite`)
  expect(fragment.join('#')).toBe(`t=${TOKEN}`)
})

it('survives a token whose characters mean something in a URL', () => {
  // base64url has no `+` or `/`, but the encoding is `URLSearchParams` at both
  // ends rather than an assumption about the alphabet — so the two cannot
  // disagree about what a character means if the token's shape ever changes.
  const awkward = 'a+b/c=d&e#f'
  const link = inviteLink(awkward)

  expect(link.split('#')[0]).toBe(`${window.location.origin}/invite`)
  expect(readInviteToken(`#${link.split('#').slice(1).join('#')}`)).toBe(awkward)
})

it('reads nothing out of a fragment that carries no token', () => {
  // `/invite#top` is an anchor, not an invite, and an empty `t=` is not a token
  // — both are "there is nothing pending here" rather than a token of `''`.
  expect(readInviteToken('')).toBeNull()
  expect(readInviteToken('#top')).toBeNull()
  expect(readInviteToken('#t=')).toBeNull()
})
