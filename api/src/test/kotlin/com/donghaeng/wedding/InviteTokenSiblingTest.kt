package com.donghaeng.wedding

import com.donghaeng.auth.session.SessionToken
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * **[InviteToken] is a copy of `SessionToken`, and this is what keeps it one.**
 *
 * `notes/2026-08-22-decision-the-partner-invite.md` §2 argues why the primitive was
 * not extracted — `SessionToken` is internal to `auth/session`, which publishes no
 * token contract across domains, and hoisting it would have restructured the session
 * token inside a change about invites. That argument is accepted; **what was not
 * acceptable is that its cost — "the two must change together" — lived only in a KDoc
 * sentence in each file.** A repo rule says a thing a test can hold does not also live
 * in prose, and a security fix landing in `SessionToken.matches` (a length guard, a
 * switch to comparing raw digest bytes) would otherwise turn nothing red here. The
 * drift would be in a credential comparison.
 *
 * So the two are compared directly, on the same inputs. This is not a test of what
 * either type does — `AcceptInviteContractTest` and `SessionResolutionTest` are that —
 * it is a test that they still do **the same thing**. When they are meant to diverge,
 * this file is where somebody has to say so, in a diff a reviewer reads.
 *
 * The third token kind (`#27`'s RSVP links) is the moment to extract one primitive
 * with three call sites to justify it; until then, this stands in for the extraction.
 */
internal class InviteTokenSiblingTest {
    @Test
    fun `both parse the same strings the same way`() {
        // Every shape the accept path and the cookie path can be handed, including the
        // ones that are not tokens. A `parse` that started accepting a separator-less
        // value in one and not the other is the drift this catches first, because it is
        // the cheapest kind to introduce.
        listOf(
            "selector.verifier",
            "selector.verifier.with.dots",
            "",
            ".",
            ".verifier",
            "selector.",
            "no-separator",
            " leading.space",
        ).forEach { raw ->
            val invite = InviteToken.parse(raw)
            val session = SessionToken.parse(raw)

            assertThat(invite == null)
                .describedAs("%s: InviteToken parsed it as %s, SessionToken as %s", raw, invite, session)
                .isEqualTo(session == null)
            if (invite != null && session != null) {
                assertThat(invite.selector).describedAs("%s", raw).isEqualTo(session.selector)
                assertThat(invite.value).describedAs("%s", raw).isEqualTo(session.cookieValue)
            }
        }

        assertThat(InviteToken.parse(null)).isNull()
        assertThat(SessionToken.parse(null)).isNull()
    }

    @Test
    fun `both hash a verifier to the same digest`() {
        // The one that would matter most and show least: a stored hash is compared
        // against a computed one, so a change of algorithm or encoding in one type
        // silently stops every token of the other kind from ever matching — or, worse,
        // weakens one of them while the endpoint tests stay green because each type is
        // still self-consistent.
        val raw = "a-selector.a-verifier-worth-256-bits-of-nobody-guessing-it"
        val invite = InviteToken.parse(raw)!!
        val session = SessionToken.parse(raw)!!

        assertThat(invite.verifierHash).isEqualTo(session.verifierHash)
        // SHA-256, hex, lower case — asserted as a shape rather than a literal, so this
        // says "both, and it is still a 256-bit hex digest" rather than pinning a value
        // that would have to be retyped in two places.
        assertThat(invite.verifierHash).hasSize(64).matches("[0-9a-f]{64}")
    }

    @Test
    fun `both compare a presented verifier against a stored hash the same way`() {
        val raw = "a-selector.a-verifier"
        val invite = InviteToken.parse(raw)!!
        val session = SessionToken.parse(raw)!!
        val wrong = InviteToken.parse("a-selector.a-different-verifier")!!.verifierHash

        assertThat(invite.matches(invite.verifierHash)).isTrue()
        assertThat(session.matches(session.verifierHash)).isTrue()
        assertThat(invite.matches(wrong)).isFalse()
        assertThat(session.matches(wrong)).isFalse()
        // The near-miss, because a comparison rewritten as a prefix test would pass
        // every assertion above.
        assertThat(invite.matches(invite.verifierHash.dropLast(1))).isFalse()
        assertThat(session.matches(session.verifierHash.dropLast(1))).isFalse()
        assertThat(invite.matches("")).isFalse()
        assertThat(session.matches("")).isFalse()
    }

    @Test
    fun `both mint the same amount of randomness, and neither prints the half that grants anything`() {
        val invite = InviteToken.mint()
        val session = SessionToken.mint()

        // 16 and 32 CSPRNG bytes, base64url unpadded — 22 and 43 characters. A change to
        // either length in one type is a change to how much entropy that credential
        // carries, and it belongs in a diff beside the other.
        assertThat(invite.selector).hasSameSizeAs(session.selector)
        assertThat(invite.value.substringAfter('.')).hasSameSizeAs(session.cookieValue.substringAfter('.'))
        // Two mints never collide, which is the assertion that would fail if `mint` were
        // ever rewritten to something deterministic.
        assertThat(InviteToken.mint().selector).isNotEqualTo(invite.selector)

        // The masking both types carry for the same reason: this object is only ever
        // printed by accident, and the accident must be harmless.
        assertThat(invite.toString()).doesNotContain(invite.value.substringAfter('.')).contains("***")
        assertThat(session.toString()).doesNotContain(session.cookieValue.substringAfter('.')).contains("***")
    }
}
