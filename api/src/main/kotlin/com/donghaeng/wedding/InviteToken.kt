package com.donghaeng.wedding

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/**
 * The value the couple send each other: `<selector>.<verifier>`.
 *
 * **This is bearer authority.** Whoever holds a live one enters the ledger and reads
 * every 하객's contact, which is the premise the whole token baseline of
 * notes/2026-07-30-decision-network-security.md exists for — CSPRNG, SHA-256 storage,
 * constant-time comparison, masking. All of it lands in this one file so that no
 * caller has to remember any of it.
 *
 * [selector] is 128 bits and [verifier] 256, both from [SecureRandom]. The verifier is
 * the only half that grants anything and it is never stored: the row keeps
 * [verifierHash], and the comparison happens in [matches].
 *
 * **A deliberate sibling of `SessionToken` rather than a reuse of it**, and the reason
 * is not a preference about duplication. `SessionToken` is internal to `auth/session`,
 * which is a different domain and publishes no token primitive as a cross-domain
 * contract (api/AGENTS.md, Architecture); reaching into it — or hoisting it into a
 * shared package — would refactor the session token inside a change about invites, in
 * the one file a security reviewer least wants restructured for somebody else's
 * feature. The cost is stated instead of hidden: the two must be changed together, and
 * the reason the invite half exists to be changed is written here
 * (notes/2026-08-22-decision-the-partner-invite.md §2).
 *
 * The lifetimes and the powers differ, which is why they are not one type even in
 * spirit: a session lives 30 days and is spent by nothing, an invite lives one day and
 * is spent by being accepted once.
 */
internal class InviteToken private constructor(
    val selector: String,
    private val verifier: String,
) {
    /**
     * The exact string the API hands back, once. It travels to the partner in a URL
     * **fragment**, which no browser sends to any server, and returns to us in the
     * BODY of the accept POST — never in a path, where an access log and an error
     * document's `instance` would both record it in plaintext
     * (notes/2026-08-22-decision-the-invite-link.md §2).
     */
    val value: String get() = "$selector$SEPARATOR$verifier"

    val verifierHash: String get() = sha256Hex(verifier)

    /**
     * The gate rather than a formality: the row was found by [selector], which is a
     * public handle carrying no authority, so this comparison is the only thing
     * standing between a guessed selector and a stranger's ledger.
     *
     * [MessageDigest.isEqual] rather than `==` because the record asks for a
     * constant-time comparison of tokens, and the same precision `SessionToken` states
     * applies: both sides are already SHA-256 digests, so a variable-time compare would
     * leak how many leading HASH bytes matched, which is not a route to a 256-bit
     * preimage. What is load-bearing is that the comparison exists at all and can be
     * watched failing — `AcceptInviteContractTest` is what watches it.
     */
    fun matches(storedVerifierHash: String): Boolean =
        MessageDigest.isEqual(
            verifierHash.toByteArray(Charsets.US_ASCII),
            storedVerifierHash.toByteArray(Charsets.US_ASCII),
        )

    /**
     * Masked, because "masked in logs" cannot be a habit every caller keeps. This
     * object is only ever printed by accident, and the accident must be harmless: the
     * selector identifies the row for an incident, the verifier is the secret and never
     * appears.
     */
    override fun toString(): String = "InviteToken(selector=$selector, verifier=***)"

    companion object {
        private const val SEPARATOR = '.'
        private const val SELECTOR_BYTES = 16
        private const val VERIFIER_BYTES = 32

        private val RANDOM = SecureRandom()
        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        fun mint(): InviteToken = InviteToken(randomValue(SELECTOR_BYTES), randomValue(VERIFIER_BYTES))

        /**
         * `null` for anything that is not shaped like one of ours — an empty string, a
         * value with no separator, a truncated one. A malformed token is not an error
         * to report; it is simply not an invite, and the caller is told exactly what
         * they are told for a well-formed token that is not ours
         * ([InviteNotFoundException]).
         */
        fun parse(raw: String?): InviteToken? {
            val separator = raw?.indexOf(SEPARATOR) ?: return null
            if (separator <= 0 || separator == raw.length - 1) return null
            return InviteToken(raw.substring(0, separator), raw.substring(separator + 1))
        }

        private fun randomValue(bytes: Int): String = ENCODER.encodeToString(ByteArray(bytes).also(RANDOM::nextBytes))

        /**
         * SHA-256 and deliberately not bcrypt/argon2: these are high-entropy random
         * values, not human-chosen passwords, so there is no dictionary attack to slow
         * down and a slow hash would only add cost to a request the couple is waiting
         * on (notes/2026-07-30-decision-network-security.md).
         */
        private fun sha256Hex(value: String): String =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
            )
    }
}
