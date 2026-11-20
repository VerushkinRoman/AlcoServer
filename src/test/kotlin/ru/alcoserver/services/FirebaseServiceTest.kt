package ru.alcoserver.services

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.ApnsFcmOptions
import com.google.firebase.messaging.ApsAlert
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import ru.alcoserver.models.NotificationDTO
import ru.alcoserver.models.NotificationType
import ru.alcoserver.routes.notificationExtraData

class FirebaseServiceTest {

    private val validImageUrl =
        "https://alcoserver.ru:3344/media/public/brand.png"

    private fun dto(
        title: String = "Совет дня",
        body: String = "Пейте больше воды",
        imageUrl: String? = null,
        type: String? = null,
        friendEmail: String? = null,
        friendUid: String? = null,
        id: String? = null,
    ): NotificationDTO = NotificationDTO(
        id = id,
        title = title,
        body = body,
        token = "fid-test-token",
        type = type,
        imageUrl = imageUrl,
        friendEmail = friendEmail,
        friendUid = friendUid,
    )

    @Test
    fun `friend uid is carried in data payload`() {
        val request = dto(
            title = "friend@x.com",
            body = "принял вашу заявку",
            type = "FRIEND_ACTIVITY",
            friendEmail = "friend@x.com",
            friendUid = "uid-friend",
        )

        val data = notificationExtraData(request)
        assertEquals("friend@x.com", data["friend_email"])
        assertEquals("uid-friend", data["friend_uid"])

        val message = buildNotificationMessage(request, data)
        assertEquals("friend@x.com", message.notificationField().stringField("title"))
        assertEquals("uid-friend", message.dataField()["friend_uid"])
    }

    @Test
    fun `blank friend uid is dropped from data payload`() {
        val data = notificationExtraData(dto(friendEmail = "friend@x.com", friendUid = "  "))

        assertFalse(data.containsKey("friend_uid"), "пустой friend_uid SHALL NOT попадать в data")
        assertEquals("friend@x.com", data["friend_email"])
    }

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
    fun `advice and payment pushes stay hybrid and keep the image`() {
        listOf(NotificationType.ADVICE, NotificationType.PAYMENT_SUCCESS).forEach { type ->
            val message = buildNotificationMessage(dto(imageUrl = validImageUrl, type = type.value))

            // notification-блок: показывается системой без запуска приложения и несёт картинку
            // (именно его читает iOS, где вложение даст NSE из отдельного change).
            val notification = message.notificationField()
            assertEquals(validImageUrl, notification.stringField("image"), "image SHALL оставаться у ${type.value}")
            assertEquals("Совет дня", notification.stringField("title"))

            // data-часть и канал Android в том же сообщении: доставка не переходит в data-only.
            assertEquals(type.value, message.dataField()["type"])
            assertEquals(type.channelId, message.androidNotificationField().stringField("channelId"))
        }
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
            "http://alcoserver.ru:3344/media/public/brand.png",
            "https://example.com/advice.jpg",
            "https://firebasestorage.googleapis.com/v0/b/alcocalendar/o/advice.jpg",
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

    @Test
    fun `message id becomes android tag and data tag`() {
        val request = dto(type = NotificationType.FRIEND_ACTIVITY.value, id = "msg-42")

        val message = buildNotificationMessage(request)

        assertEquals("msg-42", message.dataField()["tag"], "клиент берёт tag из data")
        assertEquals(
            "msg-42",
            message.androidNotificationField().stringField("tag"),
            "системный показ SHALL заменять уведомление по тому же tag",
        )
    }

    @Test
    fun `message without id has no tag`() {
        val request = dto(type = NotificationType.FRIEND_ACTIVITY.value)

        val message = buildNotificationMessage(request)

        assertNull(message.dataField()["tag"], "без id tag не задаётся: старые клиенты")
        assertNull(message.androidNotificationField().stringField("tag"))
    }

    @Test
    fun `image source classifies urls for logging`() {
        assertEquals("none", imageSource(null))
        assertEquals("none", imageSource(" "))
        assertEquals("avatar-canvas", imageSource("https://alcoserver.ru:3344/media/avatars/u.png?v=7"))
        assertEquals("brand", imageSource("https://alcoserver.ru:3344/media/public/brand.png"))
        assertEquals("invalid", imageSource("https://example.com/brand.png"))
    }

    @Test
    fun `ios payload is an alert push with mutable content and the same image`() {
        val apns = buildNotificationMessage(dto(imageUrl = validImageUrl, type = "friend_activity"))
            .apnsConfigField()

        assertEquals("alert", apns.headersField()["apns-push-type"])
        assertEquals("10", apns.headersField()["apns-priority"])

        val aps = apns.apsField()
        val alert = aps["alert"] as ApsAlert
        assertEquals("Совет дня", alert.stringField("title"))
        assertEquals("Пейте больше воды", alert.stringField("body"))
        assertEquals("default", aps["sound"])
        // 1, а не true: APNs ждёт ровно единицу.
        assertEquals(1, aps["mutable-content"], "mutable-content SHALL запускать расширение")
        assertEquals(validImageUrl, apns.fcmOptionsField()?.stringField("image"))
    }

    @Test
    fun `mutable content stays on even without an image`() {
        val apns = buildNotificationMessage(dto(imageUrl = null)).apnsConfigField()

        assertEquals(1, apns.apsField()["mutable-content"], "без флага расширение не запустится и заглушки не будет")
        assertNull(apns.fcmOptionsField()?.stringField("image"))
    }

    @Test
    fun `data keys used by ios bridge survive the apns branch`() {
        val message = buildNotificationMessage(
            dto(type = "friend_activity", friendEmail = "friend@x.com", friendUid = "uid-friend", id = "n-1"),
            notificationExtraData(dto(type = "friend_activity", friendEmail = "friend@x.com", friendUid = "uid-friend", id = "n-1")),
        )

        val data = message.dataField()
        assertEquals("friend_activity", data["type"])
        assertEquals("Совет дня", data["title"])
        assertEquals("Пейте больше воды", data["message"])
        assertEquals("uid-friend", data["friend_uid"])
        assertEquals("n-1", data["tag"])
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

    private fun Message.apnsConfigField(): ApnsConfig =
        requireNotNull(readField("apnsConfig")) as ApnsConfig

    private fun ApnsConfig.headersField(): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        return requireNotNull(readField("headers")) as Map<String, String>
    }

    private fun ApnsConfig.apsField(): Map<String, Any> {
        val payload = requireNotNull(readField("payload")) as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        return requireNotNull(payload["aps"]) as Map<String, Any>
    }

    private fun ApnsConfig.fcmOptionsField(): ApnsFcmOptions? = readField("fcmOptions") as ApnsFcmOptions?

    private fun Message.androidNotificationField(): AndroidNotification {
        val androidConfig = requireNotNull(readField("androidConfig")) as AndroidConfig
        return requireNotNull(androidConfig.readField("notification")) as AndroidNotification
    }
}
