package ru.alcoserver.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DonateRequest(
    val paymentToken: String? = null,
    val amount: String,
    val description: String = "Donation",
    val userId: String? = null,
    val redirect: Boolean = false,
    @SerialName("return_url") val returnUrl: String? = null,
    /**
     * Идентичность плательщика для снапшота `payments/{paymentId}`
     * (change minimize-server-firestore-reads). Nullable — старые клиенты
     * их не передают.
     */
    val email: String? = null,
    val locale: String? = null,
    val token: String? = null,
)

/**
 * Снапшот платежа — маленький док `payments/{paymentId}` с идентичностью
 * плательщика (change minimize-server-firestore-reads): вебхук и проверка
 * берут uid/email/locale/token из него БЕЗ чтения Firestore-каталога.
 * Пишется при создании платежа (идемпотентная перезапись по paymentId),
 * удаляется после успешного проведения и подтверждения платежа.
 */
data class PaymentSnapshot(
    val uid: String? = null,
    val email: String? = null,
    val locale: String? = null,
    val token: String? = null,
    val amount: String? = null,
    val createdAt: Long = 0L,
)

@Serializable
data class DonateResponse(
    val status: String,
    @SerialName("payment_id") val paymentId: String? = null,
    @SerialName("confirmation_url") val confirmationUrl: String? = null,
    val error: String? = null,
)

@Serializable
data class DonateVerifyRequest(
    @SerialName("payment_id") val paymentId: String,
    val amount: String,
    val userId: String? = null,
)

@Serializable
data class DonateVerifyResponse(
    val success: Boolean,
    val paid: Boolean,
    val status: String,
    @SerialName("total_donations") val totalDonations: Double = 0.0,
)

@Serializable
data class DonateConfigResponse(
    @SerialName("client_public_key") val clientPublicKey: String,
    @SerialName("shop_id") val shopId: String,
)

@Serializable
internal data class YooKassaPaymentResponse(
    val id: String,
    val status: String,
    val paid: Boolean = false,
    val confirmation: YooKassaConfirmation? = null,
)

@Serializable
internal data class YooKassaConfirmation(
    val type: String,
    @SerialName("confirmation_url") val confirmationUrl: String? = null,
)
