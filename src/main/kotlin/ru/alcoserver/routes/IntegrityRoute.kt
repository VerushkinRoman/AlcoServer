package ru.alcoserver.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import ru.alcoserver.models.IntegrityErrorMessage
import ru.alcoserver.models.IntegrityInvalidReason
import ru.alcoserver.models.IntegrityRequest
import ru.alcoserver.models.IntegrityResult
import ru.alcoserver.services.IntegrityService

@Serializable
data class IntegritySuccessResponse(
    val status: String = "success",
    val valid: Boolean = true,
    val message: String = "Play Integrity verification passed",
    val appVerdict: String = "PLAY_RECOGNIZED",
    val deviceVerdict: List<String> = listOf("MEETS_DEVICE_INTEGRITY")
)

@Serializable
data class IntegrityInvalidResponse(
    val status: String = "invalid",
    val valid: Boolean = false,
    val error: IntegrityErrorDetails
)

@Serializable
data class IntegrityErrorDetails(
    val type: String,
    val reason: String
)

@Serializable
data class IntegrityErrorResponse(
    val status: String = "error",
    val valid: Boolean = false,
    val error: String
)

@Serializable
data class IntegrityBadRequestResponse(
    val error: String,
    val valid: Boolean = false
)

fun Route.integrityRoute(integrityService: IntegrityService) {
    val logger = LoggerFactory.getLogger("IntegrityRoute")

    route("/api/v1/integrity") {
        post {
            try {
                val request = call.receive<IntegrityRequest>()

                if (request.token.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        IntegrityBadRequestResponse(
                            error = "Token cannot be empty"
                        )
                    )
                    return@post
                }

                logger.info("Received integrity verification request")

                when (val result = integrityService.verifyToken(request.token)) {
                    is IntegrityResult.Valid -> {
                        call.respond(
                            HttpStatusCode.OK,
                            IntegritySuccessResponse()
                        )
                    }

                    is IntegrityResult.Invalid -> {
                        val errorDetails = when (val invalidReason = result.reason) {
                            is IntegrityInvalidReason.AppNotValid ->
                                IntegrityErrorDetails(
                                    type = "app_not_valid",
                                    reason = invalidReason.reason
                                        ?: "App is not recognized by Google Play"
                                )

                            is IntegrityInvalidReason.DeviceNotValid ->
                                IntegrityErrorDetails(
                                    type = "device_not_valid",
                                    reason = invalidReason.reason
                                        ?: "Device integrity check failed"
                                )

                            is IntegrityInvalidReason.AccessForbiddenByServer ->
                                IntegrityErrorDetails(
                                    type = "access_forbidden",
                                    reason = "Access to Play Integrity API is forbidden"
                                )

                            is IntegrityInvalidReason.Unknown ->
                                IntegrityErrorDetails(
                                    type = "unknown",
                                    reason = invalidReason.reason ?: "Unknown validation error"
                                )
                        }

                        call.respond(
                            HttpStatusCode.OK,
                            IntegrityInvalidResponse(error = errorDetails)
                        )
                    }

                    is IntegrityResult.Error -> {
                        val errorMessage = when (val error = result.message) {
                            is IntegrityErrorMessage.ServerError ->
                                "Google Play Integrity API returned error: ${error.code}"

                            is IntegrityErrorMessage.NoConnectionToServer ->
                                "Cannot connect to Google Play Integrity API"

                            is IntegrityErrorMessage.ConnectionTimeout ->
                                "Connection to Google Play Integrity API timed out"

                            is IntegrityErrorMessage.ServerUnreachable ->
                                "Google Play Integrity API is unreachable"

                            is IntegrityErrorMessage.NetworkError ->
                                "Network error: ${error.message}"
                        }

                        call.respond(
                            HttpStatusCode.InternalServerError,
                            IntegrityErrorResponse(error = errorMessage)
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Error processing integrity verification", e)
                call.respond(
                    HttpStatusCode.BadRequest,
                    IntegrityBadRequestResponse(
                        error = "Invalid request format: ${e.message}"
                    )
                )
            }
        }
    }
}
