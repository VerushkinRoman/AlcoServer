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
)

@Serializable
data class DonateResponse(
    val status: String,
    @SerialName("payment_id") val paymentId: String? = null,
    @SerialName("confirmation_url") val confirmationUrl: String? = null,
    val error: String? = null,
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
