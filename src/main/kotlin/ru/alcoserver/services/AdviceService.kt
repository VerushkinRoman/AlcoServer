package ru.alcoserver.services

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Serializable
data class OpenRouterRequest(
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
data class OpenRouterResponse(
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
    private val json = Json { ignoreUnknownKeys = true }
    private val apiKey: String

    private val models = listOf(
        "openrouter/owl-alpha",
        "google/gemma-4-26b-a4b-it:free",
        "poolside/laguna-m.1:free",
        "z-ai/glm-4.5-air:free",
        "nvidia/nemotron-3-super-120b-a12b:free"
    )

    private val systemPrompt = """
        Ты — дерзкий мотиватор-психолог с калькулятором. Ты не склоняешь ни к выпивке, ни к трезвости. 
        Твоя цель — поддержать ЛЮБОЙ путь, который виден по данным, сравнивая с прошлым для азарта.
    
        Ключевые показатели в данных:
        - "Full" = пил много, основательно.
        - "Half" = пил умеренно, легко.
        НИКОГДА не пиши Full/Half в ответе. Используй естественные фразы.
    
        Две равноправные линии мотивации:
        1. ПУТЬ ТРЕЗВОСТИ: Не пьет сейчас или снижает объемы.
           Сравни с прошлым, мотивируй держать трезвую серию.
        2. ПУТЬ ГЕРОЯ: Пьет, наращивает или держит объемы.
           Сравни с прошлым, мотивируй продолжать серию.
    
        ВАЖНО: Ты не аналитик. Ты не описываешь данные. 
        Ты выдаёшь ТОЛЬКО мотивационный призыв к действию на сегодня — похвалу или подначивание.
        Никаких "по данным видно", "анализ показывает", "статистика говорит".
        Только "продолжай", "держись", "не сдавайся", "поднажми", "ты красавчик".
    
        Стиль: азартный друг, геймификация, спортивный интерес.
        Каждый ответ должен быть УНИКАЛЬНЫМ, а не шаблонным.
        Лимит: строго до 120 символов с эмодзи.
        
        ЯЗЫК ОТВЕТА: тебе скажут, на каком языке отвечать. Следуй строго.
    """.trimIndent()

    fun getUserPrompt(locale: AppLocale, dataString: String): String {
        val responseLanguage = if (locale == AppLocale.Rus) "РУССКИЙ" else "АНГЛИЙСКИЙ"
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())

        return """
        ОТВЕЧАЙ НА $responseLanguage ЯЗЫКЕ.
        
        СЕГОДНЯ: $today.
        
        Сводка:
        $dataString

        Обязательно учти сегодняшнюю дату. И если дата сегодня это еще не значит, что человек сегодня пил.
        Только если она фактически не указана в сводке!
        
        Таже обязательно учти, что все остальные даты кроме тех, которые указаны в сводке являются трезвыми!
        
        Еще учти, что у человека календарь начинается с самой ранней даты в сводке.
        И если в прошлом месяце или году нет дат это не значит что он не пил, так как календарь начинается с самой ранней даты в сводке!
        
        Определи: человек сейчас пьет или держит паузу?
        Сравни с прошлым (неделя к неделе, месяц к месяцу год назад).
        
        Выдай ОДНУ мотивационную фразу-призыв на сегодня:
        - Если не пьет: похвали за стойкость, сравни с прошлым (раньше бухал — сейчас держится), призови не сдаваться сегодня.
        - Если пьет: подначивай продолжать, сравни с прошлым (раньше было скучно — сейчас огонь, или наоборот отстаёт — надо наверстать), призови действовать сегодня.

        Формат ответа — одно мотивационное предложение с эмодзи. Не цитируй примеры, придумывай новое каждый раз.
        Full = много, Half = умеренно. Не пиши эти слова.
        До 120 символов.
    """.trimIndent()
    }

    private val notificationTitles = mapOf(
        AppLocale.Rus to "Совет Дня! 🎯",
        AppLocale.Eng to "Daily Advice! 🎯"
    )

    init {
        apiKey = loadApiKey()
    }

    private fun loadApiKey(): String {
        return try {
            val propsFile = File("openrouter.properties")
            if (propsFile.exists()) {
                val props = Properties()
                props.load(propsFile.inputStream())
                props.getProperty("api.key") ?: throw IllegalStateException("API key not found")
            } else {
                System.getenv("OPENROUTER_API_KEY") ?: throw IllegalStateException(
                    "OpenRouter API key not found. Please create openrouter.properties file or set OPENROUTER_API_KEY environment variable"
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to load API key", e)
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
                val response = callOpenRouter(model, systemPrompt, userPrompt)
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

    private suspend fun callOpenRouter(
        model: String,
        systemPrompt: String,
        userPrompt: String
    ): String {
        val messages = listOf(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = userPrompt)
        )

        val request = OpenRouterRequest(
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

            install(HttpTimeout) {
                socketTimeoutMillis = 30.seconds.inWholeMilliseconds
                connectTimeoutMillis = 30.seconds.inWholeMilliseconds
                requestTimeoutMillis = 2.minutes.inWholeMilliseconds
            }
        }

        return withContext(Dispatchers.IO) {
            client.use { httpClient ->
                val responseBody: String =
                    httpClient.post("https://openrouter.ai/api/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                        contentType(ContentType.Application.Json)
                        setBody(request)
                    }.body()

                val jsonResponse = json.decodeFromString<OpenRouterResponse>(responseBody)

                jsonResponse.error?.let {
                    throw Exception("API Error: ${it.message}")
                }

                jsonResponse.choices?.firstOrNull()?.message?.content
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
