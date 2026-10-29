package ru.alcoserver.services

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import ru.alcoserver.models.NotificationDTO
import ru.alcoserver.models.NotificationType

class FirebaseServiceTest {

    private val validImageUrl =
        "https://firebasestorage.googleapis.com/v0/b/alcocalendar/o/advice%2Fday.jpg?alt=media"

    private fun dto(
        title: String = "Совет дня",
        body: String = "Пейте больше воды",
        imageUrl: String? = null,
        type: String? = null,
    ): NotificationDTO = NotificationDTO(
        title = title,
        body = body,
        token = "fid-test-token",
        type = type,
        imageUrl = imageUrl,
    )

    @Test
    fun `image survives title and body assignment`() {
        val notification = buildNotificationMessage(
            dto(imageUrl = validImageUrl, type = NotificationType.ADVICE.value),
        ).notificationField()

        assertEquals("Совет дня", notification.stringField("title"))
        assertEquals("Пейте больше воды", notification.stringField("body"))
        assertEquals(
            validImageUrl,
            notification.stringField("image"),
            "notification.image SHALL NOT теряться при установке title/body",
        )
    }

    @Test
    fun `image stays out of data payload`() {
        val message = buildNotificationMessage(dto(imageUrl = validImageUrl))

        val data = message.dataField()
        assertEquals(NotificationType.DEFAULT.value, data["type"])
        assertEquals("Совет дня", data["title"])
        assertEquals("Пейте больше воды", data["message"])
        assertFalse(data.containsValue(validImageUrl), "image SHALL жить только в notification-пейлоаде")
        assertNull(data["imageUrl"], "imageUrl SHALL NOT попадать в data")
    }

    @Test
    fun `invalid image url is dropped but title and body remain`() {
        listOf(
            "http://firebasestorage.googleapis.com/v0/b/alcocalendar/o/advice.jpg",
            "https://example.com/advice.jpg",
            " ",
        ).forEach { invalidUrl ->
            val notification = buildNotificationMessage(dto(imageUrl = invalidUrl)).notificationField()

            assertEquals("Совет дня", notification.stringField("title"))
            assertEquals("Пейте больше воды", notification.stringField("body"))
            assertNull(notification.stringField("image"), "невалидный url не SHALL попадать в notification.image")
        }
    }

    @Test
    fun `message without image keeps title and body`() {
        val notification = buildNotificationMessage(dto(imageUrl = null)).notificationField()

        assertEquals("Совет дня", notification.stringField("title"))
        assertEquals("Пейте больше воды", notification.stringField("body"))
        assertNull(notification.stringField("image"))
    }

    @Test
    fun `android notification uses channel of resolved type`() {
        val androidNotification = buildNotificationMessage(
            dto(type = NotificationType.FRIEND_ACTIVITY.value, imageUrl = validImageUrl),
        ).androidNotificationField()

        assertEquals(
            NotificationType.FRIEND_ACTIVITY.channelId,
            androidNotification.stringField("channelId"),
        )
    }

    @Test
    fun `extra data is merged after service fields`() {
        val data = buildNotificationMessage(
            dto(type = NotificationType.ADVICE.value),
            data = mapOf("date" to "2026-10-29", "drinkType" to "water"),
        ).dataField()

        assertEquals(NotificationType.ADVICE.value, data["type"])
        assertEquals("2026-10-29", data["date"])
        assertEquals("water", data["drinkType"])
    }

    // firebase-admin не публикует геттеры для Message/Notification (они package-private),
    // поэтому фактический пейлоад читается рефлексией.
    private fun Any.readField(name: String): Any? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

    private fun Any.stringField(name: String): String? = readField(name) as String?

    private fun Message.notificationField(): Notification =
        requireNotNull(readField("notification")) as Notification

    private fun Message.dataField(): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        return requireNotNull(readField("data")) as Map<String, String>
    }

    private fun Message.androidNotificationField(): AndroidNotification {
        val androidConfig = requireNotNull(readField("androidConfig")) as AndroidConfig
        return requireNotNull(androidConfig.readField("notification")) as AndroidNotification
    }
}
