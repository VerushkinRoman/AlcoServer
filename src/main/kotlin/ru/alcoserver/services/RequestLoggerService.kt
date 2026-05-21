package ru.alcoserver.services

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable
data class RequestLogEntry(
    val timestamp: String,
    val requestId: String,
    val type: String,
    val status: String,
    val details: String? = null,
    val processingTimeMs: Long? = null
)

class RequestLoggerService(
    private val logDirectory: String = "logs/requests",
    private val maxQueueSize: Int = 1000
) {
    private val logger = LoggerFactory.getLogger(RequestLoggerService::class.java)
    private val json = Json { prettyPrint = false }
    private val logQueue = ConcurrentLinkedQueue<RequestLogEntry>()

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    init {
        val dir = File(logDirectory)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        scheduler.scheduleAtFixedRate(
            { flushLogs() },
            5,
            5,
            TimeUnit.SECONDS
        )

        logger.info("Request logger initialized. Logs directory: ${dir.absolutePath}")
    }

    fun logRequest(
        requestId: String,
        type: String,
        status: String,
        details: String? = null,
        processingTimeMs: Long? = null
    ) {
        val entry = RequestLogEntry(
            timestamp = formatter.format(Instant.now()),
            requestId = requestId,
            type = type,
            status = status,
            details = details,
            processingTimeMs = processingTimeMs
        )

        if (logQueue.size < maxQueueSize) {
            logQueue.add(entry)
        } else {
            logger.warn("Log queue is full (${maxQueueSize}), dropping entry")
        }
    }

    private fun flushLogs() {
        if (logQueue.isEmpty()) return

        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())
        val today = dateFormatter.format(Instant.now())

        val logFile = File(logDirectory, "requests_$today.log")

        try {
            PrintWriter(FileWriter(logFile, true)).use { writer ->
                var count = 0
                while (!logQueue.isEmpty() && count < 100) {
                    val entry = logQueue.poll()
                    if (entry != null) {
                        writer.println(json.encodeToString(entry))
                        count++
                    }
                }
                writer.flush()
            }

            if (logQueue.isNotEmpty()) {
                logger.debug("Flushed logs, ${logQueue.size} entries remaining in queue")
            }
        } catch (e: Exception) {
            logger.error("Failed to flush logs to file", e)
        }
    }

    fun getTodayLogs(): List<RequestLogEntry> {
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())
        val today = dateFormatter.format(Instant.now())

        return getLogsForDate(today)
    }

    fun getLogsForDate(date: String): List<RequestLogEntry> {
        val logFile = File(logDirectory, "requests_$date.log")
        if (!logFile.exists()) return emptyList()

        return try {
            logFile.readLines().mapNotNull { line ->
                try {
                    json.decodeFromString<RequestLogEntry>(line)
                } catch (e: Exception) {
                    logger.error("Failed to parse log line: $line", e)
                    null
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to read logs for date: $date", e)
            emptyList()
        }
    }

    fun forceFlush() {
        flushLogs()
    }

    fun shutdown() {
        scheduler.shutdown()
        forceFlush()
        logger.info("Request logger shut down")
    }
}
