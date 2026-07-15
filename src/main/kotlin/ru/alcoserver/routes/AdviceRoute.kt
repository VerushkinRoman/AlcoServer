package ru.alcoserver.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import ru.alcoserver.models.AdviceRequest
import ru.alcoserver.models.AppLocale
import ru.alcoserver.models.DrinkType
import ru.alcoserver.models.LLMDate
import ru.alcoserver.models.NotificationDTO
import ru.alcoserver.models.NotificationType
import ru.alcoserver.routes.APIConstants.API_PATH
import ru.alcoserver.services.AdviceService
import ru.alcoserver.services.FirebaseService
import ru.alcoserver.services.RateLimitResult
import ru.alcoserver.services.RequestLoggerService
import kotlin.time.Clock

@Serializable
data class AdviceResponse(
    val status: String,
    val message: String? = null,
    val requestId: String? = null,
    val queued: Boolean = false,
    val queuePosition: Int? = null,
    val estimatedWaitSeconds: Long? = null
)

@Serializable
data class AdviceErrorResponse(
    val error: String
)

@Serializable
data class QueueStatsResponse(
    val currentRequests: Int,
    val maxRequestsPerMinute: Int,
    val queueSize: Int,
    val activeRequests: Int,
    val cooldownMs: Long,
    val status: String = "ok"
)

private const val ADVICE_ROUTE = "/advice"
private const val STATS_PATH = "/stats"
private const val LOGS_PATH = "/logs"

fun Route.adviceRoute(
    adviceService: AdviceService,
    firebaseService: FirebaseService,
    requestLogger: RequestLoggerService
) {
    val logger = LoggerFactory.getLogger("AdviceRoute")
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    route(API_PATH + ADVICE_ROUTE) {
        post {
            val startTime = System.currentTimeMillis()
            var requestId = ""

            try {
                val request = call.receive<AdviceRequest>()

                if (request.token.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AdviceErrorResponse(error = "Device token cannot be empty")
                    )
                    return@post
                }

                if (request.data.isEmpty()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AdviceErrorResponse(error = "Data cannot be empty")
                    )
                    return@post
                }

                val locale = AppLocale.fromValue(request.locale)
                logger.info("Processing advice request for locale: $locale")

                val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
                val twoYearsAgo = today.minus(2, DateTimeUnit.YEAR)
                val minDate = twoYearsAgo.toEpochDays()

                val llmDates = request.data.mapNotNull { drinkData ->
                    if (drinkData.date < minDate) return@mapNotNull null

                    try {
                        val localDate = LocalDate.fromEpochDays(drinkData.date.toInt())
                        LLMDate(
                            date = localDate.toString(),
                            drinkType = DrinkType.fromString(drinkData.drinkType)
                        )
                    } catch (e: Exception) {
                        logger.error("Failed to parse date: ${drinkData.date}", e)
                        throw IllegalArgumentException("Invalid date format: ${drinkData.date}")
                    }
                }

                logger.info("Processing advice request for token: ${request.token.take(10)}..., locale: $locale")

                val (rateLimitResult, deferredAdvice) = adviceService.generateAdviceWithRateLimit(
                    llmDates,
                    locale
                )
                requestId = when (rateLimitResult) {
                    is RateLimitResult.Processed -> rateLimitResult.requestId
                    is RateLimitResult.Queued -> rateLimitResult.requestId
                    is RateLimitResult.Failed -> rateLimitResult.requestId
                }

                when (rateLimitResult) {
                    is RateLimitResult.Processed -> {
                        requestLogger.logRequest(
                            requestId = rateLimitResult.requestId,
                            type = "advice",
                            status = "processed",
                            details = "Locale: $locale"
                        )

                        scope.launch {
                            try {
                                val advice = deferredAdvice.await()
                                if (advice != null) {
                                    val title = adviceService.getNotificationTitle(locale)
                                    sendNotification(
                                        firebaseService,
                                        request.token,
                                        title,
                                        advice,
                                        logger
                                    )
                                } else {
                                    logger.info("Advice is null (not sober or prompts disabled), skipping notification")
                                }

                                val processingTime = System.currentTimeMillis() - startTime
                                requestLogger.logRequest(
                                    requestId = rateLimitResult.requestId,
                                    type = "advice",
                                    status = "completed",
                                    details = "Advice generated and sent. Locale: $locale",
                                    processingTimeMs = processingTime
                                )
                            } catch (e: Exception) {
                                logger.error(
                                    "Failed to generate advice for ${rateLimitResult.requestId}",
                                    e
                                )
                                requestLogger.logRequest(
                                    requestId = rateLimitResult.requestId,
                                    type = "advice",
                                    status = "failed",
                                    details = "Error: ${e.message}. Locale: $locale"
                                )
                            }
                        }

                        call.respond(
                            HttpStatusCode.OK,
                            AdviceResponse(
                                status = "processed",
                                message = "Advice generation started",
                                requestId = rateLimitResult.requestId
                            )
                        )
                    }

                    is RateLimitResult.Queued -> {
                        requestLogger.logRequest(
                            requestId = rateLimitResult.requestId,
                            type = "advice",
                            status = "queued",
                            details = "Position: ${rateLimitResult.queuePosition}, Wait: ${rateLimitResult.estimatedWaitMs}ms, Locale: $locale"
                        )

                        scope.launch {
                            try {
                                logger.info("Waiting for queued advice generation: ${rateLimitResult.requestId}")
                                val advice = deferredAdvice.await()
                                if (advice != null) {
                                    val title = adviceService.getNotificationTitle(locale)
                                    sendNotification(
                                        firebaseService,
                                        request.token,
                                        title,
                                        advice,
                                        logger
                                    )
                                } else {
                                    logger.info("Advice is null (not sober or prompts disabled), skipping notification")
                                }

                                val processingTime = System.currentTimeMillis() - startTime
                                requestLogger.logRequest(
                                    requestId = rateLimitResult.requestId,
                                    type = "advice",
                                    status = "completed",
                                    details = "Queued advice generated and sent. Locale: $locale",
                                    processingTimeMs = processingTime
                                )
                            } catch (e: Exception) {
                                logger.error(
                                    "Failed to process queued request ${rateLimitResult.requestId}",
                                    e
                                )
                                requestLogger.logRequest(
                                    requestId = rateLimitResult.requestId,
                                    type = "advice",
                                    status = "failed",
                                    details = "Error: ${e.message}. Locale: $locale"
                                )
                            }
                        }

                        call.respond(
                            HttpStatusCode.Accepted,
                            AdviceResponse(
                                status = "queued",
                                message = "Request queued due to rate limiting",
                                requestId = rateLimitResult.requestId,
                                queued = true,
                                queuePosition = rateLimitResult.queuePosition,
                                estimatedWaitSeconds = rateLimitResult.estimatedWaitMs / 1000
                            )
                        )
                    }

                    is RateLimitResult.Failed -> {
                        logger.error("Request ${rateLimitResult.requestId} failed: ${rateLimitResult.error}")

                        requestLogger.logRequest(
                            requestId = rateLimitResult.requestId,
                            type = "advice",
                            status = "failed",
                            details = "Error: ${rateLimitResult.error}. Locale: $locale"
                        )

                        call.respond(
                            HttpStatusCode.InternalServerError,
                            AdviceErrorResponse(error = rateLimitResult.error)
                        )
                    }
                }
            } catch (e: IllegalArgumentException) {
                logger.warn("Bad request", e)
                requestLogger.logRequest(
                    requestId = requestId.ifEmpty { "unknown" },
                    type = "advice",
                    status = "bad_request",
                    details = e.message
                )
                call.respond(
                    HttpStatusCode.BadRequest,
                    AdviceErrorResponse(error = e.message ?: "Invalid request")
                )
            } catch (e: Exception) {
                logger.error("Error processing advice request", e)
                requestLogger.logRequest(
                    requestId = requestId.ifEmpty { "unknown" },
                    type = "advice",
                    status = "error",
                    details = e.message
                )
                call.respond(
                    HttpStatusCode.InternalServerError,
                    AdviceErrorResponse(error = "Internal server error")
                )
            }
        }

        get(STATS_PATH) {
            try {
                val stats = adviceService.getRateLimiterStats()
                call.respond(
                    HttpStatusCode.OK,
                    QueueStatsResponse(
                        currentRequests = stats.currentRequests,
                        maxRequestsPerMinute = stats.maxRequestsPerMinute,
                        queueSize = stats.queueSize,
                        activeRequests = stats.activeRequests,
                        cooldownMs = stats.cooldownMs
                    )
                )
            } catch (e: Exception) {
                logger.error("Error getting queue stats", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    AdviceErrorResponse(error = "Failed to get queue stats")
                )
            }
        }

        get("$LOGS_PATH/today") {
            try {
                val logs = requestLogger.getTodayLogs()
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "status" to "success",
                        "count" to logs.size,
                        "logs" to logs
                    )
                )
            } catch (e: Exception) {
                logger.error("Error getting today's logs", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    AdviceErrorResponse(error = "Failed to get logs")
                )
            }
        }

        get("$LOGS_PATH/{date}") {
            try {
                val date =
                    call.parameters["date"] ?: throw IllegalArgumentException("Date is required")
                val logs = requestLogger.getLogsForDate(date)
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "status" to "success",
                        "date" to date,
                        "count" to logs.size,
                        "logs" to logs
                    )
                )
            } catch (e: Exception) {
                logger.error("Error getting logs for date", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    AdviceErrorResponse(error = "Failed to get logs")
                )
            }
        }
    }
}

private fun sendNotification(
    firebaseService: FirebaseService,
    token: String,
    title: String,
    body: String,
    logger: org.slf4j.Logger
) {
    try {
        val notificationDTO = NotificationDTO(
            title = title,
            body = body,
            token = token,
            type = NotificationType.ADVICE.value,
        )

        val result = firebaseService.sendNotification(notificationDTO)

        if (result.success) {
            logger.info("Notification sent successfully to token: ${token.take(10)}...")
        } else {
            logger.error("Failed to send notification: ${result.error}")
        }
    } catch (e: Exception) {
        logger.error("Error sending notification", e)
    }
}
