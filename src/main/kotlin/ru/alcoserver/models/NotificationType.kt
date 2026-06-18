package ru.alcoserver.models

enum class NotificationType(val value: String) {
    ADVICE("advice"),
    FRIEND_ACTIVITY("friend_activity"),
    REMINDER("reminder"),
    SYSTEM("system"),
    DEFAULT("default");
}
