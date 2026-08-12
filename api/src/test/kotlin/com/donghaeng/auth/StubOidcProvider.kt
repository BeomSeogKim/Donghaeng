package com.donghaeng.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.text.Charsets.UTF_8

/**
 * A local OpenID Connect provider, standing in for Google.
 *
 * It exists because of a hard constraint on this stop: **no Google credentials
 * exist**, and the suite has to be green on a machine that has never seen a Google
 * client. A test that mocked our own code instead would assert that we call the
 * functions we wrote — which is exactly what the Red Gate is not allowed to be.
 * This serves the three endpoints the real provider serves, signs a real RS256 ID
 * token with a real key, and publishes a real JWKS, so Spring Security's token
 * exchange and full ID-token validation run unmodified.
 *
 * It is also the PKCE assertion. [lastCodeVerifier] is only ever set by a token
 * request that actually carried one, and [verifierMatches] recomputes
 * `BASE64URL(SHA256(verifier))` and compares it to the challenge the authorization
 * request advertised. Remove PKCE from the client and there is no challenge to
 * compare, no verifier to find, and the test fails before it reaches the database.
 */
internal class StubOidcProvider(
    val clientId: String,
    private val clientSecret: String,
) {
    private val rsaKey: RSAKey = RSAKeyGenerator(2048).keyID("stub").generate()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val issuer: String get() = "http://127.0.0.1:${server.address.port}"
    val jwkSetUri: String get() = "$issuer/jwks"
    val tokenUri: String get() = "$issuer/token"
    val userInfoUri: String get() = "$issuer/userinfo"
    val authorizationUri: String get() = "$issuer/authorize"

    /** What the provider claims about the person. Set per test. */
    var subject: String = "google-subject-1"
    var email: String? = null
    var emailVerified: Boolean = false
    var fullName: String? = "테스터"

    /** Echoed into the ID token; Spring compares it with the authorization request's. */
    var nonce: String? = null

    var lastCodeVerifier: String? = null
        private set
    var lastRedirectUri: String? = null
        private set
    var lastClientAuthorization: String? = null
        private set

    fun start() {
        server.createContext("/jwks") { it.respond(JWKSet(rsaKey).toPublicJWKSet().toString()) }
        server.createContext("/token", ::token)
        server.createContext("/userinfo", ::userInfo)
        server.executor = null
        server.start()
    }

    fun stop() = server.stop(0)

    fun verifierMatches(codeChallenge: String?): Boolean {
        val verifier = lastCodeVerifier ?: return false
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest) == codeChallenge
    }

    private fun token(exchange: HttpExchange) {
        val form =
            exchange.requestBody
                .readBytes()
                .toString(UTF_8)
                .parseForm()
        lastCodeVerifier = form["code_verifier"]
        lastRedirectUri = form["redirect_uri"]
        lastClientAuthorization = exchange.requestHeaders.getFirst("Authorization")

        val expected = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray(UTF_8))
        if (lastClientAuthorization != "Basic $expected") {
            exchange.respond("""{"error":"invalid_client"}""", status = 401)
            return
        }

        exchange.respond(
            """
            {"access_token":"$ACCESS_TOKEN","token_type":"Bearer","expires_in":3600,
             "scope":"openid email profile","id_token":"${idToken()}"}
            """.trimIndent(),
        )
    }

    private fun userInfo(exchange: HttpExchange) {
        if (exchange.requestHeaders.getFirst("Authorization") != "Bearer $ACCESS_TOKEN") {
            exchange.respond("""{"error":"invalid_token"}""", status = 401)
            return
        }
        exchange.respond(claims().toJsonObject())
    }

    private fun idToken(): String {
        val now = Instant.now()
        val builder =
            JWTClaimsSet
                .Builder()
                .issuer(issuer)
                .subject(subject)
                .audience(clientId)
                .issueTime(java.util.Date.from(now))
                .expirationTime(java.util.Date.from(now.plusSeconds(300)))
        claims().forEach { (name, value) -> builder.claim(name, value) }
        nonce?.let { builder.claim("nonce", it) }

        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.keyID).build(), builder.build())
            .apply { sign(RSASSASigner(rsaKey)) }
            .serialize()
    }

    private fun claims(): Map<String, Any> =
        buildMap {
            put("sub", subject)
            email?.let {
                put("email", it)
                put("email_verified", emailVerified)
            }
            fullName?.let { put("name", it) }
        }

    private companion object {
        const val ACCESS_TOKEN = "stub-access-token"

        fun String.parseForm(): Map<String, String> =
            split("&")
                .filter { it.contains("=") }
                .associate { pair ->
                    val (name, value) = pair.split("=", limit = 2)
                    URLDecoder.decode(name, UTF_8) to URLDecoder.decode(value, UTF_8)
                }

        fun Map<String, Any>.toJsonObject(): String =
            entries.joinToString(",", "{", "}") { (name, value) ->
                val rendered = if (value is Boolean) value.toString() else "\"$value\""
                "\"$name\":$rendered"
            }

        fun HttpExchange.respond(
            body: String,
            status: Int = 200,
        ) {
            val bytes = body.toByteArray(UTF_8)
            responseHeaders.add("Content-Type", "application/json;charset=UTF-8")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }
}
