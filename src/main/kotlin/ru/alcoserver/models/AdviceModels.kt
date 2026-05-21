package ru.alcoserver.models

import kotlinx.serialization.Serializable

@Serializable
data class AdviceRequest(
    val token: String,
    val locale: String = "Ru",
    val data: List<DrinkData>
)

@Serializable
data class DrinkData(
    val date: Long,
    val drinkType: String
)

enum class AppLocale(val value: String) {
    Rus("Ru"),
    Eng("En");

    companion object {
        fun fromValue(value: String): AppLocale {
            return entries.find { it.value.equals(value, ignoreCase = true) } ?: Rus
        }
    }
}

enum class DrinkType(val value: String) {
    Full("Full"),
    Half("Half");

    companion object {
        fun fromString(value: String): DrinkType {
            return entries.find { it.value.equals(value, ignoreCase = true) } ?: Full
        }
    }
}

data class LLMDate(
    val date: String,
    val drinkType: DrinkType
) {
    override fun toString(): String {
        return "Date: $date, Type: $drinkType"
    }
}
