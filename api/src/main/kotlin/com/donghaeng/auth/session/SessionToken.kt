package com.donghaeng.auth.session

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/**
 * The value the browser holds: `<selector>.<verifier>`.
 *
 * The whole token baseline of notes/2026-07-30-decision-network-security.md lands
 * in this one file — CSPRNG, SHA-256 storage, constant-time comparison, masking —
 * so that no caller has to remember any of it.
 *
 * [selector] is 128 bits and [verifier] 256, both from [SecureRandom]. The
 * verifier is the only half that grants anything, and it is never stored: the row
 * keeps [verifierHash] and the comparison happens in [matches].
 */
internal class SessionToken private constructor(
    val selector: String,
    private val verifier: String,
) {
    /** The exact string that travels in the cookie. */
    val cookieValue: String get() = "$selector$SEPARATOR$verifier"

    val verifierHash: String get() = sha256Hex(verifier)

    /**
     * The gate rather than a formality: the row was found by [selector], which is
     * a public handle, so this comparison is the only thing standing between a
     * guessed selector and someone else's session.
     *
     * [MessageDigest.isEqual] rather than `==` because the record asks for a
     * constant-time comparison of tokens. Be precise about what that buys here:
     * both sides are already SHA-256 digests, so a variable-time compare would
     * leak how many leading *hash* bytes matched, and a hash prefix is not a route
     * to a 256-bit preimage. It costs nothing and it is what the rule says; the
     * load-bearing property is that the comparison exists at all and can be
     * observed failing (notes/2026-08-12-decision-session-token-shape.md).
     */
    fun matches(storedVerifierHash: String): Boolean =
        MessageDigest.isEqual(
            verifierHash.toByteArray(Charsets.US_ASCII),
            storedVerifierHash.toByteArray(Charsets.US_ASCII),
        )

    /**
     * Masked, because "masked in logs" cannot be a habit every caller keeps. This
     * object is only ever printed by accident, and the accident must be harmless:
     * the selector identifies the row for an incident, the verifier is the secret
     * and never appears.
     */
    override fun toString(): String = "SessionToken(selector=$selector, verifier=***)"

    companion object {
        private const val SEPARATOR = '.'
        private const val SELECTOR_BYTES = 16
        private const val VERIFIER_BYTES = 32

        private val RANDOM = SecureRandom()
        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        fun mint(): SessionToken = SessionToken(randomValue(SELECTOR_BYTES), randomValue(VERIFIER_BYTES))

        /**
         * `null` for anything that is not shaped like one of ours — an absent
         * cookie, a truncated one, a value with no separator. A malformed token is
         * not an error to report; it is simply not a session, and saying more
         * would answer questions an anonymous caller has no business asking.
         */
        fun parse(raw: String?): SessionToken? {
            val separator = raw?.indexOf(SEPARATOR) ?: return null
            if (separator <= 0 || separator == raw.length - 1) return null
            return SessionToken(raw.substring(0, separator), raw.substring(separator + 1))
        }

        private fun randomValue(bytes: Int): String = ENCODER.encodeToString(ByteArray(bytes).also(RANDOM::nextBytes))

        /**
         * SHA-256 and deliberately not bcrypt/argon2: these are high-entropy random
         * values, not human-chosen passwords, so there is no dictionary attack to
         * slow down and a slow hash would only add cost to every request
         * (notes/2026-07-30-decision-network-security.md).
         */
        private fun sha256Hex(value: String): String =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
            )
    }
}
