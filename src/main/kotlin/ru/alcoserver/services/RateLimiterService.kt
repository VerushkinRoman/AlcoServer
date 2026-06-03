package ru.alcoserver.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class QueuedRequest(
    val id: String,
    val timestamp: Instant = Instant.now(),
    val callback: suspend () -> Unit
)

class RateLimiterService(
    private val maxRequestsPerMinute: Int = 20,
    private val cooldownPeriodMs: Long = 60.seconds.inWholeMilliseconds / maxRequestsPerMinute
) {
    private val logger = LoggerFactory.getLogger(RateLimiterService::class.java)

    private val requestTimestamps = ConcurrentLinkedQueue<Instant>()
    private val requestQueue = ConcurrentLinkedQueue<QueuedRequest>()
    private val activeRequests = AtomicInteger(0)
    private val mutex = Mutex()

    private var isProcessing = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            while (isActive) {
                processQueue()
                delay(1.seconds)
            }
        }
    }

    suspend fun <T> executeWithRateLimit(
        requestData: T,
        block: suspend (T) -> Unit
    ): RateLimitResult {
        val requestId = generateRequestId()

        return mutex.withLock {
            val currentCount = getCurrentRequestCount()

            if (currentCount < maxRequestsPerMinute) {
                requestTimestamps.add(Instant.now())
                activeRequests.incrementAndGet()

                scope.launch {
                    try {
                        logger.debug("Processing request $requestId immediately (count: ${currentCount + 1})")
                        block(requestData)
                    } catch (e: Exception) {
                        logger.error("Failed to process request $requestId", e)
                    } finally {
                        activeRequests.decrementAndGet()
                    }
                }

                RateLimitResult.Processed(requestId)
            } else {
                logger.info("Rate limit reached (${currentCount}/${maxRequestsPerMinute}), queuing request $requestId")

                val oldestTimestamp = requestTimestamps.peek()
                val waitTimeMs = if (oldestTimestamp != null) {
                    val elapsed = Instant.now().toEpochMilli() - oldestTimestamp.toEpochMilli()
                    maxOf(0, 60.seconds.inWholeMilliseconds - elapsed)
                } else {
                    60.seconds.inWholeMilliseconds
                }

                val queuedRequest = QueuedRequest(
                    id = requestId,
                    callback = {
                        try {
                            block(requestData)
                        } catch (e: Exception) {
                            logger.error("Failed to execute queued request $requestId", e)
                            throw e
                        }
                    }
                )

                requestQueue.add(queuedRequest)

                RateLimitResult.Queued(
                    requestId = requestId,
                    estimatedWaitMs = waitTimeMs,
                    queuePosition = requestQueue.size
                )
            }
        }
    }

    private fun getCurrentRequestCount(): Int {
        val now = Instant.now()
        val oneMinuteAgo = now.minusSeconds(60)

        requestTimestamps.removeIf { it.isBefore(oneMinuteAgo) }

        return requestTimestamps.size
    }

    private suspend fun processQueue() {
        if (isProcessing || requestQueue.isEmpty()) return

        isProcessing = true

        try {
            while (requestQueue.isNotEmpty()) {
                val currentCount = getCurrentRequestCount()

                if (currentCount < maxRequestsPerMinute) {
                    val request = requestQueue.poll()

                    if (request != null) {
                        logger.info("Processing queued request ${request.id} (queue size: ${requestQueue.size})")

                        requestTimestamps.add(Instant.now())
                        activeRequests.incrementAndGet()

                        try {
                            request.callback()
                            logger.debug("Successfully processed queued request ${request.id}")
                        } catch (e: Exception) {
                            logger.error("Failed to process queued request ${request.id}", e)
                        } finally {
                            activeRequests.decrementAndGet()
                        }

                        delay(cooldownPeriodMs.milliseconds)
                    }
                } else {
                    val oldestTimestamp = requestTimestamps.peek()
                    if (oldestTimestamp != null) {
                        val waitTime = 60.seconds.inWholeMilliseconds - (Instant.now()
                            .toEpochMilli() - oldestTimestamp.toEpochMilli())
                        if (waitTime > 0) {
                            logger.debug("Waiting ${waitTime}ms for rate limit reset")
                            delay(waitTime.milliseconds)
                        }
                    } else {
                        delay(1.seconds)
                    }
                }
            }
        } finally {
            isProcessing = false
        }
    }

    private fun generateRequestId(): String {
        return "req_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }

    fun getQueueStats(): QueueStats {
        return QueueStats(
            currentRequests = getCurrentRequestCount(),
            maxRequestsPerMinute = maxRequestsPerMinute,
            queueSize = requestQueue.size,
            activeRequests = activeRequests.get(),
            cooldownMs = cooldownPeriodMs
        )
    }

    fun shutdown() {
        scope.cancel()
        logger.info("Rate limiter service shut down")
    }
}

sealed class RateLimitResult {
    data class Processed(val requestId: String) : RateLimitResult()
    data class Queued(
        val requestId: String,
        val estimatedWaitMs: Long,
        val queuePosition: Int
    ) : RateLimitResult()

    data class Failed(val requestId: String, val error: String) : RateLimitResult()
}

data class QueueStats(
    val currentRequests: Int,
    val maxRequestsPerMinute: Int,
    val queueSize: Int,
    val activeRequests: Int,
    val cooldownMs: Long
)
