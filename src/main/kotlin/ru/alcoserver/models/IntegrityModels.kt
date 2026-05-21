package ru.alcoserver.models

import kotlinx.serialization.Serializable

@Serializable
data class IntegrityRequest(
    val token: String,
    val nonce: String? = null
)

sealed class IntegrityResult {
    data object Valid : IntegrityResult()
    data class Invalid(val reason: IntegrityInvalidReason) : IntegrityResult()
    data class Error(val message: IntegrityErrorMessage) : IntegrityResult()
}

sealed class IntegrityInvalidReason {
    data class AppNotValid(val reason: String?) : IntegrityInvalidReason()
    data class DeviceNotValid(val reason: String?) : IntegrityInvalidReason()
    data object AccessForbiddenByServer : IntegrityInvalidReason()
    data class Unknown(val reason: String?) : IntegrityInvalidReason()
}

sealed class IntegrityErrorMessage {
    data class ServerError(val code: Int) : IntegrityErrorMessage()
    data object NoConnectionToServer : IntegrityErrorMessage()
    data object ConnectionTimeout : IntegrityErrorMessage()
    data object ServerUnreachable : IntegrityErrorMessage()
    data class NetworkError(val message: String?) : IntegrityErrorMessage()
}
