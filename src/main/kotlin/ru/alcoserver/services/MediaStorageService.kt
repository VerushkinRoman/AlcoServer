package ru.alcoserver.services

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Предел размера одного файла аватара: компактная версия 240 px весит единицы килобайт. */
internal const val MAX_AVATAR_BYTES = 2 * 1024 * 1024

private val UID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

/** Результат сохранения файла аватара. */
sealed interface AvatarSaveResult {
    /** Файл записан; [url] — публичный адрес, который можно отдать в пуш. */
    data class Saved(val url: String) : AvatarSaveResult

    /** uid не проходит по формату — путь не строится. */
    data object InvalidName : AvatarSaveResult

    /** Байты не JPEG или пусты. */
    data object InvalidImage : AvatarSaveResult

    /** Файл больше [MAX_AVATAR_BYTES]. */
    data object TooLarge : AvatarSaveResult
}

/** Файл, готовый к отдаче по HTTP: содержимое и MIME-тип по расширению. */
data class MediaFile(
    val bytes: ByteArray,
    val contentType: String,
)

/**
 * Хранилище медиа на диске сервера вместо Firebase Storage (Blaze не используется).
 *
 * Раскладка на диске повторяет публичные пути:
 * - `avatars/{uid}.png` и `avatars/{uid}-push.png` — зеркала аватаров, по одному файлу на вид;
 * - `public/brand.png`, `public/default-avatar.png` — статичные картинки пушей, кладутся вручную.
 *
 * Аватар — PNG с прозрачными углами: клиент шлёт квадратный JPEG, сервер вырезает круг,
 * поэтому в шторке уведомления картинка круглая, как аватар в приложении.
 *
 * Версии аватара на диске нет: новый аватар перезаписывает прежний файл того же вида, поэтому
 * один пользователь занимает максимум два файла. Актуальность картинки в кэшах обеспечивает
 * клиент параметром `?v={version}` в URL — сам сервер путь версии не знает.
 *
 * Пути под `/media` отдаются публично без авторизации: картинку для `notification.image`
 * скачивает Google Play services / APNs, у них нет ни Firebase-токена, ни App Check.
 */
class MediaStorageService(
    root: Path,
    baseUrl: String,
) {
    private val mediaRoot: Path = root.toAbsolutePath().normalize()
    private val publicBase: String = baseUrl.trimEnd('/')

    fun avatarUrl(uid: String, push: Boolean): String =
        "$publicBase/media/avatars/$uid" + if (push) "-push.png" else ".png"

    fun assetUrl(fileName: String): String = "$publicBase/media/public/$fileName"

    /**
     * Принимает квадратный JPEG, вырезает круг и кладёт PNG в `avatars/{uid}[-push].png`
     * атомарной записью поверх прежнего файла: смена аватара не плодит файлов, а последняя
     * успешная загрузка всегда становится текущей.
     */
    fun saveAvatar(uid: String, push: Boolean, bytes: ByteArray): AvatarSaveResult {
        if (!UID_PATTERN.matches(uid)) return AvatarSaveResult.InvalidName
        if (bytes.isEmpty() || !isJpeg(bytes)) return AvatarSaveResult.InvalidImage
        if (bytes.size > MAX_AVATAR_BYTES) return AvatarSaveResult.TooLarge

        val png = circlePng(bytes) ?: return AvatarSaveResult.InvalidImage

        val directory = mediaRoot.resolve("avatars")
        Files.createDirectories(directory)

        val fileName = uid + if (push) "-push.png" else ".png"
        val target = directory.resolve(fileName)
        val temp = directory.resolve(".$fileName.${UUID.randomUUID()}.tmp")
        Files.write(temp, png)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

        return AvatarSaveResult.Saved(avatarUrl(uid, push))
    }

    /**
     * Читает файл по относительному пути под `/media`. Возвращает `null` для выхода за
     * пределы каталога и для не-картинок: отдаются только `.jpg`/`.png`.
     */
    fun read(relativePath: String): MediaFile? {
        if (relativePath.isBlank() || relativePath.contains('\\')) return null
        val contentType = when {
            relativePath.endsWith(".jpg") -> "image/jpeg"
            relativePath.endsWith(".png") -> "image/png"
            else -> return null
        }
        val resolved = mediaRoot.resolve(relativePath).normalize()
        if (!resolved.startsWith(mediaRoot) || !Files.isRegularFile(resolved)) return null
        return MediaFile(bytes = Files.readAllBytes(resolved), contentType = contentType)
    }

    /**
     * Декодирует присланный JPEG, обрезает его до квадрата по центру и вырезает круг.
     * `null` — байты не декодируются как изображение.
     */
    private fun circlePng(bytes: ByteArray): ByteArray? {
        val source = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val side = minOf(source.width, source.height)
        if (side <= 0) return null
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2

        val masked = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        val graphics = masked.createGraphics()
        try {
            graphics.drawImage(source, 0, 0, side, side, left, top, left + side, top + side, null)
        } finally {
            graphics.dispose()
        }
        applyCircleMask(masked)

        val output = ByteArrayOutputStream()
        if (!ImageIO.write(masked, "png", output)) return null
        return output.toByteArray()
    }

    /**
     * Заменяет alpha круговой маской с мягким краем в 1 px.
     *
     * Пиксели вне круга получают нулевую непрозрачность, пиксели на границе — частичную,
     * поэтому край круга гладкий даже без сглаживания растеризатора.
     */
    private fun applyCircleMask(image: BufferedImage) {
        val radius = image.width / 2.0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val distance = hypot(x + 0.5 - radius, y + 0.5 - radius)
                val coverage = (radius - distance + 0.5).coerceIn(0.0, 1.0)
                val alpha = (255 * coverage).roundToInt()
                val argb = image.getRGB(x, y)
                image.setRGB(x, y, (alpha shl 24) or (argb and 0xFFFFFF))
            }
        }
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size > 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
}
