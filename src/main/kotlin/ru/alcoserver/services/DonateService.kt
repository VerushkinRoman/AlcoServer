package ru.alcoserver.services

import com.google.cloud.firestore.FieldPath
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions
import com.google.firebase.cloud.FirestoreClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import ru.alcoserver.config.AppConfig
import ru.alcoserver.models.AppLocale
import ru.alcoserver.models.DonateConfigResponse
import ru.alcoserver.models.DonateRequest
import ru.alcoserver.models.DonateResponse
import ru.alcoserver.models.DonateVerifyRequest
import ru.alcoserver.models.DonateVerifyResponse
import ru.alcoserver.models.NotificationDTO
import ru.alcoserver.models.NotificationType
import ru.alcoserver.models.PaymentSnapshot
import ru.alcoserver.models.YooKassaPaymentResponse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Идентификатор плательщика из metadata платежа: uid (новые клиенты) или email
 * (легаси-клиенты, до-uid форма). Тот же признак использует клиент (`isUidLike`).
 */
internal fun paymentIdLooksLikeEmail(userId: String): Boolean = '@' in userId

/**
 * Locale/token плательщика: настройки `users/{uid}/settings/data` (uid-модель)
 * имеют приоритет над легаси-записью `Collection_of_all_users/Users`.
 */
internal fun paymentLocale(
    settings: Map<String, Any?>?,
    legacyEntry: Map<String, Any?>?,
): AppLocale {
    val raw = (settings?.get("locale") as? String)?.takeIf(String::isNotBlank)
        ?: (legacyEntry?.get("locale") as? String)?.takeIf(String::isNotBlank)
        ?: ""
    return AppLocale.fromValue(raw)
}

internal fun paymentToken(
    settings: Map<String, Any?>?,
    legacyEntry: Map<String, Any?>?,
): String? =
    (settings?.get("token") as? String)?.trim()?.takeIf(String::isNotBlank)
        ?: (legacyEntry?.get("token") as? String)?.trim()?.takeIf(String::isNotBlank)

/**
 * Данные плательщика, резолвнутые по его идентификатору. `uid == null` — новый
 * путь фида (`users/{uid}/data/Notifications`) недоступен; `email == null` —
 * легаси-зеркало `{email}/Notifications` недоступно.
 */
internal data class PaymentPayer(
    val uid: String?,
    val email: String?,
    val locale: AppLocale,
    val token: String?,
)

/**
 * Собирает данные плательщика из уже прочитанных источников Firestore:
 * легаси-каталога `Collection_of_all_users/Users`, индекса `email_index/{email}`,
 * профиля `users/{uid}` и настроек `users/{uid}/settings/data`.
 *
 * Легаси-платежи (userId — email): email — ключ каталога, uid — через
 * `email_index`. Uid-платежи: email — поле профиля, locale/token — настройки
 * с фолбэком на легаси-запись. Возвращает null, когда плательщик не найден ни
 * в одном хранилище (спека «Анонимные платежи исключаются»).
 */
@Suppress("UNCHECKED_CAST")
internal fun buildPaymentPayer(
    userId: String,
    legacyCatalog: Map<String, Any>,
    emailIndexUid: String?,
    profileExists: Boolean,
    profileEmail: String?,
    settings: Map<String, Any?>?,
): PaymentPayer? {
    return if (paymentIdLooksLikeEmail(userId)) {
        val legacyEntry = legacyCatalog[userId] as? Map<String, Any?>
        if (legacyEntry == null && emailIndexUid == null) return null
        PaymentPayer(
            uid = emailIndexUid,
            email = userId,
            locale = paymentLocale(settings, legacyEntry),
            token = paymentToken(settings, legacyEntry),
        )
    } else {
        if (!profileExists) return null
        val legacyEntry = profileEmail?.let { legacyCatalog[it] as? Map<String, Any?> }
        PaymentPayer(
            uid = userId,
            email = profileEmail?.trim()?.takeIf(String::isNotBlank),
            locale = paymentLocale(settings, legacyEntry),
            token = paymentToken(settings, legacyEntry),
        )
    }
}

/**
 * Форма записи в Firestore-фиде плательщика — одинакова для нового пути
 * `users/{uid}/data/Notifications` и легаси-зеркала `{email}/Notifications`;
 * тип хранится именем enum (см. спеку «Тип в ленте хранится как имя enum»).
 */
internal fun buildPaymentNotification(
    id: String,
    title: String,
    body: String,
    timestamp: Long = System.currentTimeMillis(),
): Map<String, Any> = mapOf(
    "id" to id,
    "type" to NotificationType.PAYMENT_SUCCESS.name,
    "title" to title,
    "body" to body,
    "timestamp" to timestamp,
    "pending" to false,
)

/**
 * Deep link успешного платежа; userId SHALL быть uid плательщика (для легаси
 * без `email_index` попадает исходный идентификатор).
 */
internal fun paymentDeepLink(amount: Double, paymentId: String, userId: String): String =
    "calendarkmp://payment/success?amount=${amount.toInt()}&paymentId=$paymentId&userId=" +
        URLEncoder.encode(userId, StandardCharsets.UTF_8.toString())

// ---------------------------------------------------------------------------
// Снапшот платежа payments/{paymentId} (minimize-server-firestore-reads)
// ---------------------------------------------------------------------------

/**
 * Поля документа снапшота для записи в Firestore: пустые/null поля
 * опускаются — снапшот старого клиента состоит только из `uid` и `amount`.
 * Запись идемпотентна: один и тот же снапшот всегда даёт одну и ту же карту
 * (повторное создание платежа с тем же id перезаписывает док целиком).
 */
internal fun paymentSnapshotFields(snapshot: PaymentSnapshot): Map<String, Any> = buildMap {
    snapshot.uid?.trim()?.takeIf(String::isNotBlank)?.let { put("uid", it) }
    snapshot.email?.trim()?.takeIf(String::isNotBlank)?.let { put("email", it) }
    snapshot.locale?.trim()?.takeIf(String::isNotBlank)?.let { put("locale", it) }
    snapshot.token?.trim()?.takeIf(String::isNotBlank)?.let { put("token", it) }
    snapshot.amount?.trim()?.takeIf(String::isNotBlank)?.let { put("amount", it) }
    put("createdAt", snapshot.createdAt)
}

/** Читает снапшот из снимка документа; отсутствующий документ даёт null. */
@Suppress("UNCHECKED_CAST")
internal fun paymentSnapshotFrom(data: Map<String, Any?>?): PaymentSnapshot? {
    data ?: return null
    return PaymentSnapshot(
        uid = data["uid"] as? String,
        email = data["email"] as? String,
        locale = data["locale"] as? String,
        token = data["token"] as? String,
        amount = data["amount"] as? String,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L,
    )
}

/**
 * Источник идентичности плательщика при обработке платежа:
 *
 * - [Snapshot] — снапшот на месте: идентичность из него, каталог НЕ читается;
 * - [LegacyResolve] — снапшота нет и платеж ещё не обрабатывался (создан до
 *   деплоя change) — прежняя схема резолва (каталог/email_index/профиль/настройки);
 * - [AlreadyProcessed] — снапшот удалён после успешной обработки (вебхук после
 *   verify или наоборот) — дубль уведомления и повторный учёт НЕ создаются.
 */
internal sealed interface PaymentIdentitySource {
    data class Snapshot(val snapshot: PaymentSnapshot) : PaymentIdentitySource
    data object LegacyResolve : PaymentIdentitySource
    data object AlreadyProcessed : PaymentIdentitySource
}

/**
 * Выбор источника идентичности: снапшот имеет приоритет; без него проверяется
 * запись `donations/{paymentId}` (создаётся при первой успешной обработке) —
 * она отличает «платёж до деплоя» от «уже обработан и снапшот удалён».
 */
internal fun paymentIdentitySource(
    snapshot: PaymentSnapshot?,
    donationRecordExists: Boolean,
): PaymentIdentitySource = when {
    snapshot != null -> PaymentIdentitySource.Snapshot(snapshot)
    donationRecordExists -> PaymentIdentitySource.AlreadyProcessed
    else -> PaymentIdentitySource.LegacyResolve
}

/**
 * Форма записи `donations/{paymentId}`: uid плательщика в `userId` плюс
 * email и локаль — для будущих рефандов, когда снапшот уже удалён.
 * Пустые email/locale опускаются из документа.
 */
internal fun donationRecordFields(
    userId: String,
    amount: Double,
    type: String,
    email: String?,
    locale: AppLocale?,
): Map<String, Any> = buildMap {
    put("userId", userId)
    put("amount", amount)
    put("type", type)
    email?.trim()?.takeIf(String::isNotBlank)?.let { put("email", it) }
    locale?.let { put("locale", it.value) }
}

/** Текущая сумма пожертвований из документа `users/{uid}/settings/data`. */
internal fun donationsFromSettings(settings: Map<String, Any?>?): Double =
    (settings?.get("donations") as? Number)?.toDouble() ?: 0.0

/** Новая сумма с ограничением снизу нулём (рефанд больше накопленного). */
internal fun nextDonationsTotal(
    current: Double,
    amount: Double,
): Double = maxOf(0.0, current + amount)

/** Зеркало в каталог нуждается в email; без него оно пропускается с логом. */
internal fun shouldMirrorDonations(email: String?): Boolean = !email.isNullOrBlank()

/**
 * Uid для учёта пожертвований: uid-платежи идентифицируют себя напрямую,
 * легаси-платежи (userId — email) резолвятся через `email_index`.
 */
internal fun accountingUid(
    userId: String,
    emailIndexUid: String?,
): String? =
    if (paymentIdLooksLikeEmail(userId)) {
        emailIndexUid?.trim()?.takeIf(String::isNotBlank)
    } else {
        userId.trim().takeIf(String::isNotBlank)
    }

class DonateService(
    private val httpClient: HttpClient,
    private val firebaseService: FirebaseService,
) {
    companion object {
        private const val YOOKASSA_API_URL = "https://api.yookassa.ru/v3/payments"
        private const val COLLECTION_ALL_USERS = "Collection_of_all_users"
        private const val DOCUMENT_USERS = "Users"
        private const val DOCUMENT_NOTIFICATIONS = "Notifications"
        private const val COLLECTION_EMAIL_INDEX = "email_index"
        private const val COLLECTION_USERS = "users"
        private const val SUBCOLLECTION_DATA = "data"
        private const val SUBCOLLECTION_SETTINGS = "settings"
        private const val DOCUMENT_SETTINGS = "data"
        private const val COLLECTION_PAYMENTS = "payments"
        private const val COLLECTION_DONATIONS = "donations"
        private const val FIELD_DONATIONS = "donations"

        private val paymentTitles = mapOf(
            AppLocale.Rus to "Платёж подтверждён",
            AppLocale.Eng to "Payment confirmed",
        )
        private val paymentBodies = mapOf(
            AppLocale.Rus to "Спасибо за поддержку!",
            AppLocale.Eng to "Thank you for your support!",
        )
    }

    private val logger = LoggerFactory.getLogger(DonateService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createPayment(request: DonateRequest): DonateResponse {
        val body = buildJsonObject {
            putJsonObject("amount") {
                put("value", request.amount)
                put("currency", "RUB")
            }
            put("capture", true)
            put("description", request.description)

            if (!request.userId.isNullOrBlank() && request.userId != "anonymous") {
                putJsonObject("metadata") {
                    put("userId", request.userId)
                }
            }

            if (!request.paymentToken.isNullOrBlank()) {
                put("payment_token", request.paymentToken)
            } else if (request.redirect) {
                putJsonObject("confirmation") {
                    put("type", "redirect")
                    put("return_url", request.returnUrl ?: "https://alcoserver.ru")
                }
            }
        }

        val response: YooKassaPaymentResponse = httpClient.post(YOOKASSA_API_URL) {
            basicAuth(AppConfig.yookassaShopId, AppConfig.yookassaSecretKey)
            header("Idempotence-Key", UUID.randomUUID().toString())
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.body()

        val donateResponse = DonateResponse(
            status = response.status,
            paymentId = response.id,
            confirmationUrl = response.confirmation?.confirmationUrl,
        )

        // Снапшот платежа — источник идентичности для вебхука и verify:
        // одно мелкое чтение вместо «каталог + email_index + профиль + настройки».
        donateResponse.paymentId?.takeIf(String::isNotBlank)?.let { paymentId ->
            writePaymentSnapshot(paymentId, request)
        }

        return donateResponse
    }

    /**
     * Пишет снапшот `payments/{paymentId}`: идентичность из запроса клиента
     * (uid/email/locale/token) и сумму. `set` без merge — идемпотентная
     * перезапись при повторном создании платежа с тем же id.
     */
    private fun writePaymentSnapshot(paymentId: String, request: DonateRequest) {
        try {
            val snapshot = PaymentSnapshot(
                uid = request.userId?.trim()?.takeIf(String::isNotBlank),
                email = request.email?.trim()?.takeIf(String::isNotBlank),
                locale = request.locale?.trim()?.takeIf(String::isNotBlank),
                token = request.token?.trim()?.takeIf(String::isNotBlank),
                amount = request.amount,
                createdAt = System.currentTimeMillis(),
            )
            FirestoreClient.getFirestore()
                .collection(COLLECTION_PAYMENTS)
                .document(paymentId)
                .set(paymentSnapshotFields(snapshot))
            logger.info("Payment snapshot written: paymentId=$paymentId, uid=${snapshot.uid}")
        } catch (e: Exception) {
            logger.error("Failed to write payment snapshot $paymentId: ${e.message}", e)
        }
    }

    private fun readPaymentSnapshot(db: Firestore, paymentId: String): PaymentSnapshot? = try {
        paymentSnapshotFrom(
            db.collection(COLLECTION_PAYMENTS).document(paymentId).get().get().data,
        )
    } catch (e: Exception) {
        logger.error("Failed to read payment snapshot $paymentId: ${e.message}", e)
        null
    }

    /** Удаляет снапшот после успешного проведения/подтверждения платежа. */
    private fun deletePaymentSnapshot(db: Firestore, paymentId: String) {
        try {
            db.collection(COLLECTION_PAYMENTS).document(paymentId).delete()
            logger.info("Payment snapshot deleted: paymentId=$paymentId")
        } catch (e: Exception) {
            logger.error("Failed to delete payment snapshot $paymentId: ${e.message}", e)
        }
    }

    /** Есть ли запись `donations/{paymentId}` со статусом succeeded. */
    private fun donationRecordSucceeded(db: Firestore, paymentId: String): Boolean = try {
        val data = db.collection(COLLECTION_DONATIONS).document(paymentId).get().get().data
        (data?.get("type") as? String) == "succeeded"
    } catch (e: Exception) {
        logger.error("Failed to read donation record $paymentId: ${e.message}", e)
        false
    }

    fun handleWebhook(body: String, remoteIp: String?): Boolean {
        logger.info("Webhook received from IP: $remoteIp")

        if (!isValidWebhookIp(remoteIp)) {
            logger.warn("Webhook rejected: IP $remoteIp not in YooKassa whitelist")
            return false
        }

        try {
            val event = json.decodeFromString<JsonObject>(body)
            val eventType = event["event"]?.toString()?.trim('"') ?: return false
            val paymentObject = event["object"] as? JsonObject ?: return false

            logger.info("Webhook event=$eventType")

            when (eventType) {
                "payment.succeeded" -> handlePaymentSucceeded(paymentObject)
                "refund.succeeded" -> handleRefundSucceeded(paymentObject)
                else -> logger.info("Ignoring event: $eventType")
            }

            return true
        } catch (e: Exception) {
            logger.error("Failed to process webhook: ${e.message}", e)
            return false
        }
    }

    private fun handlePaymentSucceeded(paymentObject: JsonObject) {
        val paymentId = paymentObject["id"]?.toString()?.trim('"') ?: return
        val status = paymentObject["status"]?.toString()?.trim('"') ?: return
        val amountObj = paymentObject["amount"] as? JsonObject
        val amountValue = amountObj?.get("value")?.toString()?.trim('"')
        val metadata = paymentObject["metadata"] as? JsonObject
        val userId = metadata?.get("userId")?.toString()?.trim('"')
        val amount = amountValue?.toDoubleOrNull() ?: 0.0

        logger.info("payment.succeeded: paymentId=$paymentId, status=$status, amount=$amountValue, userId=$userId")

        if (userId.isNullOrBlank() || userId == "anonymous") return

        val db = FirestoreClient.getFirestore()
        val snapshot = readPaymentSnapshot(db, paymentId)
        // Запись donations читается ТОЛЬКО когда снапшота нет (short-circuit).
        val donationRecordExists = snapshot == null && donationRecordSucceeded(db, paymentId)

        when (val source = paymentIdentitySource(snapshot, donationRecordExists)) {
            is PaymentIdentitySource.AlreadyProcessed -> {
                // Вебхук пришёл после verifyPayment (снапшот уже удалён):
                // дубль уведомления и повторный учёт не создаются.
                logger.info("payment.succeeded: paymentId=$paymentId already processed, duplicate skipped")
            }
            is PaymentIdentitySource.Snapshot -> {
                // Идентичность из снапшота: без чтений Firestore-каталога.
                completePaymentSuccess(
                    db = db,
                    paymentId = paymentId,
                    fallbackUserId = userId,
                    amount = amount,
                    payer = payerFromSnapshot(db, source.snapshot, userId),
                )
            }
            PaymentIdentitySource.LegacyResolve -> {
                // Платёж до деплоя change: прежняя схема резолва (фолбэк).
                completePaymentSuccess(
                    db = db,
                    paymentId = paymentId,
                    fallbackUserId = userId,
                    amount = amount,
                    payer = resolvePaymentPayer(userId, fetchAllUsersData() ?: emptyMap()),
                )
            }
        }
    }

    /**
     * Общая обработка успешного платежа: учёт в `users/{uid}/settings`,
     * запись `donations/{paymentId}` (с email и локалью), уведомление в оба
     * пути фида и push — затем удаление снапшота.
     */
    private fun completePaymentSuccess(
        db: Firestore,
        paymentId: String,
        fallbackUserId: String,
        amount: Double,
        payer: PaymentPayer?,
    ) {
        if (payer == null) {
            logger.warn("Payer $fallbackUserId not found, payment $paymentId processing skipped")
            return
        }

        val uid = payer.uid
        if (uid == null) {
            logger.warn("Payment $paymentId: uid unknown for $fallbackUserId, settings accounting skipped")
        } else {
            addToUserDonations(db, uid, amount, payer.email)
        }

        saveDonationRecord(db, paymentId, fallbackUserId, amount, "succeeded", payer.email, payer.locale)
        notifyPaymentSuccess(db, payer, paymentId, amount)
        deletePaymentSnapshot(db, paymentId)
    }

    private fun handleRefundSucceeded(refundObject: JsonObject) {
        val refundId = refundObject["id"]?.toString()?.trim('"') ?: return
        val paymentId = refundObject["payment_id"]?.toString()?.trim('"') ?: return
        val amountObj = refundObject["amount"] as? JsonObject
        val refundAmount = amountObj?.get("value")?.toString()?.trim('"')?.toDoubleOrNull() ?: 0.0

        logger.info("refund.succeeded: refundId=$refundId, paymentId=$paymentId, amount=$refundAmount")

        val db = FirestoreClient.getFirestore()
        val record = readDonationRecord(db, paymentId)
        val userId = record?.get("userId") as? String
        if (userId.isNullOrBlank() || userId == "anonymous") {
            logger.warn("Cannot process refund: userId not found for paymentId=$paymentId")
            return
        }

        // Идентичность рефанда — из записи donations/{paymentId} (email/locale
        // записаны при обработке платежа, когда снапшот мог быть ещё удалён).
        val email = record["email"] as? String
        val locale = (record["locale"] as? String)?.let(AppLocale::fromValue)
        val emailIndexUid = if (paymentIdLooksLikeEmail(userId)) readEmailIndexUid(db, userId) else null
        val uid = accountingUid(userId, emailIndexUid)

        saveDonationRecord(db, refundId, userId, refundAmount, "refunded", email, locale)
        if (uid == null) {
            logger.warn("Refund $refundId: uid unknown for $userId, settings accounting skipped")
        } else {
            addToUserDonations(db, uid, -refundAmount, email)
        }
    }

    private fun isValidWebhookIp(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        val validRanges = listOf(
            "185.71.76.", "185.71.77.",
            "77.75.153.", "77.75.156.",
            "77.75.154.",
        )
        return validRanges.any { ip.startsWith(it) }
    }

    /**
     * Учёт пожертвований в `users/{uid}/settings` (поле `donations`):
     * чтение мелкого дока → merge-запись туда же. Зеркало в каталог —
     * точечное `FieldPath(email, "donations")` БЕЗ чтения и полной перезаписи
     * документа `Collection_of_all_users/Users`; без email зеркало
     * пропускается с логом, uid-учёт продолжается.
     */
    private fun addToUserDonations(db: Firestore, uid: String, amount: Double, email: String?) {
        try {
            val settingsRef = db.collection(COLLECTION_USERS)
                .document(uid)
                .collection(SUBCOLLECTION_SETTINGS)
                .document(DOCUMENT_SETTINGS)

            val current = donationsFromSettings(settingsRef.get().get().data)
            val newTotal = nextDonationsTotal(current, amount)
            settingsRef.set(mapOf(FIELD_DONATIONS to newTotal), SetOptions.merge())
            logger.info("User=$uid donations: $current + $amount = $newTotal")

            if (shouldMirrorDonations(email)) {
                try {
                    db.collection(COLLECTION_ALL_USERS)
                        .document(DOCUMENT_USERS)
                        .update(FieldPath.of(email!!, FIELD_DONATIONS), newTotal)
                    logger.info("Donations mirrored to catalog entry of $email: $newTotal")
                } catch (e: Exception) {
                    logger.error("Failed to mirror donations for $email: ${e.message}", e)
                }
            } else {
                logger.warn("Donations catalog mirror skipped for uid=$uid: email unknown")
            }
        } catch (e: Exception) {
            logger.error("Failed to update donations for user $uid: ${e.message}", e)
        }
    }

    /** Текущая сумма пожертвований: чтение `users/{uid}/settings` (мелкий док). */
    private fun getUserDonations(db: Firestore, uid: String): Double {
        return try {
            val snapshot = db.collection(COLLECTION_USERS)
                .document(uid)
                .collection(SUBCOLLECTION_SETTINGS)
                .document(DOCUMENT_SETTINGS)
                .get().get()
            donationsFromSettings(snapshot.data)
        } catch (e: Exception) {
            logger.error("Failed to get user donations: ${e.message}", e)
            0.0
        }
    }

    private fun readDonationRecord(db: Firestore, paymentId: String): Map<String, Any?>? = try {
        db.collection(COLLECTION_DONATIONS).document(paymentId).get().get().data
    } catch (e: Exception) {
        logger.error("Failed to read donation record $paymentId: ${e.message}", e)
        null
    }

    /**
     * Резолвит uid из `email_index` для легаси-идентификатора (userId — email).
     * Для uid-идентификатора чтение не выполняется.
     */
    private fun readEmailIndexUid(db: Firestore, email: String): String? = try {
        uidFromEmailIndex(db.collection(COLLECTION_EMAIL_INDEX).document(email).get().get().data)
    } catch (e: Exception) {
        logger.error("Failed to resolve uid for $email from email_index: ${e.message}", e)
        null
    }

    private fun readPayerProfile(db: Firestore, uid: String): Map<String, Any?>? = try {
        db.collection(COLLECTION_USERS).document(uid).get().get().data
    } catch (e: Exception) {
        logger.error("Failed to read profile for $uid: ${e.message}", e)
        null
    }

    private fun readPayerSettings(db: Firestore, uid: String): Map<String, Any?>? = try {
        db.collection(COLLECTION_USERS)
            .document(uid)
            .collection(SUBCOLLECTION_SETTINGS)
            .document(DOCUMENT_SETTINGS)
            .get().get().data
    } catch (e: Exception) {
        logger.error("Failed to read settings for $uid: ${e.message}", e)
        null
    }

    /**
     * Чтение каталога `Collection_of_all_users/Users` целиком — ТОЛЬКО фолбэк
     * для платежей без снапшота (созданы до деплоя change); рабочие пути
     * снапшота каталог не читают (change minimize-server-firestore-reads).
     */
    private fun fetchAllUsersData(): Map<String, Any>? {
        return try {
            val db = FirestoreClient.getFirestore()
            val snapshot = db.collection(COLLECTION_ALL_USERS).document(DOCUMENT_USERS).get().get()
            snapshot.data ?: emptyMap()
        } catch (e: Exception) {
            logger.error("Failed to read users data: ${e.message}", e)
            null
        }
    }

    private fun resolvePaymentPayer(userId: String, legacyCatalog: Map<String, Any>): PaymentPayer? {
        val db = FirestoreClient.getFirestore()
        return if (paymentIdLooksLikeEmail(userId)) {
            val uid = readEmailIndexUid(db, userId)
            val settings = uid?.let { readPayerSettings(db, it) }
            buildPaymentPayer(
                userId = userId,
                legacyCatalog = legacyCatalog,
                emailIndexUid = uid,
                profileExists = true,
                profileEmail = userId,
                settings = settings,
            )
        } else {
            val profile = readPayerProfile(db, userId)
            val profileEmail = (profile?.get("email") as? String)?.trim()?.takeIf(String::isNotBlank)
            val settings = readPayerSettings(db, userId)
            buildPaymentPayer(
                userId = userId,
                legacyCatalog = legacyCatalog,
                emailIndexUid = null,
                profileExists = profile != null,
                profileEmail = profileEmail,
                settings = settings,
            )
        }
    }

    /**
     * Идентичность плательщика из снапшота `payments/{paymentId}` — БЕЗ чтения
     * Firestore-каталога. Мелкие чтения идут только там, где поля не хватает:
     * uid легаси-клиента (userId — email) — через `email_index`, email
     * uid-платежа — из профиля `users/{uid}`, locale/token — из
     * `users/{uid}/settings/data` (фолбэк для старых клиентов, не шлющих поля).
     */
    private fun payerFromSnapshot(
        db: Firestore,
        snapshot: PaymentSnapshot,
        fallbackUserId: String,
    ): PaymentPayer? {
        val snapshotUid = snapshot.uid?.trim()?.takeIf(String::isNotBlank)
        val snapshotEmail = snapshot.email?.trim()?.takeIf(String::isNotBlank)
        val uidOrEmail = snapshotUid
            ?: fallbackUserId.trim().takeIf { it.isNotBlank() && it != "anonymous" }

        if (uidOrEmail == null && snapshotEmail == null) return null

        val uid: String?
        val email: String?
        if (uidOrEmail != null && paymentIdLooksLikeEmail(uidOrEmail)) {
            // Легаси-идентификатор: uid резолвится через email_index.
            email = snapshotEmail ?: uidOrEmail
            uid = readEmailIndexUid(db, uidOrEmail)
        } else {
            uid = uidOrEmail
            email = snapshotEmail ?: uidOrEmail?.let { readProfileEmail(db, it) }
        }
        if (uid == null && email == null) return null

        val snapshotLocale = snapshot.locale?.trim()?.takeIf(String::isNotBlank)
        val snapshotToken = snapshot.token?.trim()?.takeIf(String::isNotBlank)
        val settings =
            if (uid != null && (snapshotLocale == null || snapshotToken == null)) {
                readPayerSettings(db, uid)
            } else {
                null
            }

        return PaymentPayer(
            uid = uid,
            email = email,
            locale = snapshotLocale?.let(AppLocale::fromValue) ?: paymentLocale(settings, null),
            token = snapshotToken ?: paymentToken(settings, null),
        )
    }

    private fun readProfileEmail(db: Firestore, uid: String): String? =
        (readPayerProfile(db, uid)?.get("email") as? String)?.trim()?.takeIf(String::isNotBlank)

    private fun notifyPaymentSuccess(
        db: Firestore,
        payer: PaymentPayer,
        paymentId: String,
        amount: Double,
    ) {
        val title = paymentTitles[payer.locale] ?: paymentTitles.getValue(AppLocale.Rus)
        val body = paymentBodies[payer.locale] ?: paymentBodies.getValue(AppLocale.Rus)

        writePaymentNotification(db, payer, paymentId, title, body)

        val token = payer.token
        if (token.isNullOrBlank()) {
            logger.info("Payer ${payer.uid ?: payer.email} has no token, push skipped")
            return
        }

        val deepLinkUserId = payer.uid ?: payer.email ?: return
        try {
            firebaseService.sendNotification(
                NotificationDTO(
                    title = title,
                    body = body,
                    token = token,
                    type = NotificationType.PAYMENT_SUCCESS.value,
                ),
                data = mapOf("deep_link" to paymentDeepLink(amount, paymentId, deepLinkUserId)),
            )
            logger.info("Payment success push sent to user $deepLinkUserId")
        } catch (e: Exception) {
            logger.error("Failed to send payment success push: ${e.message}", e)
        }
    }

    /**
     * Запись фида платежа в ОБА пути — новый `users/{uid}/data/Notifications` и
     * легаси-зеркало `{email}/Notifications` (спека firestore-storage: dual-write).
     * Пути независимы: сбой одного логируется и НЕ отменяет другой и НЕ роняет
     * пуш — push уже доставляется, фид дозаписывается при ретрае.
     */
    private fun writePaymentNotification(
        db: Firestore,
        payer: PaymentPayer,
        paymentId: String,
        title: String,
        body: String,
    ) {
        val notificationId = "donation_$paymentId"
        val notification = buildPaymentNotification(notificationId, title, body)
        val feedEntry = mapOf(notificationId to notification)

        // Основной путь uid-модели: users/{uid}/data/Notifications.
        val uid = payer.uid
        if (uid == null) {
            logger.warn("Payment notification uid path skipped: no uid for payer ${payer.email}")
        } else {
            try {
                db.collection(COLLECTION_USERS)
                    .document(uid)
                    .collection(SUBCOLLECTION_DATA)
                    .document(DOCUMENT_NOTIFICATIONS)
                    .set(feedEntry, SetOptions.merge())
                logger.info("Payment success notification written for $uid, id=$notificationId")
            } catch (e: Exception) {
                logger.error("Failed to write payment notification to users/$uid data feed: ${e.message}", e)
            }
        }

        // Легаси-зеркало {email}/Notifications для старых клиентов и фолбэка чтения.
        val email = payer.email
        if (email == null) {
            logger.warn("Payment notification legacy path skipped: no email for payer $uid")
        } else {
            try {
                db.collection(email).document(DOCUMENT_NOTIFICATIONS)
                    .set(feedEntry, SetOptions.merge())
                logger.info("Payment success notification written (legacy) for $email, id=$notificationId")
            } catch (e: Exception) {
                logger.error("Failed to write legacy payment notification for $email: ${e.message}", e)
            }
        }
    }

    /**
     * Запись `donations/{paymentId}`: userId (uid или исходный идентификатор)
     * + email и локаль плательщика — для будущих рефандов, когда снапшот уже
     * удалён.
     */
    private fun saveDonationRecord(
        db: Firestore,
        id: String,
        userId: String,
        amount: Double,
        type: String,
        email: String?,
        locale: AppLocale?,
    ) {
        try {
            db.collection(COLLECTION_DONATIONS).document(id).set(
                donationRecordFields(userId, amount, type, email, locale),
            )
            logger.info("Saved $type record: id=$id, userId=$userId, amount=$amount")
        } catch (e: Exception) {
            logger.error("Failed to save donation record: ${e.message}", e)
        }
    }

    fun getClientConfig(): DonateConfigResponse {
        return DonateConfigResponse(
            clientPublicKey = AppConfig.yookassaClientPublicKey,
            shopId = AppConfig.yookassaShopId,
        )
    }

    suspend fun verifyPayment(request: DonateVerifyRequest): DonateVerifyResponse {
        logger.info("Verify payment: paymentId=${request.paymentId}, amount=${request.amount}, userId=${request.userId}")
        return try {
            val response: YooKassaPaymentResponse =
                httpClient.get("https://api.yookassa.ru/v3/payments/${request.paymentId}") {
                    basicAuth(AppConfig.yookassaShopId, AppConfig.yookassaSecretKey)
                }.body()

            val amount = request.amount.toDoubleOrNull() ?: 0.0

            logger.info("YooKassa response: status=${response.status}, paid=${response.paid}")

            var totalDonations = 0.0
            if (response.status == "succeeded") {
                val userId = request.userId
                if (!userId.isNullOrBlank() && userId != "anonymous") {
                    val db = FirestoreClient.getFirestore()
                    val snapshot = readPaymentSnapshot(db, request.paymentId)
                    val donationRecordExists =
                        snapshot == null && donationRecordSucceeded(db, request.paymentId)

                    when (val source = paymentIdentitySource(snapshot, donationRecordExists)) {
                        is PaymentIdentitySource.AlreadyProcessed -> {
                            // Вебхук уже обработал платёж и удалил снапшот:
                            // повторный учёт не нужен, отдаём текущую сумму.
                            logger.info(
                                "Verify payment ${request.paymentId}: already processed, recount skipped",
                            )
                            totalDonations = getUserDonations(db, userId)
                        }
                        else -> {
                            val payer = when (source) {
                                is PaymentIdentitySource.Snapshot ->
                                    payerFromSnapshot(db, source.snapshot, userId)
                                else ->
                                    resolvePaymentPayer(userId, fetchAllUsersData() ?: emptyMap())
                            }

                            val emailIndexUid =
                                if (paymentIdLooksLikeEmail(userId)) readEmailIndexUid(db, userId) else null
                            val uid = accountingUid(userId, emailIndexUid)
                                ?: payer?.uid

                            if (uid == null) {
                                logger.warn("Verify payment ${request.paymentId}: uid unknown for $userId")
                            } else {
                                addToUserDonations(db, uid, amount, payer?.email)
                            }
                            saveDonationRecord(
                                db = db,
                                id = request.paymentId,
                                userId = userId,
                                amount = amount,
                                type = "succeeded",
                                email = payer?.email,
                                locale = payer?.locale,
                            )
                            if (uid != null) {
                                totalDonations = getUserDonations(db, uid)
                                logger.info("User=$uid total donations=$totalDonations")
                            }
                            deletePaymentSnapshot(db, request.paymentId)
                        }
                    }
                }
            }

            DonateVerifyResponse(
                success = true,
                paid = response.status == "succeeded",
                status = response.status,
                totalDonations = totalDonations,
            )
        } catch (e: Exception) {
            logger.error("Failed to verify payment: ${e.message}", e)
            DonateVerifyResponse(success = false, paid = false, status = "error")
        }
    }
}
