package ru.alcoserver.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import ru.alcoserver.models.NotificationDTO
import ru.alcoserver.services.FirebaseService

@Serializable
data class NotificationSuccessResponse(
    val status: String = "success",
    @SerialName("message_id") val messageId: String? = null
)

@Serializable
data class NotificationErrorResponse(
    val status: String = "error",
    val error: String? = null
)

@Serializable
data class NotificationBadRequestResponse(
    val error: String
)

fun Route.notificationRoute(firebaseService: FirebaseService) {
    val logger = LoggerFactory.getLogger("NotificationRoute")

    route("/api/v1/notifications") {
        post("/send") {
            try {
                val request = call.receive<NotificationDTO>()

                if (request.token.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NotificationBadRequestResponse(error = "Device token cannot be empty")
                    )
                    return@post
                }

                if (request.title.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NotificationBadRequestResponse(error = "Notification title cannot be empty")
                    )
                    return@post
                }

                if (request.body.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NotificationBadRequestResponse(error = "Notification body cannot be empty")
                    )
                    return@post
                }

                logger.info("Sending notification: ${request.title}")

                val result = firebaseService.sendNotification(request)

                if (result.success) {
                    call.respond(
                        HttpStatusCode.OK,
                        NotificationSuccessResponse(messageId = result.messageId)
                    )
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        NotificationErrorResponse(error = result.error)
                    )
                }
            } catch (e: Exception) {
                logger.error("Error sending notification", e)
                call.respond(
                    HttpStatusCode.BadRequest,
                    NotificationBadRequestResponse(error = "Invalid request format")
                )
            }
        }
    }
}
