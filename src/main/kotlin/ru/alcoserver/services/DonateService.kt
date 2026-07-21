package ru.alcoserver.services

import com.google.firebase.cloud.FirestoreClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import ru.alcoserver.config.AppConfig
import ru.alcoserver.models.DonateConfigResponse
import ru.alcoserver.models.DonateRequest
import ru.alcoserver.models.DonateResponse
import ru.alcoserver.models.DonateVerifyRequest
import ru.alcoserver.models.DonateVerifyResponse
import ru.alcoserver.models.YooKassaPaymentResponse
import java.util.UUID

class DonateService(
    private val httpClient: HttpClient,
) {
    companion object {
        private const val YOOKASSA_API_URL = "https://api.yookassa.ru/v3/payments"
    }

    private val logger = LoggerFactory.getLogger(DonateService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createPayment(request: DonateRequest): DonateResponse {
        val body = buildJsonObject {
            putJsonObject("amount") {
                put("value", request.amount)
                put("currency", "RUB")
            }
            put("capture", true)
            put("description", request.description)

            if (!request.userId.isNullOrBlank() && request.userId != "anonymous") {
                putJsonObject("metadata") {
                    put("userId", request.userId)
                }
            }

            if (!request.paymentToken.isNullOrBlank()) {
                put("payment_token", request.paymentToken)
            } else if (request.redirect) {
                putJsonObject("confirmation") {
                    put("type", "redirect")
                    put("return_url", request.returnUrl ?: "https://alcoserver.ru")
                }
            }
        }

        val response: YooKassaPaymentResponse = httpClient.post(YOOKASSA_API_URL) {
            basicAuth(AppConfig.yookassaShopId, AppConfig.yookassaSecretKey)
            header("Idempotence-Key", UUID.randomUUID().toString())
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.body()

        val donateResponse = DonateResponse(
            status = response.status,
            paymentId = response.id,
            confirmationUrl = response.confirmation?.confirmationUrl,
        )

        return donateResponse
    }

    fun handleWebhook(body: String, remoteIp: String?): Boolean {
        logger.info("Webhook received from IP: $remoteIp")

        if (!isValidWebhookIp(remoteIp)) {
            logger.warn("Webhook rejected: IP $remoteIp not in YooKassa whitelist")
            return false
        }

        try {
            val event = json.decodeFromString<JsonObject>(body)
            val eventType = event["event"]?.toString()?.trim('"') ?: return false
            val paymentObject = event["object"] as? JsonObject ?: return false

            logger.info("Webhook event=$eventType")

            when (eventType) {
                "payment.succeeded" -> handlePaymentSucceeded(paymentObject)
                "refund.succeeded" -> handleRefundSucceeded(paymentObject)
                else -> logger.info("Ignoring event: $eventType")
            }

            return true
        } catch (e: Exception) {
            logger.error("Failed to process webhook: ${e.message}", e)
            return false
        }
    }

    private fun handlePaymentSucceeded(paymentObject: JsonObject) {
        val paymentId = paymentObject["id"]?.toString()?.trim('"') ?: return
        val status = paymentObject["status"]?.toString()?.trim('"') ?: return
        val amountObj = paymentObject["amount"] as? JsonObject
        val amountValue = amountObj?.get("value")?.toString()?.trim('"')
        val metadata = paymentObject["metadata"] as? JsonObject
        val userId = metadata?.get("userId")?.toString()?.trim('"')
        val amount = amountValue?.toDoubleOrNull() ?: 0.0

        logger.info("payment.succeeded: paymentId=$paymentId, status=$status, amount=$amountValue, userId=$userId")

        if (!userId.isNullOrBlank() && userId != "anonymous") {
            saveDonationRecord(paymentId, userId, amount, "succeeded")
            addToUserDonations(userId, amount)
        }
    }

    private fun handleRefundSucceeded(refundObject: JsonObject) {
        val refundId = refundObject["id"]?.toString()?.trim('"') ?: return
        val paymentId = refundObject["payment_id"]?.toString()?.trim('"') ?: return
        val amountObj = refundObject["amount"] as? JsonObject
        val refundAmount = amountObj?.get("value")?.toString()?.trim('"')?.toDoubleOrNull() ?: 0.0

        logger.info("refund.succeeded: refundId=$refundId, paymentId=$paymentId, amount=$refundAmount")

        val userId = getUserIdFromDonation(paymentId)
        if (userId.isNullOrBlank() || userId == "anonymous") {
            logger.warn("Cannot process refund: userId not found for paymentId=$paymentId")
            return
        }

        saveDonationRecord(refundId, userId, refundAmount, "refunded")
        addToUserDonations(userId, -refundAmount)
    }

    private fun isValidWebhookIp(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        val validRanges = listOf(
            "185.71.76.", "185.71.77.",
            "77.75.153.", "77.75.156.",
            "77.75.154.",
        )
        return validRanges.any { ip.startsWith(it) }
    }

    private fun addToUserDonations(userId: String, amount: Double) {
        try {
            val db = FirestoreClient.getFirestore()
            val docRef = db.collection("Collection_of_all_users").document("Users")
            val snapshot = docRef.get().get()

            val allData = snapshot.data?.toMutableMap() ?: mutableMapOf()

            @Suppress("UNCHECKED_CAST")
            val userData = (allData[userId] as? Map<String, Any>)?.toMutableMap()
            if (userData == null) {
                logger.warn("User $userId not found in Firestore")
                return
            }
            val currentDonations = (userData["donations"] as? Number)?.toDouble() ?: 0.0
            val newDonations = maxOf(0.0, currentDonations + amount)
            userData["donations"] = newDonations
            allData[userId] = userData

            docRef.set(allData)
            logger.info("User=$userId donations: $currentDonations + $amount = $newDonations")
        } catch (e: Exception) {
            logger.error("Failed to update donations for user $userId: ${e.message}", e)
        }
    }

    private fun saveDonationRecord(id: String, userId: String, amount: Double, type: String) {
        try {
            val db = FirestoreClient.getFirestore()
            db.collection("donations").document(id).set(
                mapOf(
                    "userId" to userId,
                    "amount" to amount,
                    "type" to type,
                )
            )
            logger.info("Saved $type record: id=$id, userId=$userId, amount=$amount")
        } catch (e: Exception) {
            logger.error("Failed to save donation record: ${e.message}", e)
        }
    }

    private fun getUserIdFromDonation(paymentId: String): String? {
        return try {
            val db = FirestoreClient.getFirestore()
            val doc = db.collection("donations").document(paymentId).get().get()
            doc.getString("userId")
        } catch (e: Exception) {
            logger.error("Failed to get userId from donation: ${e.message}", e)
            null
        }
    }

    private fun getUserDonations(userId: String): Double {
        return try {
            val db = FirestoreClient.getFirestore()
            val docRef = db.collection("Collection_of_all_users").document("Users")
            val snapshot = docRef.get().get()

            @Suppress("UNCHECKED_CAST")
            val allData = snapshot.data ?: emptyMap()

            @Suppress("UNCHECKED_CAST")
            val userData = allData[userId] as? Map<String, Any> ?: emptyMap()
            (userData["donations"] as? Number)?.toDouble() ?: 0.0
        } catch (e: Exception) {
            logger.error("Failed to get user donations: ${e.message}", e)
            0.0
        }
    }

    fun getClientConfig(): DonateConfigResponse {
        return DonateConfigResponse(
            clientPublicKey = AppConfig.yookassaClientPublicKey,
            shopId = AppConfig.yookassaShopId,
        )
    }

    suspend fun verifyPayment(request: DonateVerifyRequest): DonateVerifyResponse {
        logger.info("Verify payment: paymentId=${request.paymentId}, amount=${request.amount}, userId=${request.userId}")
        return try {
            val response: YooKassaPaymentResponse =
                httpClient.get("https://api.yookassa.ru/v3/payments/${request.paymentId}") {
                    basicAuth(AppConfig.yookassaShopId, AppConfig.yookassaSecretKey)
                }.body()

            val amount = request.amount.toDoubleOrNull() ?: 0.0

            logger.info("YooKassa response: status=${response.status}, paid=${response.paid}")

            var totalDonations = 0.0
            if (response.status == "succeeded") {
                val userId = request.userId
                if (!userId.isNullOrBlank() && userId != "anonymous") {
                    addToUserDonations(userId, amount)
                    saveDonationRecord(request.paymentId, userId, amount, "succeeded")
                    totalDonations = getUserDonations(userId)
                    logger.info("User=$userId total donations=$totalDonations")
                }
            }

            DonateVerifyResponse(
                success = true,
                paid = response.status == "succeeded",
                status = response.status,
                totalDonations = totalDonations,
            )
        } catch (e: Exception) {
            logger.error("Failed to verify payment: ${e.message}", e)
            DonateVerifyResponse(success = false, paid = false, status = "error")
        }
    }
}
