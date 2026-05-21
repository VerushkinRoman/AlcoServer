package ru.alcoserver.routes

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String = "healthy",
    val timestamp: Long = System.currentTimeMillis(),
    val services: ServicesStatus = ServicesStatus()
)

@Serializable
data class ServicesStatus(
    @SerialName("play_integrity") val playIntegrity: String = "enabled",
    val firebase: String = "enabled"
)

fun Route.healthRoute() {
    route("/health") {
        get {
            call.respond(HealthResponse())
        }
    }
}
