package ru.alcoserver.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import ru.alcoserver.models.DonateRequest
import ru.alcoserver.services.DonateService

fun Route.donateRoute(donateService: DonateService) {
    get("donate/config") {
        call.respond(donateService.getClientConfig())
    }

    post("donate") {
        val request = call.receive<DonateRequest>()
        val response = donateService.createPayment(request)
        val status = if (response.error != null) HttpStatusCode.BadRequest else HttpStatusCode.OK
        call.respond(status, response)
    }
}
