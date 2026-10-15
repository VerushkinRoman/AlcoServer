package ru.alcoserver.services

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.runBlocking
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import ru.alcoserver.models.IntegrityInvalidReason
import ru.alcoserver.models.IntegrityRequest
import ru.alcoserver.models.IntegrityResult

class AppAttestVerifierTest {

    private val projectNumber = 123456789L

    @Test
    fun `valid App Check token is accepted`() = runBlocking<Unit> {
        val testKeys = TestKeys()
        val verifier = appAttestVerifier(testKeys.publicJwkSet())

        val result = verifier.verify(IntegrityRequest(token = testKeys.signedToken(projectNumber), platform = "ios"))

        assertEquals(IntegrityResult.Valid, result)
    }

    @Test
    fun `token issued for another project is rejected`() = runBlocking<Unit> {
        val testKeys = TestKeys()
        val verifier = appAttestVerifier(testKeys.publicJwkSet())

        val result = verifier.verify(
            IntegrityRequest(token = testKeys.signedToken(projectNumber = 987654321L), platform = "ios")
        )

        assertIs<IntegrityResult.Invalid>(result)
        assertEquals(IntegrityInvalidReason.AppNotValid::class, result.reason::class)
    }

    @Test
    fun `not RS256 algorithm is rejected`() = runBlocking<Unit> {
        val testKeys = TestKeys()
        val verifier = appAttestVerifier(testKeys.publicJwkSet())

        val hmacToken = hmacSignedToken(projectNumber)

        val result = verifier.verify(IntegrityRequest(token = hmacToken, platform = "ios"))

        assertIs<IntegrityResult.Invalid>(result)
    }

    @Test
    fun `expired token is rejected`() = runBlocking<Unit> {
        val testKeys = TestKeys()
        val verifier = appAttestVerifier(testKeys.publicJwkSet())

        val result = verifier.verify(
            IntegrityRequest(token = testKeys.signedToken(projectNumber, expired = true), platform = "ios")
        )

        assertIs<IntegrityResult.Invalid>(result)
    }

    @Test
    fun `garbage token is rejected`() = runBlocking<Unit> {
        val testKeys = TestKeys()
        val verifier = appAttestVerifier(testKeys.publicJwkSet())

        val result = verifier.verify(IntegrityRequest(token = "not-a-jwt", platform = "ios"))

        assertIs<IntegrityResult.Invalid>(result)
    }

    private fun appAttestVerifier(jwkSource: JWKSource<SecurityContext>): AppAttestVerifier {
        return AppAttestVerifier(projectNumber = projectNumber, jwkSource = jwkSource)
    }

    private class TestKeys {
        private val keyId = "test-key"

        private val keyPair = run {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            generator.generateKeyPair()
        }

        private val rsaKey: RSAKey = RSAKey.Builder(keyPair.public as RSAPublicKey)
            .privateKey(keyPair.private as RSAPrivateKey)
            .keyID(keyId)
            .build()

        fun publicJwkSet(): JWKSource<SecurityContext> {
            val jwkSet = JWKSet(listOf(rsaKey.toPublicJWK() as JWK))
            return ImmutableJWKSet(jwkSet)
        }

        fun signedToken(projectNumber: Long, expired: Boolean = false): String {
            val claims = claimsFor(projectNumber, expired)
            val signedJwt = SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(),
                claims
            )
            signedJwt.sign(RSASSASigner(rsaKey))
            return signedJwt.serialize()
        }
    }
}

private fun hmacSignedToken(projectNumber: Long): String {
    val claims = claimsFor(projectNumber, expired = false)
    val signedJwt = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
    signedJwt.sign(MACSigner("test-secret-test-secret-test-secret-32b"))
    return signedJwt.serialize()
}
    private fun claimsFor(projectNumber: Long, expired: Boolean): JWTClaimsSet {
    val builder = JWTClaimsSet.Builder()
        .issuer("https://firebaseappcheck.googleapis.com/$projectNumber")
        .audience(listOf("projects/$projectNumber"))
        .subject("1:$projectNumber:ios:some-app-id")
        .issueTime(Date())
    if (expired) {
        builder.expirationTime(Date(System.currentTimeMillis() - 60_000L))
    } else {
        builder.expirationTime(Date(System.currentTimeMillis() + 600_000L))
    }
    return builder.build()
}