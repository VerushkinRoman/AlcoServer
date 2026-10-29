package ru.alcoserver.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.alcoserver.models.NotificationType

class AdviceFeedServiceTest {

    @Test
    fun `same messageId produces same id so double write merges into one entry`() {
        val messageId = "projects/alcocalendar/messages/0:1761500000000"

        val firstId = adviceNotificationId(messageId)
        val secondId = adviceNotificationId(messageId)

        assertEquals(firstId, secondId, "одинаковый messageId SHALL давать одинаковый id")

        // SetOptions.merge по ключу id: две записи с одним ключом — одна запись в ленте.
        val usersFirst = buildAdviceNotification(firstId, "Совет дня", "Текст")
        val usersSecond = buildAdviceNotification(secondId, "Совет дня", "Текст")
        val merged = mapOf(firstId to usersFirst) + mapOf(secondId to usersSecond)
        assertEquals(1, merged.size, "двойная запись при одном messageId SHALL давать одну запись")
    }

    @Test
    fun `different messageIds produce different ids`() {
        assertNotEquals(
            adviceNotificationId("projects/p/messages/111"),
            adviceNotificationId("projects/p/messages/222"),
            "разные messageId SHALL давать разные id",
        )
    }

    @Test
    fun `id sanitizes message id suffix`() {
        assertEquals("ADVICE_012345", adviceNotificationId("projects/p/messages/0:12345"))
    }

    @Test
    fun `missing messageId falls back to unique time based suffix`() {
        val id = adviceNotificationId(null)
        assertTrue(id.startsWith("ADVICE_"), "id SHALL начинаться с префикса типа")
        assertNotEquals(id, adviceNotificationId(null), "fallback id SHALL быть уникален")
    }

    @Test
    fun `notification body mirrors client DataNotification shape`() {
        val notification = buildAdviceNotification(
            id = "ADVICE_x1",
            title = "Совет дня",
            body = "Текст совета",
            timestamp = 42L,
        )

        assertEquals("ADVICE_x1", notification["id"])
        assertEquals(NotificationType.ADVICE.name, notification["type"], "тип SHALL храниться именем enum")
        assertEquals("Совет дня", notification["title"])
        assertEquals("Текст совета", notification["body"])
        assertEquals(42L, notification["timestamp"])
        assertEquals(false, notification["pending"])
    }

    @Test
    fun `resolveEmailByToken finds owner of token`() {
        val users = mapOf(
            "alice@example.com" to mapOf("token" to "fid-alice", "locale" to "Ru"),
            "bob@example.com" to mapOf("token" to "fid-bob", "locale" to "Eng"),
        )

        assertEquals("bob@example.com", resolveEmailByToken(users, "fid-bob"))
        assertNull(resolveEmailByToken(users, "fid-unknown"))
        assertNull(resolveEmailByToken(users, "   "), "пустой токен SHALL NOT резолвиться")
    }

    @Test
    fun `resolveEmailByToken tolerates malformed entries`() {
        val users = mapOf(
            "broken@example.com" to "not-a-map",
            "ok@example.com" to mapOf("token" to "fid-ok"),
        )

        assertEquals("ok@example.com", resolveEmailByToken(users, "fid-ok"))
        assertNull(resolveEmailByToken(emptyMap(), "fid-ok"))
    }

    @Test
    fun `uidFromEmailIndex extracts uid for dual write`() {
        assertEquals("uid-123", uidFromEmailIndex(mapOf("uid" to "uid-123")))
        assertEquals("uid-123", uidFromEmailIndex(mapOf("uid" to "  uid-123  ")))
    }

    @Test
    fun `uidFromEmailIndex returns null so uid path is skipped but legacy still applies`() {
        assertNull(uidFromEmailIndex(null), "отсутствующий снимок SHALL давать null")
        assertNull(uidFromEmailIndex(emptyMap()), "нет поля uid — null")
        assertNull(uidFromEmailIndex(mapOf("uid" to "")), "пустой uid SHALL давать null")
        assertNull(uidFromEmailIndex(mapOf("uid" to "   ")))
        assertNull(uidFromEmailIndex(mapOf("uid" to 42)), "не-строковый uid SHALL давать null")
    }

    // --- adviceFeedPlan: выбор источника идентичности (minimize-server-firestore-reads) ---

    @Test
    fun `uid and email in request plan direct write with zero firestore reads`() {
        val plan = adviceFeedPlan(email = "alice@example.com", uid = "uid-1")

        assertTrue(plan is AdviceFeedPlan.Direct)

        assertEquals("uid-1", plan.uid, "оба пути пишутся: uid-путь доступен")
        assertEquals("alice@example.com", plan.email, "оба пути пишутся: легаси-путь доступен")
        // Direct не требует чтений каталога/email_index — они появляются только в TokenResolve.
    }

    @Test
    fun `only email in request plans legacy path only`() {
        val plan = adviceFeedPlan(email = "alice@example.com", uid = null)

        assertTrue(plan is AdviceFeedPlan.Direct)

        assertNull(plan.uid, "без uid основной путь SHALL пропущен")
        assertEquals("alice@example.com", plan.email, "легаси-путь SHALL записан")
    }

    @Test
    fun `only uid in request plans uid path only`() {
        val plan = adviceFeedPlan(email = null, uid = "uid-2")

        assertTrue(plan is AdviceFeedPlan.Direct)

        assertEquals("uid-2", plan.uid, "uid-путь SHALL записан")
        assertNull(plan.email, "без email легаси-путь SHALL пропущен")
    }

    @Test
    fun `missing identity falls back to token resolve for old clients`() {
        assertEquals(
            AdviceFeedPlan.TokenResolve,
            adviceFeedPlan(email = null, uid = null),
            "запрос без идентичности SHALL резолвиться по токену (каталог + email_index)",
        )
    }

    @Test
    fun `blank identity is treated as missing`() {
        assertEquals(
            AdviceFeedPlan.TokenResolve,
            adviceFeedPlan(email = "   ", uid = ""),
            "blank-значения SHALL приравниваться к отсутствию",
        )

        val plan = adviceFeedPlan(email = " a@b.c ", uid = " uid-x ")
        assertTrue(plan is AdviceFeedPlan.Direct)

        assertEquals("a@b.c", plan.email, "идентичность SHALL обрезаться")
        assertEquals("uid-x", plan.uid, "идентичность SHALL обрезаться")
    }
}
