package ru.alcoserver.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import ru.alcoserver.models.DonateRequest
import ru.alcoserver.models.DonateVerifyRequest
import ru.alcoserver.services.DonateService

private const val DONATE_ROUTE = "/donate"
private const val CONFIG_PATH = "/config"
private const val WEBHOOK_PATH = "/webhook"

fun Route.donateRoute(donateService: DonateService) {
    route(APIConstants.API_PATH + DONATE_ROUTE) {
        get(CONFIG_PATH) {
            call.respond(donateService.getClientConfig())
        }

        post {
            val request = call.receive<DonateRequest>()
            val response = donateService.createPayment(request)
            val status =
                if (response.error != null) HttpStatusCode.BadRequest else HttpStatusCode.OK
            call.respond(status, response)
        }

        post(WEBHOOK_PATH) {
            val body = call.receiveText()
            val remoteIp = call.request.header("X-Forwarded-For")
                ?: call.request.header("X-Real-IP")
                ?: call.request.local.remoteAddress
            val success = donateService.handleWebhook(body, remoteIp)
            if (success) {
                call.respond(HttpStatusCode.OK)
            } else {
                call.respond(HttpStatusCode.BadRequest)
            }
        }

        post("/verify") {
            val request = call.receive<DonateVerifyRequest>()
            val response = donateService.verifyPayment(request)
            call.respond(response)
        }
    }
}
