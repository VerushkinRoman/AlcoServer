package ru.alcoserver.services

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKMatcher
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.KeyType
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.alcoserver.config.AppConfig

sealed interface AppCheckVerdict {
    data object Valid : AppCheckVerdict
    data class Invalid(val reason: String) : AppCheckVerdict
    data object Absent : AppCheckVerdict
}

/**
 * Verifies Firebase App Check tokens.
 *
 * The Firebase Admin Java SDK does not expose App Check token verification, so
 * tokens are validated directly against the App Check JWKS
 * (`https://firebaseappcheck.googleapis.com/v1/jwks`) with Nimbus:
 *
 * 1. parse the JWT and require the `RS256` algorithm;
 * 2. resolve the signing RSA key from the JWKS and verify the signature;
 * 3. validate the `iss`/`aud` claims against the configured Cloud project;
 * 4. reject expired tokens.
 *
 * Never throws: any failure is reported as [AppCheckVerdict.Invalid] with a
 * human-readable reason, and a missing token as [AppCheckVerdict.Absent].
 */
class AppCheckService(
    private val projectNumber: Long = AppConfig.cloudProjectNumber,
    private val jwkSource: JWKSource<SecurityContext> = HttpAppCheckJwkSource(),
) {
    private val logger = LoggerFactory.getLogger(AppCheckService::class.java)

    suspend fun verify(token: String?): AppCheckVerdict = withContext(Dispatchers.IO) {
        if (token.isNullOrBlank()) {
            return@withContext AppCheckVerdict.Absent
        }

        val verdict = verifyToken(token)
        if (verdict is AppCheckVerdict.Invalid) {
            logger.warn("App Check token rejected: ${verdict.reason}")
        }
        verdict
    }

    private fun verifyToken(token: String): AppCheckVerdict {
        val jwsObject = try {
            JWSObject.parse(token)
        } catch (_: Exception) {
            return AppCheckVerdict.Invalid("Token is not a valid JWT")
        }

        if (jwsObject.header.algorithm != JWSAlgorithm.RS256) {
            return AppCheckVerdict.Invalid("Token algorithm must be RS256")
        }

        val rsaKey = resolveRsaKey(jwsObject)
            ?: return AppCheckVerdict.Invalid("No matching App Check signing key")

        val signatureValid = try {
            jwsObject.verify(RSASSAVerifier(rsaKey))
        } catch (_: JOSEException) {
            false
        }
        if (!signatureValid) {
            return AppCheckVerdict.Invalid("Token signature is invalid")
        }

        val claims = jwsObject.payload.toJSONObject()

        val issuer = claims["iss"] as? String
        if (issuer != APP_CHECK_ISSUER_PREFIX + projectNumber) {
            return AppCheckVerdict.Invalid("Token issuer does not match the project")
        }

        val audience = claims["aud"]
        val expectedAudience = "projects/$projectNumber"
        val audienceMatched = when (audience) {
            is String -> audience == expectedAudience
            is List<*> -> audience.any { it == expectedAudience }
            else -> false
        }
        if (!audienceMatched) {
            return AppCheckVerdict.Invalid("Token audience does not match the project")
        }

        val expiresAt = (claims["exp"] as? Number)?.toLong() ?: 0L
        if (expiresAt < System.currentTimeMillis() / 1000L) {
            return AppCheckVerdict.Invalid("Token has expired")
        }

        return AppCheckVerdict.Valid
    }

    private fun resolveRsaKey(jwsObject: JWSObject): RSAKey? {
        return try {
            val matcherBuilder = JWKMatcher.Builder().keyType(KeyType.RSA)
            jwsObject.header.keyID?.let { matcherBuilder.keyID(it) }
            val selection = jwkSource.get(JWKSelector(matcherBuilder.build()), null)
            selection.mapNotNull { it as? RSAKey }.firstOrNull()
        } catch (_: Exception) {
            logger.warn("Failed to resolve App Check signing key")
            null
        }
    }

    private companion object {
        const val APP_CHECK_ISSUER_PREFIX = "https://firebaseappcheck.googleapis.com/"
    }
}
