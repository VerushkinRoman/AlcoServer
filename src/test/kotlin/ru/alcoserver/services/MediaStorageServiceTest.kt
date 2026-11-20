package ru.alcoserver.services

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaStorageServiceTest {

    private val root: Path = Files.createTempDirectory("media-test")
    private val service = MediaStorageService(root, "https://alcoserver.ru:3344")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `a user has exactly one avatar file`() {
        val saved = service.saveAvatar("uid-1", bytes = jpegOf(Color.RED))

        assertEquals("https://alcoserver.ru:3344/media/avatars/uid-1.png", (saved as AvatarSaveResult.Saved).url)
        assertTrue(Files.isRegularFile(root.resolve("avatars/uid-1.png")))
        assertEquals(listOf("uid-1.png"), root.resolve("avatars").toFile().listNames())
    }

    @Test
    fun `client png frame is stored as is`() {
        val frame = pngOf(Color.RED, width = 64, height = 32)

        val saved = service.saveAvatar("uid-1", bytes = frame)

        assertTrue(saved is AvatarSaveResult.Saved)
        assertTrue(
            Files.readAllBytes(root.resolve("avatars/uid-1.png")).contentEquals(frame),
            "PNG-кадр 2:1 SHALL сохраняться без перерисовки и без потери альфы",
        )
    }

    @Test
    fun `avatar is stored as png circle with transparent corners`() {
        service.saveAvatar("uid-1", bytes = jpegOf(Color.RED, side = 32))

        val stored = ImageIO.read(root.resolve("avatars/uid-1.png").toFile())

        assertNotNull(stored, "файл остаётся читаемым PNG")
        assertEquals(32, stored.width)
        assertEquals(0, alphaAt(stored, x = 0, y = 0), "угол прозрачный — картинка круглая в шторке")
        assertEquals(255, alphaAt(stored, x = 16, y = 16), "центр круга непрозрачный")
    }

    @Test
    fun `saving a new avatar overwrites the previous file`() {
        service.saveAvatar("uid-1", bytes = jpegOf(Color.RED, side = 32))

        service.saveAvatar("uid-1", bytes = jpegOf(Color.BLUE, side = 32))

        val names = root.resolve("avatars").toFile().listNames()
        val stored = ImageIO.read(root.resolve("avatars/uid-1.png").toFile())

        assertEquals(
            listOf("uid-1.png"),
            names,
            "на пользователя остаётся ровно один файл картинки пуша",
        )
        assertNotNull(stored)
        val center = stored.getRGB(16, 16)
        assertTrue(
            (center and 0xFF) > 200 && ((center shr 16) and 0xFF) < 60,
            "текущая картинка — последняя загруженная (синяя, с учётом JPEG-сжатия)",
        )
    }

    @Test
    fun `invalid names are rejected before touching disk`() {
        listOf("../evil", "", "u/../x", "a".repeat(65)).forEach { uid ->
            assertEquals(AvatarSaveResult.InvalidName, service.saveAvatar(uid, bytes = jpegOf(Color.RED)))
        }
        assertTrue(!Files.exists(root.resolve("avatars")))
    }

    @Test
    fun `non jpeg and oversized payloads are rejected`() {
        assertEquals(
            AvatarSaveResult.InvalidImage,
            service.saveAvatar("uid-1", bytes = byteArrayOf(1, 2, 3, 4)),
        )
        assertEquals(
            AvatarSaveResult.InvalidImage,
            service.saveAvatar("uid-1", bytes = ByteArray(0)),
        )
        assertEquals(
            AvatarSaveResult.InvalidImage,
            service.saveAvatar("uid-1", bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
            "JPEG-сигнатура без декодируемого кадра не принимается",
        )

        val oversized = ByteArray(MAX_AVATAR_BYTES + 1)
        oversized[0] = 0xFF.toByte()
        oversized[1] = 0xD8.toByte()
        oversized[2] = 0xFF.toByte()
        assertEquals(
            AvatarSaveResult.TooLarge,
            service.saveAvatar("uid-1", bytes = oversized),
        )
    }

    @Test
    fun `read serves only images inside the media root`() {
        service.saveAvatar("uid-1", bytes = jpegOf(Color.RED))
        Files.createDirectories(root.resolve("public"))
        root.resolve("public/brand.png").writeBytes(byteArrayOf(1, 2, 3))
        root.resolve("secret.txt").writeBytes(byteArrayOf(9))

        val avatar = service.read("avatars/uid-1.png")
        assertNotNull(avatar)
        assertEquals("image/png", avatar.contentType)
        assertEquals("image/png", service.read("public/brand.png")?.contentType)

        assertNull(service.read("secret.txt"), "не-картинки не отдаются")
        assertNull(service.read("../media-test-outside.txt"), "выход за пределы каталога запрещён")
        assertNull(service.read("avatars/missing.png"))
    }

    /** Квадратный JPEG одного цвета — минимальный валидный вход для `saveAvatar`. */
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

    /** PNG произвольного размера — как его шлёт новый клиент (кадр 2:1 с прозрачностью). */
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

    private fun alphaAt(image: BufferedImage, x: Int, y: Int): Int = image.getRGB(x, y) ushr 24

    private fun java.io.File.listNames(): List<String> =
        listFiles().orEmpty().map { it.name }.sorted()
}
