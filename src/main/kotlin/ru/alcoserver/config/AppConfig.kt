package ru.alcoserver.config

import com.typesafe.config.ConfigFactory
import java.io.File
import java.nio.file.Paths

object AppConfig {
    private val config = ConfigFactory.load()

    val serverPort: Int = config.getInt("ktor.deployment.port")
    val serverHost: String = config.getString("ktor.deployment.host")

    val firebaseConfigPath: String = resolveFirebaseConfigPath()

    val cloudProjectNumber: Long = config.getLong("google.cloud.project-number")

    private fun resolveFirebaseConfigPath(): String {
        val envPath = System.getenv("FIREBASE_CONFIG_PATH")
        if (envPath != null && File(envPath).exists()) {
            return envPath
        }

        val systemProperty = System.getProperty("firebase.config.path")
        if (systemProperty != null && File(systemProperty).exists()) {
            return systemProperty
        }

        val configPath = if (config.hasPath("firebase.config.path")) {
            config.getString("firebase.config.path")
        } else {
            "config/firebase-service-account.json"
        }

        val possiblePaths = listOf(
            Paths.get(configPath),
            Paths.get("config", "firebase-service-account.json"),
            Paths.get("..", "config", "firebase-service-account.json"),
            Paths.get(System.getProperty("user.dir"), "config", "firebase-service-account.json"),
            Paths.get(System.getProperty("user.dir"), configPath)
        )

        for (path in possiblePaths) {
            val file = path.toFile()
            if (file.exists()) {
                println("Firebase config found at: ${path.toAbsolutePath()}")
                return path.toAbsolutePath().toString()
            }
        }

        val defaultPath = Paths.get("config", "firebase-service-account.json").toAbsolutePath()
        println("=".repeat(60))
        println("WARNING: Firebase configuration file not found!")
        println("Expected locations:")
        possiblePaths.forEach { path ->
            println("  - ${path.toAbsolutePath()}")
        }
        println()
        println("To fix this:")
        println("1. Go to Firebase Console > Project Settings > Service Accounts")
        println("2. Generate new private key")
        println("3. Save as 'firebase-service-account.json'")
        println("4. Place it in one of the locations above")
        println("=".repeat(60))

        return defaultPath.toString()
    }
}
