package com.example.ogsmini.core

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Помощники для OAuth2 Authorization Code + PKCE (RFC 7636). */
object Pkce {
    private val random = SecureRandom()

    private fun b64url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Случайная строка для code_verifier (64 символа) или state. */
    fun randomToken(bytes: Int = 48): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return b64url(b)
    }

    fun challengeFor(verifier: String): String =
        b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizeUrl(
        clientId: String,
        redirectUri: String,
        challenge: String,
        state: String,
        scope: String = "read write",
    ): String {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        return "https://online-go.com/oauth2/authorize/" +
            "?client_id=${e(clientId)}" +
            "&response_type=code" +
            "&redirect_uri=${e(redirectUri)}" +
            "&scope=${e(scope)}" +
            "&state=${e(state)}" +
            "&code_challenge=${e(challenge)}" +
            "&code_challenge_method=S256"
    }
}
