package ru.alcoserver.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.alcoserver.models.IntegrityInvalidReason
import ru.alcoserver.models.IntegrityRequest
import ru.alcoserver.models.IntegrityResult
import ru.alcoserver.routes.APIConstants.API_PATH
import ru.alcoserver.services.IntegrityVerifier

class IntegrityRouteTest {

    @Test
    fun `android token routes to verifier and returns successful response`() = integrityTestApp { client, verifier ->
        val response = client.post("$API_PATH/integrity") {
            contentType(ContentType.Application.Json)
            setBody(IntegrityRequest(token = "android-token", platform = "android"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("android", verifier.received.single().platform)
        val body = Json.parseToJsonElement(response.body<String>()).jsonObject
        assertEquals(true, body["valid"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("success", body["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("PLAY_RECOGNIZED", body["appVerdict"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `request without platform is treated as Android for backward compatibility`() =
        integrityTestApp { client, verifier ->
            val response = client.post("$API_PATH/integrity") {
                contentType(ContentType.Application.Json)
                setBody(IntegrityRequest(token = "legacy-token"))
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertNull(verifier.received.single().platform)
        }

    @Test
    fun `ios token routes to verifier and returns success`() = integrityTestApp { client, verifier ->
        val response = client.post("$API_PATH/integrity") {
            contentType(ContentType.Application.Json)
            setBody(IntegrityRequest(token = "ios-app-check-token", platform = "ios"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ios", verifier.received.single().platform)
        val body = Json.parseToJsonElement(response.body<String>()).jsonObject
        assertEquals(true, body["valid"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `ios invalid token returns invalid response body`() = integrityTestApp(
        verifier = FakeIntegrityVerifier(
            IntegrityResult.Invalid(IntegrityInvalidReason.AppNotValid("App Check verification failed"))
        )
    ) { client, _ ->
        val response = client.post("$API_PATH/integrity") {
            contentType(ContentType.Application.Json)
            setBody(IntegrityRequest(token = "bad-token", platform = "ios"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.body<String>()).jsonObject
        assertEquals(false, body["valid"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("invalid", body["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("app_not_valid", body["error"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `unknown platform returns BadRequest without calling verifier`() = integrityTestApp { client, verifier ->
        val response = client.post("$API_PATH/integrity") {
            contentType(ContentType.Application.Json)
            setBody(IntegrityRequest(token = "token", platform = "windows"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(verifier.received.isEmpty())
    }

    @Test
    fun `empty platform returns BadRequest without calling verifier`() = integrityTestApp { client, verifier ->
        val response = client.post("$API_PATH/integrity") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"token","platform":""}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(verifier.received.isEmpty())
    }

    @Test
    fun `blank token returns BadRequest`() = integrityTestApp { client, verifier ->
        val response = client.post("$API_PATH/integrity") {
            contentType(ContentType.Application.Json)
            setBody(IntegrityRequest(token = "   "))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(verifier.received.isEmpty())
    }

    @Test
    fun `malformed body returns BadRequest`() = integrityTestApp { client, verifier ->
        val response = client.post("$API_PATH/integrity") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(verifier.received.isEmpty())
    }

    private class FakeIntegrityVerifier(
        var result: IntegrityResult,
    ) : IntegrityVerifier {
        val received: MutableList<IntegrityRequest> = mutableListOf()

        override suspend fun verify(request: IntegrityRequest): IntegrityResult {
            received.add(request)
            return result
        }
    }

    private fun integrityTestApp(
        verifier: FakeIntegrityVerifier = FakeIntegrityVerifier(IntegrityResult.Valid),
        block: suspend (HttpClient, FakeIntegrityVerifier) -> Unit,
    ) = testApplication {
        application {
            install(ServerContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
            routing { integrityRoute(verifier) }
        }
        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
        }
        block(client, verifier)
    }
}