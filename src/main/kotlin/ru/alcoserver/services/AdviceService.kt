package ru.alcoserver.services

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import ru.alcoserver.models.AppLocale
import ru.alcoserver.models.DrinkData
import ru.alcoserver.models.DrinkType
import ru.alcoserver.models.LLMDate
import java.io.File
import java.util.Properties
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatResponse(
    val choices: List<Choice>? = null,
    val error: ErrorResponse? = null
)

@Serializable
data class Choice(
    val message: ResponseMessage?
)

@Serializable
data class ResponseMessage(
    val content: String?
)

@Serializable
data class ErrorResponse(
    val message: String,
    val code: Int? = null
)

@Suppress("PropertyName")
@Serializable
private data class DailyAdviceConfig(
    val system_prompt: String,
    val user_prompt_template: String
)

class AdviceService(
    private val rateLimiterService: RateLimiterService = RateLimiterService()
) {
    private val logger = LoggerFactory.getLogger(AdviceService::class.java)
    private val llmUserPassword: String

    private val models = listOf(
        "mistral/mistral-large-latest",
        "mistral/mistral-medium-latest",
        "mistral/mistral-small-latest",
        "mistral/codestral-latest",
        "mistral/open-mistral-nemo",
        "mistral/ministral-3b-latest",
    )

    private data class DailyAdvicePrompts(
        val systemPrompt: String?,
        val userPromptTemplate: String?
    )

    private val dailyAdvicePrompts: DailyAdvicePrompts? by lazy { loadDailyAdvicePrompts() }

    private fun loadDailyAdvicePrompts(): DailyAdvicePrompts? {
        return try {
            val file = File("daily_advice_prompts.json")
            if (!file.exists()) {
                logger.info("daily_advice_prompts.json not found, daily advice disabled")
                return null
            }
            val content = file.readText()
            val config = Json.decodeFromString(serializer<DailyAdviceConfig>(), content)
            logger.info("Loaded daily advice prompts from file")
            DailyAdvicePrompts(
                systemPrompt = config.system_prompt,
                userPromptTemplate = config.user_prompt_template
            )
        } catch (e: Exception) {
            logger.warn("Failed to load daily_advice_prompts.json: ${e.message}")
            null
        }
    }

    fun isTodaySober(drinkData: List<DrinkData>): Boolean {
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        val todayEpochDay = today.toEpochDays()
        return drinkData.none { it.date == todayEpochDay }
    }

    fun generateSummaries(drinkData: List<DrinkData>, locale: AppLocale): String {
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        val yesterday = today.minus(1, DateTimeUnit.DAY)
        val weekAgo = today.minus(7, DateTimeUnit.DAY)
        val monthAgo = today.minus(30, DateTimeUnit.DAY)

        val isRus = locale == AppLocale.Rus

        val yesterdayData = drinkData.filter { it.date == yesterday.toEpochDays() }
        val weekData = drinkData.filter {
            val date = it.date
            date > weekAgo.toEpochDays() && date < today.toEpochDays()
        }
        val monthData = drinkData.filter {
            val date = it.date
            date > monthAgo.toEpochDays() && date < today.toEpochDays()
        }

        val summaries = mutableListOf<String>()

        if (yesterdayData.isNotEmpty()) {
            val drinkTypes = yesterdayData.map { DrinkType.fromString(it.drinkType) }
            val hasFull = drinkTypes.any { it == DrinkType.Full }
            val hasHalf = drinkTypes.any { it == DrinkType.Half }
            val desc = when {
                hasFull && hasHalf -> if (isRus) "пили основательно" else "drank heavily"
                hasFull -> if (isRus) "пили много" else "drank a lot"
                else -> if (isRus) "пили умеренно" else "drank moderately"
            }
            summaries.add(if (isRus) "Вчера вы $desc." else "Yesterday you $desc.")
        } else {
            summaries.add(if (isRus) "Вчера вы были трезвы." else "Yesterday you were sober.")
        }

        val weekDrinkCount = weekData.size
        val weekDays = 7
        val weekSoberDays = weekDays - weekDrinkCount
        if (weekDrinkCount > 0) {
            summaries.add(
                if (isRus) "На прошлой неделе вы пили $weekDrinkCount из $weekDays дней, трезвых: $weekSoberDays."
                else "Last week you drank $weekDrinkCount out of $weekDays days, sober: $weekSoberDays."
            )
        } else {
            summaries.add(
                if (isRus) "На прошлой неделе вы были трезвы все $weekDays дней!"
                else "Last week you were sober all $weekDays days!"
            )
        }

        val monthDrinkCount = monthData.size
        val monthDays = 30
        val monthSoberDays = monthDays - monthDrinkCount
        if (monthDrinkCount > 0) {
            summaries.add(
                if (isRus) "За месяц: выпивали $monthDrinkCount раз, трезвых дней — $monthSoberDays."
                else "This month: drank $monthDrinkCount times, sober days — $monthSoberDays."
            )
        } else {
            summaries.add(
                if (isRus) "За месяц вы были трезвы все $monthDays дней!"
                else "This month you were sober all $monthDays days!"
            )
        }

        return summaries.joinToString(" ")
    }

    fun getUserPrompt(
        locale: AppLocale,
        dataString: String,
        summaries: String
    ): String {
        val responseLanguage = if (locale == AppLocale.Rus) "РУССКИЙ" else "АНГЛИЙСКИЙ"
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        val dayOfWeek = today.dayOfWeek

        val template = dailyAdvicePrompts?.userPromptTemplate
            ?: return "[NO TEMPLATE]"

        return template
            .replace("{response_language}", responseLanguage)
            .replace("{today}", today.toString())
            .replace("{day_of_week}", dayOfWeek.toString())
            .replace("{data_string}", dataString)
            .replace("{summaries}", summaries)
    }

    private val notificationTitles = mapOf(
        AppLocale.Rus to "Совет Дня! 🎯",
        AppLocale.Eng to "Daily Advice! 🎯"
    )

    init {
        llmUserPassword = loadLlmUserPassword()
    }

    private fun loadLlmUserPassword(): String {
        return try {
            val propsFile = File("keys.properties")
            if (propsFile.exists()) {
                val props = Properties()
                props.load(propsFile.inputStream())
                props.getProperty("llm_user_pwd") ?: throw IllegalStateException("llm_user_pwd not found in keys.properties")
            } else {
                System.getenv("LLM_USER_PWD") ?: throw IllegalStateException(
                    "llm_user_pwd not found. Please create keys.properties file or set LLM_USER_PWD environment variable"
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to load llm_user_pwd", e)
            throw e
        }
    }

    suspend fun generateAdviceWithRateLimit(
        llmDates: List<LLMDate>,
        locale: AppLocale
    ): Pair<RateLimitResult, CompletableDeferred<String?>> {
        val deferred = CompletableDeferred<String?>()

        val result =
            rateLimiterService.executeWithRateLimit(Pair(llmDates, locale)) { (dates, loc) ->
                try {
                    val advice = generateAdvice(dates, loc)
                    deferred.complete(advice)
                } catch (e: Exception) {
                    deferred.completeExceptionally(e)
                }
            }

        if (result is RateLimitResult.Failed) {
            deferred.completeExceptionally(RuntimeException(result.error))
        }

        return Pair(result, deferred)
    }

    suspend fun generateAdvice(
        drinkData: List<LLMDate>,
        locale: AppLocale = AppLocale.Rus,
        drinkDataRaw: List<DrinkData>? = null
    ): String? {
        val prompts = dailyAdvicePrompts
        if (prompts?.systemPrompt == null || prompts.userPromptTemplate == null) {
            logger.info("Daily advice prompts not loaded, skipping advice generation")
            return null
        }

        val rawData = drinkDataRaw ?: drinkData.map { drink ->
            DrinkData(
                date = LocalDate.parse(drink.date).toEpochDays(),
                drinkType = drink.drinkType.value
            )
        }

        if (!isTodaySober(rawData)) {
            logger.info("Today is not sober, skipping daily advice")
            return null
        }

        val dataString = drinkData.joinToString("\n") { it.toString() }
        val summaries = generateSummaries(rawData, locale)
        val userPrompt = getUserPrompt(locale, dataString, summaries)

        for (model in models) {
            try {
                logger.info("Trying model: $model for locale: $locale")
                val response = callLlmRouter(model, prompts.systemPrompt, userPrompt)
                logger.info("Successfully got response from model: $model")
                return response
            } catch (e: Exception) {
                logger.warn("Model $model failed: ${e.message}")
                if (model == models.last()) {
                    throw Exception("All models failed. Last error: ${e.message}")
                }
            }
        }

        throw Exception("No models available")
    }

    fun getNotificationTitle(locale: AppLocale): String {
        return notificationTitles[locale] ?: notificationTitles[AppLocale.Rus]!!
    }

    private val baseUrl = "https://alcoserver.ru:4001"

    private suspend fun callLlmRouter(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        retryCount: Int = 0,
    ): String {
        val messages = listOf(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = userPrompt)
        )

        val request = ChatRequest(
            model = model,
            messages = messages
        )

        val client = HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }

            install(Auth) {
                basic {
                    credentials {
                        BasicAuthCredentials(
                            username = "llm_user",
                            password = llmUserPassword,
                        )
                    }
                }
            }

            install(HttpTimeout) {
                socketTimeoutMillis = 300.seconds.inWholeMilliseconds
                connectTimeoutMillis = 30.seconds.inWholeMilliseconds
                requestTimeoutMillis = 300.seconds.inWholeMilliseconds
            }
        }

        return withContext(Dispatchers.IO) {
            client.use { httpClient ->
                val response = httpClient.post("$baseUrl/chat/completions") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

                if (response.status.value == 429) {
                    if (retryCount < 3) {
                        logger.warn("⚠️ 429 Too Many Requests, retry $retryCount in 5s")
                        delay(5.seconds)
                        return@withContext callLlmRouter(model, systemPrompt, userPrompt, retryCount + 1)
                    }
                    logger.warn("⚠️ 429 исчерпал ретраи, перехожу к следующей модели")
                    throw Exception("429 Too Many Requests after $retryCount retries")
                }

                if (!response.status.isSuccess()) {
                    val bodyText = response.bodyAsText()
                    logger.warn("⚠️ $model | ${response.status} | ${bodyText.take(200)}")

                    if (response.status.value == 502 && retryCount < 1) {
                        delay(3.seconds)
                        return@withContext callLlmRouter(model, systemPrompt, userPrompt, retryCount + 1)
                    }

                    throw Exception("API error: ${response.status} ${bodyText.take(200)}")
                }

                val body = response.body<ChatResponse>()
                if (body.error != null) {
                    throw Exception("API Error: ${body.error.message}")
                }

                body.choices?.firstOrNull()?.message?.content
                    ?: throw Exception("Empty API response")
            }
        }
    }

    fun getRateLimiterStats(): QueueStats {
        return rateLimiterService.getQueueStats()
    }

    fun shutdown() {
        rateLimiterService.shutdown()
    }
}
