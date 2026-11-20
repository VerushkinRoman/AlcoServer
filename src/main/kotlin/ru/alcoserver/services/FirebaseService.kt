package ru.alcoserver.services

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.ApnsFcmOptions
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.ApsAlert
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import org.slf4j.LoggerFactory
import ru.alcoserver.config.AppConfig
import ru.alcoserver.models.NotificationDTO
import ru.alcoserver.models.NotificationResponse
import ru.alcoserver.models.NotificationType
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.Paths

/** Хост нашего медиа-хранилища: только ему разрешён `notification.image`. */
private val mediaHost: String? =
    runCatching { URI(AppConfig.mediaBaseUrl).host }.getOrNull()

/**
 * Пропускает только HTTPS-URL своего медиа-хоста.
 *
 * Картинка уходит в `notification.image`, её скачивает системный загрузчик, поэтому
 * произвольный URL от клиента — это чужой трекер в уведомлении и лишний исходящий запрос
 * с устройства. Хост берётся из конфигурации сервера, а не из пейлоада.
 */
internal fun validateImageUrl(imageUrl: String?): String? =
    imageUrl?.trim()?.takeIf { url ->
        mediaHost != null &&
            url.startsWith("https://") &&
            runCatching { URI(url).host }.getOrNull() == mediaHost
    }

/**
 * Источник картинки для лога (спека notifications, «Наблюдаемость картинок пушей»):
 * позволяет по жалобе «пуш без картинки» отличить отсутствующий URL от неудачного скачивания
 * без записи самого URL в лог.
 */
internal fun imageSource(imageUrl: String?): String =
    when {
        imageUrl.isNullOrBlank() -> "none"
        !imageUrl.contains("/media/") -> "invalid"
        imageUrl.contains("/media/public/") -> "brand"
        else -> "avatar-canvas"
    }

private fun resolveNotificationType(rawType: String?): NotificationType =
    NotificationType.entries.find { it.value == rawType?.takeIf(String::isNotBlank) }
        ?: NotificationType.DEFAULT

/** Звук APNs-баннера: тот же «default», что у Android-канала. */
private const val APNS_SOUND = "default"

/**
 * APNs-часть сообщения: avatar iOS-баннера.
 *
 * Расширение уведомлений (Notification Service Extension) запускается системой только при
 * `mutable-content: 1` и берёт картинку из `fcm_options.image`. Флаг выставляется ВСЕГДА,
 * в том числе без картинки: иначе расширение не запустится и заглушка не появится, а Firebase
 * сам выставляет его только при наличии `fcm_options.image`.
 */
internal fun buildApnsConfig(
    title: String,
    body: String,
    imageUrl: String?,
): ApnsConfig {
    val aps = Aps.builder()
        .setAlert(
            ApsAlert.builder()
                .setTitle(title)
                .setBody(body)
                .build()
        )
        .setSound(APNS_SOUND)
        .setMutableContent(true)
        .build()

    val builder = ApnsConfig.builder()
        .setAps(aps)
        // alert + 10: баннер показывается сразу, доставка не превращается в data-only.
        .putHeader("apns-push-type", "alert")
        .putHeader("apns-priority", "10")

    imageUrl?.let { url ->
        builder.setFcmOptions(ApnsFcmOptions.builder().setImage(url).build())
    }

    return builder.build()
}

/**
 * Собирает FCM-сообщение без обращения к Firebase Messaging.
 *
 * title, body и image выставляются в ОДНОМ `Notification.Builder`, чтобы
 * последующая установка title/body не затирала `notification.image`.
 */
internal fun buildNotificationMessage(
    notificationDTO: NotificationDTO,
    data: Map<String, String> = emptyMap(),
): Message {
    val notificationType = resolveNotificationType(notificationDTO.type)

    val notificationBuilder = Notification.builder()
        .setTitle(notificationDTO.title)
        .setBody(notificationDTO.body)
    // image валидируется и живёт только в notification-пейлоаде, не в data.
    val imageUrl = validateImageUrl(notificationDTO.imageUrl)
    imageUrl?.let(notificationBuilder::setImage)

    val messageBuilder = Message.builder()
        .setFid(notificationDTO.token)
        .setNotification(notificationBuilder.build())
        .putData("type", notificationType.value)
        .putData("message", notificationDTO.body)
        .putData("title", notificationDTO.title)

    // tag делает повторную доставку того же сообщения заменой уведомления. Клиент при
    // собственном показе (фореграунд) берёт этот же tag из data, поэтому строка должна
    // быть одинаковой в обоих местах.
    val tag = notificationDTO.id?.takeIf { it.isNotBlank() }
    tag?.let { messageBuilder.putData("tag", it) }

    data.forEach { (key, value) ->
        messageBuilder.putData(key, value)
    }

    // Контракт спеки (notifications, «Пуш-уведомления»): каждое сообщение
    // гибридное — system tray показывает его при убитом/фоновом приложении,
    // data-пейлоад остаётся служебным слоем. Data-only режим удалён.
    messageBuilder.setAndroidConfig(
        AndroidConfig.builder()
            .setNotification(
                AndroidNotification.builder()
                    .setChannelId(notificationType.channelId)
                    .apply { tag?.let(::setTag) }
                    .build()
            )
            .build()
    )

    // iOS: тот же кадр уезжает в APNs, откуда его забирает расширение уведомлений.
    messageBuilder.setApnsConfig(
        buildApnsConfig(
            title = notificationDTO.title,
            body = notificationDTO.body,
            imageUrl = imageUrl,
        )
    )

    return messageBuilder.build()
}

class FirebaseService {
    private val logger = LoggerFactory.getLogger(FirebaseService::class.java)

    init {
        initializeFirebase()
    }

    private fun initializeFirebase() {
        try {
            val configPath = AppConfig.firebaseConfigPath
            logger.info("Initializing Firebase with config: $configPath")

            val configFile = Paths.get(configPath).toFile()
            if (!configFile.exists()) {
                throw FileNotFoundException(
                    "Firebase config file not found at: ${configFile.absolutePath}\n" +
                            "Please make sure you have downloaded the service account JSON from Firebase Console " +
                            "and placed it in the config directory."
                )
            }

            val serviceAccount = FileInputStream(configFile)

            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                // Без projectId Admin SDK не может проверить ID-токен пользователя,
                // а проверка нужна для загрузки аватаров на сервер.
                .setProjectId(AppConfig.firebaseProjectId)
                .build()

            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options)
                logger.info("Firebase initialized successfully from: ${configFile.absolutePath}")
            } else {
                logger.info("Firebase already initialized")
            }
        } catch (e: FileNotFoundException) {
            logger.error("Firebase configuration file not found: ${e.message}")
            logger.error(
                "To fix this:\n" +
                        "1. Go to Firebase Console > Project Settings > Service Accounts\n" +
                        "2. Generate new private key\n" +
                        "3. Save the JSON file as 'firebase-service-account.json'\n" +
                        "4. Place it in the 'config' folder of your project"
            )
            throw RuntimeException("Firebase initialization failed: Config file not found", e)
        } catch (e: Exception) {
            logger.error("Failed to initialize Firebase", e)
            throw RuntimeException("Firebase initialization failed", e)
        }
    }

    /**
     * UID владельца ID-токена из заголовка `Authorization: Bearer ...`.
     * `null` — заголовка нет, токен просрочен/подделан или Admin SDK недоступен.
     */
    fun verifyIdTokenUid(authorizationHeader: String?): String? = runCatching {
        val token = authorizationHeader
            ?.removePrefix("Bearer")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        FirebaseAuth.getInstance().verifyIdToken(token).uid
    }.onFailure { logger.warn("ID token verification failed: ${it.message}") }
        .getOrNull()

    fun sendNotification(
        notificationDTO: NotificationDTO,
        data: Map<String, String> = emptyMap(),
    ): NotificationResponse {
        return try {
            val message = buildNotificationMessage(notificationDTO, data)

            logger.info(
                "Sending notification: type={}, image={}",
                notificationDTO.type,
                imageSource(notificationDTO.imageUrl),
            )

            val response = FirebaseMessaging.getInstance().send(message)

            logger.info("Notification sent successfully: $response")
            NotificationResponse(
                success = true,
                messageId = response
            )
        } catch (e: Exception) {
            logger.error(
                "Failed to send notification to token: ${notificationDTO.token.take(20)}...",
                e
            )
            NotificationResponse(
                success = false,
                error = e.message
            )
        }
    }
}
