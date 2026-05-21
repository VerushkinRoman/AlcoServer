package ru.alcoserver

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import ru.alcoserver.config.AppConfig
import ru.alcoserver.routes.integrityRoute
import ru.alcoserver.routes.notificationRoute
import ru.alcoserver.services.FirebaseService
import ru.alcoserver.services.IntegrityService

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

fun main() {
    Runtime.getRuntime().addShutdownHook(Thread {
        println("Shutting down application...")
    })

    embeddedServer(Netty, port = AppConfig.serverPort, host = AppConfig.serverHost) {
        configureServer()
    }.start(wait = true)
}

fun Application.configureServer() {
    val logger = LoggerFactory.getLogger("Application")

    val integrityService = IntegrityService()
    val firebaseService = FirebaseService()

    install(ContentNegotiation) {
        json()
    }

    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/api") }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception", cause)
            call.respondText(
                text = "Internal Server Error: ${cause.message}",
                status = HttpStatusCode.InternalServerError
            )
        }
    }

    routing {
        get("/health") {
            call.respond(HealthResponse())
        }

        integrityRoute(integrityService)
        notificationRoute(firebaseService)
    }

    monitor.subscribe(ApplicationStopped) {
        logger.info("Application stopped")
    }
}
