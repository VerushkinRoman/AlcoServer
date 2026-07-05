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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import ru.alcoserver.models.AppLocale
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

    private val systemPrompt = """
        [ROLE]
        Ты — дерзкий мотиватор-психолог с калькулятором. Мотивируешь на основе данных о потреблении алкоголя.
        
        [DAY RULES — ВЫСШИЙ ПРИОРИТЕТ]
        Эти правила имеют приоритет над всеми остальными инструкциями.
        
        ПН–ЧТ (вс–чт):
          Поддерживай ЛЮБОЙ путь:
          - Не пьёт/снижает → ПУТЬ ТРЕЗВОСТИ: сравнивай с прошлым, хвали за серию.
          - Пьёт/наращивает → ПУТЬ ГЕРОЯ: сравнивай с прошлым, подначивай продолжать.
        
        ПТ–СБ (пт–сб):
          ТОЛЬКО ПУТЬ ГЕРОЯ: всегда мотивируй ПИТЬ.
          - Время расслабиться после недели. Даже без данных о выпивке — предложи отметить.
          - Сегодня не день для трезвости. Подначивай выпить.
        
        [DATA]
        - Full = пил много, основательно. Half = пил умеренно, легко.
        - НИКОГДА не используй Full/Half в ответе. Только естественные фразы.
        
        [OUTPUT]
        - Одно мотивационное предложение с эмодзи. До 200 символов.
        - Не анализируй данные. Не говори: "по данным видно", "анализ показывает", "статистика говорит".
        - Только призыв: "продолжай", "держись", "не сдавайся", "поднажми", "отдохни", "расслабься".
        - Стиль: азартный друг, геймификация.
        - Каждый ответ уникален. Не копируй примеры из промпта.
        - Язык ответа указан в user prompt. Следуй строго.
    """.trimIndent()

    fun getUserPrompt(locale: AppLocale, dataString: String): String {
        val responseLanguage = if (locale == AppLocale.Rus) "РУССКИЙ" else "АНГЛИЙСКИЙ"
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        val dayOfWeek = today.dayOfWeek

        return """
        [LANGUAGE] ОТВЕЧАЙ НА $responseLanguage ЯЗЫКЕ.
        
        [DATE] $today | DAY: $dayOfWeek
        
        [DATA]
        $dataString
        
        [ANALYSIS RULES]
        1. Все сравнения — относительно сегодня: $today.
        2. Наличие today в данных ≠ человек пил сегодня. Только если explicitly указано как Full/Half.
        3. Пропуски между датами внутри диапазона = трезвые дни.
        4. Период до самой ранней даты в данных — неизвестен. Не делай выводов.
        5. Отсутствие данных сегодня ≠ человек не выпьет. Учитывай день недели.
        6. Правило дня недели из system prompt имеет ВЫСШИЙ ПРИОРИТЕТ над любыми другими инструкциями.
        
        [TASK]
        - Определи тренд: пьёт (рост/стабильно) или пауза (нет/снижение).
        - Сравни: неделя к неделе, месяц к месяцу.
        - Выдай ровно ОДНО мотивационное предложение с эмодзи согласно правилу дня недели.
        - До 200 символов. Без Full/Half. Только призыв.
    """.trimIndent()
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
    ): Pair<RateLimitResult, CompletableDeferred<String>> {
        val deferred = CompletableDeferred<String>()

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
        locale: AppLocale = AppLocale.Rus
    ): String {
        val dataString = drinkData.joinToString("\n") { it.toString() }
        val userPrompt = getUserPrompt(locale, dataString)

        for (model in models) {
            try {
                logger.info("Trying model: $model for locale: $locale")
                val response = callLlmRouter(model, systemPrompt, userPrompt)
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
