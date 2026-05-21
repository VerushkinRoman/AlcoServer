package ru.alcoserver.services

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import org.slf4j.LoggerFactory
import ru.alcoserver.config.AppConfig
import ru.alcoserver.models.NotificationDTO
import ru.alcoserver.models.NotificationResponse
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.file.Paths

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

    fun sendNotification(notificationDTO: NotificationDTO): NotificationResponse {
        return try {
            logger.info("Sending notification to token: ${notificationDTO.token.take(20)}...")

            val message = Message.builder()
                .setToken(notificationDTO.token)
                .setNotification(
                    Notification.builder()
                        .setTitle(notificationDTO.title)
                        .setBody(notificationDTO.body)
                        .build()
                )
                .build()

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
