package ru.alcoserver.services

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import ru.alcoserver.config.AppConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Fetches and caches the Firebase App Check JSON Web Key Set
 * (`https://firebaseappcheck.googleapis.com/v1/jwks`) and serves RSA keys to
 * [JWKSelector] lookups. Safe to call from a blocking dispatcher.
 */
class HttpAppCheckJwkSource(
    private val jwksUrl: String = AppConfig.appCheckJwksUrl,
    private val cacheDurationMinutes: Long = 60L,
) : JWKSource<SecurityContext> {

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    @Volatile
    private var cachedKeys: List<RSAKey>? = null

    @Volatile
    private var cachedAt: Long = 0L

    override fun get(jwkSelector: JWKSelector, context: SecurityContext?): MutableList<JWK> {
        var selected: List<JWK> = loadKeys().filter { jwkSelector.matcher.matches(it) }

        if (selected.isEmpty() && cachedKeys != null) {
            val refreshed = fetchKeys().also {
                cachedKeys = it
                cachedAt = System.currentTimeMillis()
            }
            selected = refreshed.filter { jwkSelector.matcher.matches(it) }
        }

        return selected.toMutableList()
    }

    private fun loadKeys(): List<RSAKey> {
        val cached = cachedKeys
        if (cached != null && System.currentTimeMillis() - cachedAt < cacheDurationMinutes * 60_000L) {
            return cached
        }
        return fetchKeys().also {
            cachedKeys = it
            cachedAt = System.currentTimeMillis()
        }
    }

    private fun fetchKeys(): List<RSAKey> {
        val request = HttpRequest.newBuilder(URI.create(jwksUrl))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("App Check JWKS request failed with status ${response.statusCode()}")
        }

        val jwks = Json.parseToJsonElement(response.body()).jsonObject
        val keysArray = jwks["keys"]?.jsonArray ?: return emptyList()

        return keysArray.mapNotNull { element ->
            try {
                RSAKey.parse(element.toString())
            } catch (_: Exception) {
                null
            }
        }
    }
}