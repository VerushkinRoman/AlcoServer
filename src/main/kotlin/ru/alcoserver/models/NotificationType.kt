package ru.alcoserver.models

enum class NotificationType(val value: String, val channelId: String) {
    ADVICE("advice", "advice_channel"),
    FRIEND_ACTIVITY("friend_activity", "friend_activity_channel"),
    PAYMENT_SUCCESS("payment_success", "payment_success_channel"),
    DEFAULT("default", "default_channel");
}
