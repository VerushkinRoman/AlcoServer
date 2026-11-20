package ru.alcoserver.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import io.ktor.server.testing.testApplication
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import ru.alcoserver.services.MediaStorageService

class MediaRouteTest {

    private val root: Path = Files.createTempDirectory("media-route-test")
    private val media = MediaStorageService(root, "https://alcoserver.ru:3344")
    private val jpeg = jpegOf(Color.RED)

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `upload returns single public url and file is served without auth`() = mediaApp { client ->
        val frame = pngOf(Color.RED, width = 64, height = 32)
        val response = client.post("/api/v1/avatars/uid-1") {
            contentType(ContentType.Image.PNG)
            header(HttpHeaders.Authorization, "Bearer valid-token")
            setBody(frame)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("/media/avatars/uid-1.png"))

        val served = client.get("/media/avatars/uid-1.png")

        assertEquals(HttpStatusCode.OK, served.status)
        assertEquals("image/png", served.headers[HttpHeaders.ContentType])
        assertEquals("public, max-age=31536000, immutable", served.headers[HttpHeaders.CacheControl])
        assertTrue(
            served.bodyAsBytes().contentEquals(frame),
            "собранный клиентом кадр отдаётся без перерисовки",
        )
    }

    @Test
    fun `legacy jpeg upload still produces a round png`() = mediaApp { client ->
        client.post("/api/v1/avatars/uid-1") {
            contentType(ContentType.Image.JPEG)
            header(HttpHeaders.Authorization, "Bearer valid-token")
            setBody(jpeg)
        }
        val body = client.get("/media/avatars/uid-1.png").bodyAsBytes()

        assertEquals(
            listOf(0x89, 0x50, 0x4E, 0x47),
            body.take(4).map { it.toInt() and 0xFF },
            "старые сборки получают PNG с прозрачностью вместо JPEG",
        )
    }

    @Test
    fun `push route is gone`() = mediaApp { client ->
        val response = client.post("/api/v1/avatars/uid-1/push") {
            contentType(ContentType.Image.JPEG)
            header(HttpHeaders.Authorization, "Bearer valid-token")
            setBody(jpeg)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `head reports whether the avatar file exists`() = mediaApp { client ->
        assertEquals(HttpStatusCode.NotFound, client.head("/media/avatars/uid-1.png").status)

        client.post("/api/v1/avatars/uid-1") {
            contentType(ContentType.Image.PNG)
            header(HttpHeaders.Authorization, "Bearer valid-token")
            setBody(pngOf(Color.RED, width = 64, height = 32))
        }

        val head = client.head("/media/avatars/uid-1.png")

        assertEquals(HttpStatusCode.OK, head.status)
        assertEquals("public, max-age=31536000, immutable", head.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `new avatar overwrites the same url`() = mediaApp { client ->
        client.post("/api/v1/avatars/uid-1") {
            contentType(ContentType.Image.JPEG)
            header(HttpHeaders.Authorization, "Bearer valid-token")
            setBody(jpeg)
        }
        val upload = client.post("/api/v1/avatars/uid-1") {
            contentType(ContentType.Image.JPEG)
            header(HttpHeaders.Authorization, "Bearer valid-token")
            setBody(jpegOf(Color.BLUE))
        }
        val served = client.get("/media/avatars/uid-1.png")

        assertEquals(HttpStatusCode.OK, upload.status)
        assertEquals(HttpStatusCode.OK, served.status)
        assertEquals("image/png", served.headers[HttpHeaders.ContentType])
        assertEquals(
            listOf("uid-1.png"),
            root.resolve("avatars").toFile().listFiles().orEmpty().map { it.name },
            "версия в пути не участвует: файл перезаписан на месте",
        )
    }

    @Test
    fun `upload without id token is rejected`() = mediaApp { client ->
        val response = client.post("/api/v1/avatars/uid-1") {
            contentType(ContentType.Image.JPEG)
            setBody(jpeg)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(!Files.exists(root.resolve("avatars/uid-1.png")))
    }

    @Test
    fun `upload for another uid is forbidden`() = mediaApp(verifyToken = { "uid-2" }) { client ->
        val response = client.post("/api/v1/avatars/uid-1") {
            contentType(ContentType.Image.JPEG)
            header(HttpHeaders.Authorization, "Bearer valid-token")
            setBody(jpeg)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(!Files.exists(root.resolve("avatars/uid-1.png")))
    }

    @Test
    fun `non image body is rejected`() = mediaApp { client ->
        val response = client.post("/api/v1/avatars/uid-1") {
            contentType(ContentType.Image.PNG)
            header(HttpHeaders.Authorization, "Bearer valid-token")
            setBody(byteArrayOf(1, 2, 3, 4))
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `unknown media path is 404`() = mediaApp { client ->
        val response = client.get("/media/avatars/missing.png")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    /** PNG произвольного размера — так клиент присылает собранный кадр 2:1. */
    private fun pngOf(color: Color, width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = color
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return output.toByteArray()
    }

    /** Квадратный JPEG одного цвета — валидный вход для загрузки аватара. */
    private fun jpegOf(color: Color, side: Int = 8): ByteArray {
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = color
        graphics.fillRect(0, 0, side, side)
        graphics.dispose()
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", output)
        return output.toByteArray()
    }

    private fun mediaApp(
        verifyToken: (String?) -> String? = { token -> token?.takeIf { it.contains("valid") }?.let { "uid-1" } },
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        application {
            install(ServerContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            routing {
                mediaRoute(media)
                avatarRoute(media, verifyToken)
            }
        }
        block(client)
    }

    @Test
    fun `public assets are served from media public folder`() = mediaApp { client ->
        Files.createDirectories(root.resolve("public"))
        Files.write(root.resolve("public/brand.png"), byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

        val response = client.get("/media/public/brand.png")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("image/png", response.headers[HttpHeaders.ContentType])
        assertNotNull(response.headers[HttpHeaders.CacheControl])
    }
}
