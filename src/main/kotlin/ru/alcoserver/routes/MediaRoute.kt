package ru.alcoserver.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import ru.alcoserver.routes.APIConstants.API_PATH
import ru.alcoserver.services.AvatarSaveResult
import ru.alcoserver.services.MAX_AVATAR_BYTES
import ru.alcoserver.services.MediaStorageService

@Serializable
private data class AvatarUploadResponse(val url: String)

@Serializable
private data class MediaErrorResponse(val error: String)

/**
 * Публичная раздача медиа (`media/...`) без App Check и авторизации.
 *
 * Плагин App Check проверяет только пути под `api`, поэтому маршрут ниже автоматически
 * доступен системному загрузчику уведомлений. Кэш годовой и immutable: имя файла меняется
 * вместе с версией аватара, значит старое содержимое никогда не должно перезапрашиваться.
 */
fun Route.mediaRoute(mediaStorage: MediaStorageService) {
    get("/media/{path...}") {
        val segments = call.parameters.getAll("path").orEmpty()
        val file = mediaStorage.read(segments.joinToString("/"))
        if (file == null) {
            call.respond(HttpStatusCode.NotFound, MediaErrorResponse("not found"))
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
        call.respondBytes(file.bytes, ContentType.parse(file.contentType))
    }
}

/**
 * Приём зеркал аватара: `POST /api/v1/avatars/{uid}` (240 px) и `.../{uid}/push`
 * (64 px для шторки).
 *
 * Тело — сырой JPEG (не multipart): клиент шлёт уже сжатые байты. Доступ требует
 * Firebase ID-токен пользователя, и uid из пути обязан совпадать с uid токена, иначе
 * любой мог бы перезаписать чужой аватар.
 */
fun Route.avatarRoute(
    mediaStorage: MediaStorageService,
    verifyToken: (String?) -> String?,
) {
    route(API_PATH + "/avatars/{uid}") {
        post {
            call.handleAvatarUpload(mediaStorage, verifyToken, push = false)
        }
        post("/push") {
            call.handleAvatarUpload(mediaStorage, verifyToken, push = true)
        }
    }
}

private suspend fun ApplicationCall.handleAvatarUpload(
    mediaStorage: MediaStorageService,
    verifyToken: (String?) -> String?,
    push: Boolean,
) {
    val uid = parameters["uid"].orEmpty()

    val tokenUid = verifyToken(request.header(HttpHeaders.Authorization))
    if (tokenUid == null) {
        respond(HttpStatusCode.Unauthorized, MediaErrorResponse("missing or invalid id token"))
        return
    }
    if (tokenUid != uid) {
        respond(HttpStatusCode.Forbidden, MediaErrorResponse("uid mismatch"))
        return
    }
    val declaredLength = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (declaredLength != null && declaredLength > MAX_AVATAR_BYTES) {
        respond(HttpStatusCode.PayloadTooLarge, MediaErrorResponse("image too large"))
        return
    }

    val bytes = runCatching { receive<ByteArray>() }.getOrNull()
    if (bytes == null) {
        respond(HttpStatusCode.BadRequest, MediaErrorResponse("empty body"))
        return
    }

    when (val result = mediaStorage.saveAvatar(uid, push, bytes)) {
        is AvatarSaveResult.Saved -> respond(AvatarUploadResponse(result.url))
        AvatarSaveResult.InvalidName -> respond(
            HttpStatusCode.BadRequest,
            MediaErrorResponse("invalid uid"),
        )
        AvatarSaveResult.InvalidImage -> respond(
            HttpStatusCode.UnsupportedMediaType,
            MediaErrorResponse("jpeg expected"),
        )
        AvatarSaveResult.TooLarge -> respond(
            HttpStatusCode.PayloadTooLarge,
            MediaErrorResponse("image too large"),
        )
    }
}
