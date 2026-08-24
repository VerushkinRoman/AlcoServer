package ru.alcoserver.models

import kotlinx.serialization.Serializable

@Serializable
data class NotificationDTO(
    val title: String,
    val body: String,
    val token: String,
    val type: String? = null,
    val messageType: String? = null,
    val friendEmail: String? = null,
    val date: String? = null,
    val drinkType: String? = null,
)

@Serializable
data class NotificationResponse(
    val success: Boolean,
    val messageId: String? = null,
    val error: String? = null
)
