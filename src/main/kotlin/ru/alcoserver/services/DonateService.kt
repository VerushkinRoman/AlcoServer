package ru.alcoserver.services

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.basicAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import ru.alcoserver.config.AppConfig
import ru.alcoserver.models.DonateConfigResponse
import ru.alcoserver.models.DonateRequest
import ru.alcoserver.models.DonateResponse
import ru.alcoserver.models.YooKassaPaymentResponse
import java.util.UUID

class DonateService(
    private val httpClient: HttpClient,
) {
    companion object {
        private const val YOOKASSA_API_URL = "https://api.yookassa.ru/v3/payments"
    }

    suspend fun createPayment(request: DonateRequest): DonateResponse {
        val body = buildMap<String, Any> {
            put("amount", mapOf("value" to request.amount, "currency" to "RUB"))
            put("capture", true)
            put("description", request.description)

            if (!request.paymentToken.isNullOrBlank()) {
                put("payment_token", request.paymentToken)
            } else if (request.redirect) {
                put("confirmation", mapOf(
                    "type" to "redirect",
                    "return_url" to (request.returnUrl ?: "https://alcoserver.ru")
                ))
            }
        }

        val response: YooKassaPaymentResponse = httpClient.post(YOOKASSA_API_URL) {
            basicAuth(AppConfig.yookassaShopId, AppConfig.yookassaSecretKey)
            header("Idempotence-Key", UUID.randomUUID().toString())
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

        return DonateResponse(
            status = response.status,
            paymentId = response.id,
            confirmationUrl = response.confirmation?.confirmationUrl,
        )
    }

    fun getClientConfig(): DonateConfigResponse {
        return DonateConfigResponse(
            clientPublicKey = AppConfig.yookassaClientPublicKey,
            shopId = AppConfig.yookassaShopId,
        )
    }
}
