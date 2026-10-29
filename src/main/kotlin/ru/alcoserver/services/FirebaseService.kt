package ru.alcoserver.services

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
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
import java.nio.file.Paths

private const val FIREBASE_STORAGE_HOST = "firebasestorage.googleapis.com"

private fun validateImageUrl(imageUrl: String?): String? =
    imageUrl?.takeIf {
        it.isNotBlank() && it.startsWith("https://") && it.contains(FIREBASE_STORAGE_HOST)
    }

private fun resolveNotificationType(rawType: String?): NotificationType =
    NotificationType.entries.find { it.value == rawType?.takeIf(String::isNotBlank) }
        ?: NotificationType.DEFAULT

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
    validateImageUrl(notificationDTO.imageUrl)?.let(notificationBuilder::setImage)

    val messageBuilder = Message.builder()
        .setFid(notificationDTO.token)
        .setNotification(notificationBuilder.build())
        .putData("type", notificationType.value)
        .putData("message", notificationDTO.body)
        .putData("title", notificationDTO.title)

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
                    .build()
            )
            .build()
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

    fun sendNotification(
        notificationDTO: NotificationDTO,
        data: Map<String, String> = emptyMap(),
    ): NotificationResponse {
        return try {
            val message = buildNotificationMessage(notificationDTO, data)

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
