package ru.alcoserver.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.alcoserver.models.AppLocale
import ru.alcoserver.models.NotificationType
import ru.alcoserver.models.PaymentSnapshot

class DonateServiceTest {

    @Test
    fun `notification body mirrors client DataNotification shape`() {
        val notification = buildPaymentNotification(
            id = "donation_pay-1",
            title = "Платёж подтверждён",
            body = "Спасибо за поддержку!",
            timestamp = 42L,
        )

        assertEquals("donation_pay-1", notification["id"])
        assertEquals(
            NotificationType.PAYMENT_SUCCESS.name,
            notification["type"],
            "тип SHALL храниться именем enum",
        )
        assertEquals("Платёж подтверждён", notification["title"])
        assertEquals("Спасибо за поддержку!", notification["body"])
        assertEquals(42L, notification["timestamp"])
        assertEquals(false, notification["pending"])
    }

    @Test
    fun `uid payer resolves email from profile and falls back to legacy entry`() {
        val payer = buildPaymentPayer(
            userId = "uid-1",
            legacyCatalog = mapOf("a@b.ru" to mapOf("locale" to "En", "token" to "fid-legacy")),
            emailIndexUid = null,
            profileExists = true,
            profileEmail = "a@b.ru",
            settings = null,
        )

        requireNotNull(payer)
        assertEquals("uid-1", payer.uid)
        assertEquals("a@b.ru", payer.email)
        assertEquals(AppLocale.Eng, payer.locale, "локаль SHALL фолбэчиться на легаси-запись")
        assertEquals("fid-legacy", payer.token)
    }

    @Test
    fun `uid payer takes locale and token from settings over legacy entry`() {
        val payer = buildPaymentPayer(
            userId = "uid-1",
            legacyCatalog = mapOf("a@b.ru" to mapOf("locale" to "Ru", "token" to "fid-legacy")),
            emailIndexUid = null,
            profileExists = true,
            profileEmail = "a@b.ru",
            settings = mapOf("locale" to "En", "token" to "fid-new"),
        )

        requireNotNull(payer)
        assertEquals(AppLocale.Eng, payer.locale, "настройки SHALL иметь приоритет")
        assertEquals("fid-new", payer.token)
    }

    @Test
    fun `legacy email payment resolves uid through email index`() {
        val payer = buildPaymentPayer(
            userId = "a@b.ru",
            legacyCatalog = mapOf("a@b.ru" to mapOf("locale" to "Ru", "token" to "fid")),
            emailIndexUid = "uid-9",
            profileExists = true,
            profileEmail = "a@b.ru",
            settings = null,
        )

        requireNotNull(payer)
        assertEquals("uid-9", payer.uid, "uid SHALL резолвиться из email_index")
        assertEquals("a@b.ru", payer.email)
    }

    @Test
    fun `legacy payment without email index keeps only legacy path`() {
        val payer = buildPaymentPayer(
            userId = "a@b.ru",
            legacyCatalog = mapOf("a@b.ru" to mapOf("locale" to "Ru", "token" to "fid")),
            emailIndexUid = null,
            profileExists = false,
            profileEmail = null,
            settings = null,
        )

        requireNotNull(payer)
        assertNull(payer.uid, "без email_index uid SHALL отсутствовать")
        assertEquals("a@b.ru", payer.email, "легаси-путь SHALL работать без uid")
        assertEquals("fid", payer.token)
    }

    @Test
    fun `unknown payer yields null so notification is skipped`() {
        assertNull(
            buildPaymentPayer(
                userId = "ghost@b.ru",
                legacyCatalog = emptyMap(),
                emailIndexUid = null,
                profileExists = false,
                profileEmail = null,
                settings = null,
            ),
            "email без записи в каталоге и без email_index SHALL давать null",
        )
        assertNull(
            buildPaymentPayer(
                userId = "uid-ghost",
                legacyCatalog = emptyMap(),
                emailIndexUid = null,
                profileExists = false,
                profileEmail = null,
                settings = null,
            ),
            "uid без профиля SHALL давать null",
        )
    }

    @Test
    fun `payment deep link carries uid URL-encoded`() {
        assertEquals(
            "calendarkmp://payment/success?amount=149&paymentId=pay-1&userId=uid-1",
            paymentDeepLink(149.0, "pay-1", "uid-1"),
        )
        assertEquals(
            "calendarkmp://payment/success?amount=149&paymentId=pay-1&userId=a%2Bb%40c.ru",
            paymentDeepLink(149.0, "pay-1", "a+b@c.ru"),
            "userId SHALL быть URL-закодирован",
        )
    }

    // --- Снапшот платежа (minimize-server-firestore-reads, задачи 4.1–4.3) ---

    @Test
    fun `payment snapshot carries full payer identity`() {
        val fields = paymentSnapshotFields(
            PaymentSnapshot(
                uid = "uid-1",
                email = "a@b.ru",
                locale = "En",
                token = "fid-1",
                amount = "100",
                createdAt = 42L,
            ),
        )

        assertEquals("uid-1", fields["uid"])
        assertEquals("a@b.ru", fields["email"])
        assertEquals("En", fields["locale"])
        assertEquals("fid-1", fields["token"])
        assertEquals("100", fields["amount"])
        assertEquals(42L, fields["createdAt"])
    }

    @Test
    fun `legacy client snapshot keeps only uid and amount`() {
        val fields = paymentSnapshotFields(
            PaymentSnapshot(uid = "uid-2", amount = "50", createdAt = 1L),
        )

        assertEquals("uid-2", fields["uid"])
        assertEquals("50", fields["amount"])
        assertFalse("email" in fields, "нет email в запросе — поля нет в снапшоте")
        assertFalse("locale" in fields)
        assertFalse("token" in fields)
    }

    @Test
    fun `payment snapshot build is idempotent for repeat creation`() {
        val snapshot = PaymentSnapshot(uid = "uid-3", email = "a@b.ru", amount = "10", createdAt = 7L)
        assertEquals(
            paymentSnapshotFields(snapshot),
            paymentSnapshotFields(snapshot),
            "повторное создание с тем же paymentId SHALL перезаписывать док теми же полями",
        )
    }

    @Test
    fun `missing snapshot document reads as null`() {
        assertNull(paymentSnapshotFrom(null), "отсутствующий док SHALL давать null")
        val snapshot = paymentSnapshotFrom(mapOf("uid" to "uid-9", "createdAt" to 5))
        assertEquals("uid-9", snapshot?.uid)
        assertEquals(5L, snapshot?.createdAt)
        assertNull(snapshot?.email, "нет поля — null")
    }

    @Test
    fun `snapshot identity means no catalog reads`() {
        val source = paymentIdentitySource(
            snapshot = PaymentSnapshot(uid = "uid-1", email = "a@b.ru"),
            donationRecordExists = false,
        )

        val snapshotSource = assertIs<PaymentIdentitySource.Snapshot>(source)
        assertEquals("uid-1", snapshotSource.snapshot.uid)
        assertEquals("a@b.ru", snapshotSource.snapshot.email)
    }

    @Test
    fun `missing snapshot falls back to legacy resolve for old payments`() {
        assertEquals(
            PaymentIdentitySource.LegacyResolve,
            paymentIdentitySource(snapshot = null, donationRecordExists = false),
            "снапшота нет и платёж не обработан (до деплоя) — прежняя схема резолва",
        )
    }

    @Test
    fun `deleted snapshot after verify skips duplicate processing`() {
        assertEquals(
            PaymentIdentitySource.AlreadyProcessed,
            paymentIdentitySource(snapshot = null, donationRecordExists = true),
            "удалённый снапшот + существующая запись — дубль уведомления не создаётся",
        )
    }

    // --- Учёт пожертвований в settings (задачи 5.1–5.2) ---

    @Test
    fun `donation record enriches email and locale for future refunds`() {
        val record = donationRecordFields(
            userId = "uid-1",
            amount = 100.0,
            type = "succeeded",
            email = "a@b.ru",
            locale = AppLocale.Eng,
        )

        assertEquals("uid-1", record["userId"])
        assertEquals(100.0, record["amount"])
        assertEquals("succeeded", record["type"])
        assertEquals("a@b.ru", record["email"])
        assertEquals("En", record["locale"])
    }

    @Test
    fun `donation record omits unknown email and locale`() {
        val record = donationRecordFields("uid-2", 5.0, "refunded", email = null, locale = null)

        assertFalse("email" in record, "неизвестный email SHALL отсутствовать в записи")
        assertFalse("locale" in record, "неизвестная локаль SHALL отсутствовать в записи")
        assertEquals("refunded", record["type"])
    }

    @Test
    fun `donations total is read from settings document`() {
        assertEquals(15.5, donationsFromSettings(mapOf("donations" to 15.5)))
        assertEquals(10.0, donationsFromSettings(mapOf("donations" to 10)))
        assertEquals(0.0, donationsFromSettings(mapOf()), "нет поля — 0.0")
        assertEquals(0.0, donationsFromSettings(null), "нет дока — 0.0")
        assertEquals(0.0, donationsFromSettings(mapOf("donations" to "не число")), "не число — 0.0")
    }

    @Test
    fun `refunds never drive the total below zero`() {
        assertEquals(120.0, nextDonationsTotal(100.0, 20.0))
        assertEquals(0.0, nextDonationsTotal(100.0, -100.0))
        assertEquals(0.0, nextDonationsTotal(50.0, -100.0), "рефанд больше суммы SHALL давать 0")
    }

    @Test
    fun `catalog mirror runs only when email is known`() {
        assertTrue(shouldMirrorDonations("a@b.ru"))
        assertFalse(shouldMirrorDonations(null), "без email зеркало SHALL пропускаться с логом")
        assertFalse(shouldMirrorDonations(""))
        assertFalse(shouldMirrorDonations("   "))
    }

    @Test
    fun `accounting uid resolves legacy email through email index`() {
        assertEquals("uid-7", accountingUid("a@b.ru", emailIndexUid = "uid-7"))
        assertNull(accountingUid("a@b.ru", emailIndexUid = null), "без email_index uid неизвестен")
        assertEquals("uid-1", accountingUid("uid-1", emailIndexUid = null), "uid-платеж читается напрямую")
    }
}
