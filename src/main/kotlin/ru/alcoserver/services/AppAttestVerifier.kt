package ru.alcoserver.services

import org.slf4j.LoggerFactory
import ru.alcoserver.models.IntegrityInvalidReason
import ru.alcoserver.models.IntegrityRequest
import ru.alcoserver.models.IntegrityResult

/**
 * Verifies iOS attestation requests.
 *
 * The client obtains an Apple **App Attest** backed token through Firebase App
 * Check and sends it as the `token`. Verification is delegated to
 * [AppCheckService], which validates the token against the App Check JWKS and
 * the configured Cloud project. The `keyId`/`assertion`/`challenge` request
 * fields are reserved for a future native App Attest assertion path and do not
 * affect the current verdict.
 */
class AppAttestVerifier(
    private val appCheckService: AppCheckService = AppCheckService(),
) {
    private val logger = LoggerFactory.getLogger(AppAttestVerifier::class.java)

    suspend fun verify(request: IntegrityRequest): IntegrityResult =
        when (val verdict = appCheckService.verify(request.token)) {
            is AppCheckVerdict.Valid -> {
                logger.info("App Attest (App Check) token verified")
                IntegrityResult.Valid
            }

            is AppCheckVerdict.Absent ->
                IntegrityResult.Invalid(IntegrityInvalidReason.AppNotValid("Missing App Attest token"))

            is AppCheckVerdict.Invalid ->
                IntegrityResult.Invalid(IntegrityInvalidReason.AppNotValid(verdict.reason))
        }
}
