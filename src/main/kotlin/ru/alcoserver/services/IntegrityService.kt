package ru.alcoserver.services

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.playintegrity.v1.PlayIntegrity
import com.google.api.services.playintegrity.v1.model.DecodeIntegrityTokenRequest
import com.google.api.services.playintegrity.v1.model.DecodeIntegrityTokenResponse
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.alcoserver.config.AppConfig
import ru.alcoserver.models.IntegrityErrorMessage
import ru.alcoserver.models.IntegrityInvalidReason
import ru.alcoserver.models.IntegrityResult
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException

class IntegrityService {
    private val logger = LoggerFactory.getLogger(IntegrityService::class.java)
    private val playIntegrity: PlayIntegrity

    init {
        playIntegrity = initializePlayIntegrity()
    }

    private fun initializePlayIntegrity(): PlayIntegrity {
        return try {
            val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
            val jsonFactory = GsonFactory.getDefaultInstance()

            val configFile = File(AppConfig.firebaseConfigPath)
            if (!configFile.exists()) {
                throw FileNotFoundException(
                    "Firebase config file not found at: ${configFile.absolutePath}\n" +
                            "Please make sure you have downloaded the service account JSON from Firebase Console " +
                            "and placed it in the config directory."
                )
            }

            val credentials = GoogleCredentials.fromStream(FileInputStream(configFile))
                .createScoped(listOf("https://www.googleapis.com/auth/playintegrity"))

            logger.info("Google Play Integrity client initialized successfully")

            PlayIntegrity.Builder(httpTransport, jsonFactory, HttpCredentialsAdapter(credentials))
                .setApplicationName("PushServer")
                .build()
        } catch (e: Exception) {
            logger.error("Failed to initialize Google Play Integrity client", e)
            throw RuntimeException("Play Integrity initialization failed", e)
        }
    }

    suspend fun verifyToken(integrityToken: String): IntegrityResult {
        return withContext(Dispatchers.IO) {
            try {
                logger.info("Verifying integrity token with Google Play Integrity API")

                val request = DecodeIntegrityTokenRequest()
                    .setIntegrityToken(integrityToken)

                val response: DecodeIntegrityTokenResponse = playIntegrity
                    .v1()
                    .decodeIntegrityToken(
                        AppConfig.cloudProjectNumber.toString(),
                        request
                    )
                    .execute()

                processIntegrityResponse(response)

            } catch (e: Exception) {
                logger.error("Error during integrity verification with Google", e)
                handleGoogleApiException(e)
            }
        }
    }

    private fun processIntegrityResponse(
        response: DecodeIntegrityTokenResponse
    ): IntegrityResult {
        val tokenPayloadExternal = response.tokenPayloadExternal

        if (tokenPayloadExternal == null) {
            logger.error("Empty response from Google Play Integrity")
            return IntegrityResult.Error(
                IntegrityErrorMessage.NetworkError("Empty response from Google")
            )
        }

        val appIntegrity = tokenPayloadExternal.appIntegrity
        val deviceIntegrity = tokenPayloadExternal.deviceIntegrity

        val appRecognitionVerdict = appIntegrity?.appRecognitionVerdict
        if (appRecognitionVerdict != "PLAY_RECOGNIZED") {
            logger.warn("App not recognized by Google Play: $appRecognitionVerdict")
            return IntegrityResult.Invalid(
                IntegrityInvalidReason.AppNotValid(
                    "App not recognized: $appRecognitionVerdict"
                )
            )
        }

        val deviceRecognitionVerdict = deviceIntegrity?.deviceRecognitionVerdict

        val meetsDeviceIntegrity = deviceRecognitionVerdict?.any {
            it == "MEETS_DEVICE_INTEGRITY" || it == "MEETS_BASIC_INTEGRITY"
        } ?: false

        if (meetsDeviceIntegrity) {
            logger.info("Integrity verification passed successfully")
            return IntegrityResult.Valid
        } else {
            logger.warn("Device integrity check failed: $deviceRecognitionVerdict")
            return IntegrityResult.Invalid(
                IntegrityInvalidReason.DeviceNotValid(
                    deviceRecognitionVerdict?.joinToString(", ") ?: "Unknown"
                )
            )
        }
    }

    private fun handleGoogleApiException(e: Exception): IntegrityResult {
        return when {
            e.message?.contains("403") == true ||
                    e.message?.contains("Forbidden") == true -> {
                IntegrityResult.Invalid(IntegrityInvalidReason.AccessForbiddenByServer)
            }

            e.message?.contains("timeout") == true ||
                    e.message?.contains("Timeout") == true -> {
                IntegrityResult.Error(IntegrityErrorMessage.ConnectionTimeout)
            }

            e.message?.contains("connect") == true ||
                    e.message?.contains("Connect") == true -> {
                IntegrityResult.Error(IntegrityErrorMessage.NoConnectionToServer)
            }

            e.message?.contains("UnknownHost") == true -> {
                IntegrityResult.Error(IntegrityErrorMessage.ServerUnreachable)
            }

            else -> {
                IntegrityResult.Error(
                    IntegrityErrorMessage.NetworkError(e.message)
                )
            }
        }
    }
}
