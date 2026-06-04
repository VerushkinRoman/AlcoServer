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
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import ru.alcoserver.config.AppConfig
import ru.alcoserver.routes.adviceRoute
import ru.alcoserver.routes.healthRoute
import ru.alcoserver.routes.integrityRoute
import ru.alcoserver.routes.notificationRoute
import ru.alcoserver.services.AdviceService
import ru.alcoserver.services.FirebaseService
import ru.alcoserver.services.IntegrityService
import ru.alcoserver.services.RateLimiterService
import ru.alcoserver.services.RequestLoggerService

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
    val rateLimiterService = RateLimiterService(maxRequestsPerMinute = 20)
    val requestLogger = RequestLoggerService()
    val adviceService = AdviceService(rateLimiterService)

    install(ContentNegotiation) {
        json()
    }

    install(XForwardedHeaders)

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
        healthRoute()
        integrityRoute(integrityService)
        notificationRoute(firebaseService)
        adviceRoute(adviceService, firebaseService, requestLogger)
    }

    monitor.subscribe(ApplicationStopped) {
        logger.info("Application stopped")
        adviceService.shutdown()
        requestLogger.shutdown()
    }
}
