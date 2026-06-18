package ru.alcoserver.models

import kotlinx.serialization.Serializable

@Serializable
data class NotificationDTO(
    val title: String,
    val body: String,
    val token: String,
    val type: String? = null
)

@Serializable
data class NotificationResponse(
    val success: Boolean,
    val messageId: String? = null,
    val error: String? = null
)
