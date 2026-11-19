package ru.alcoserver.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NotificationDTO(
    /**
     * Идентификатор сообщения от клиента: стабильный между ретраями из очереди
     * `pending_notifications`. Используется как `notification.tag` на Android, чтобы повторная
     * доставка заменяла уведомление, а не создавала второе (спека notifications, «Единственная
     * доставка без дублей»). Старые клиенты поле не передают — тогда tag не задаётся.
     */
    val id: String? = null,
    val title: String,
    val body: String,
    val token: String,
    val type: String? = null,
    val messageType: String? = null,
    val friendEmail: String? = null,
    @SerialName("friend_uid") val friendUid: String? = null,
    val date: String? = null,
    val drinkType: String? = null,
    val imageUrl: String? = null,
)

@Serializable
data class NotificationResponse(
    val success: Boolean,
    val messageId: String? = null,
    val error: String? = null
)
