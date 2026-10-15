package ru.alcoserver.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import ru.alcoserver.config.AppCheckMode
import ru.alcoserver.routes.APIConstants.API_PATH
import ru.alcoserver.services.AppCheckService
import ru.alcoserver.services.AppCheckVerdict

const val APP_CHECK_HEADER = "X-Firebase-AppCheck"

private const val WEBHOOK_EXCLUSION = "$API_PATH/donate/webhook"
private const val LEGACY_INTEGRITY_EXCLUSION = "$API_PATH/integrity"

@Serializable
private data class AppCheckErrorResponse(
    val error: String,
    val code: String,
)

class AppCheckSettings {
    lateinit var service: AppCheckService
    var mode: AppCheckMode = AppCheckMode.MONITOR
}

val AppCheckPlugin = createRouteScopedPlugin("AppCheckPlugin", ::AppCheckSettings) {
    val service = pluginConfig.service
    val mode = pluginConfig.mode
    val logger = LoggerFactory.getLogger("AppCheckPlugin")

    onCall { call ->
        if (mode == AppCheckMode.OFF) return@onCall

        val path = call.request.path()
        if (!path.startsWith(API_PATH)) return@onCall
        if (path.isExcludedFromAppCheck()) return@onCall

        val token = call.request.header(APP_CHECK_HEADER)
        val verdict = service.verify(token)

        when (mode) {
            AppCheckMode.OFF -> Unit

            AppCheckMode.MONITOR -> {
                if (verdict !is AppCheckVerdict.Valid) {
                    logger.warn("App Check monitor: verdict=$verdict path=$path")
                }
            }

            AppCheckMode.ENFORCE -> {
                if (verdict !is AppCheckVerdict.Valid) {
                    logger.warn("App Check rejected: verdict=$verdict path=$path")
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        AppCheckErrorResponse(
                            error = "App Check verification failed",
                            code = "app_check_failed",
                        ),
                    )
                }
            }
        }
    }
}

private fun String.isExcludedFromAppCheck(): Boolean =
    this == WEBHOOK_EXCLUSION ||
        startsWith("$WEBHOOK_EXCLUSION/") ||
        this == LEGACY_INTEGRITY_EXCLUSION ||
        startsWith("$LEGACY_INTEGRITY_EXCLUSION/")
