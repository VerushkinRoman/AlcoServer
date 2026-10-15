package ru.alcoserver.services

import ru.alcoserver.models.IntegrityRequest
import ru.alcoserver.models.IntegrityResult

/**
 * Verifies an attestation request for a specific platform.
 *
 * Implementations dispatch by [IntegrityRequest.platform]: Android requests are
 * verified through Google Play Integrity, iOS requests through Apple App Attest.
 */
interface IntegrityVerifier {
    suspend fun verify(request: IntegrityRequest): IntegrityResult
}
