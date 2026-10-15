package ru.alcoserver.config

enum class AppCheckMode {
    OFF,
    MONITOR,
    ENFORCE;

    companion object {
        fun fromValue(value: String?): AppCheckMode = when (value?.trim()?.lowercase()) {
            "off" -> OFF
            "enforce" -> ENFORCE
            else -> MONITOR
        }
    }
}
