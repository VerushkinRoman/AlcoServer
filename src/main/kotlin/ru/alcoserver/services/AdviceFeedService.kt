package ru.alcoserver.services

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions
import com.google.firebase.cloud.FirestoreClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.alcoserver.models.NotificationType
import java.util.UUID

private const val COLLECTION_ALL_USERS = "Collection_of_all_users"
private const val DOCUMENT_USERS = "Users"
private const val DOCUMENT_NOTIFICATIONS = "Notifications"
private const val COLLECTION_EMAIL_INDEX = "email_index"
private const val COLLECTION_USERS = "users"
private const val SUBCOLLECTION_DATA = "data"
private const val FIELD_UID = "uid"
private const val FEED_NOTIFICATION_TYPE = "ADVICE"

/**
 * Детерминированный id записи фида из FCM messageId: один и тот же messageId
 * всегда даёт один и тот же ключ, поэтому повторная запись (ретрай, дедупликация)
 * сливается в одну запись через SetOptions.merge вместо создания дубликата.
 * Некорректные символы messageId вычищаются, пустой/отсутствующий messageId
 * деградирует к уникальному суффиксу (UUID), чтобы дубликаты не склеивались
 * в одну запись ошибочно.
 */
internal fun adviceNotificationId(messageId: String?): String {
    val suffix = messageId
        ?.substringAfterLast('/')
        ?.replace(Regex("[^A-Za-z0-9_-]"), "")
        ?.takeIf { it.isNotBlank() }
    return FEED_NOTIFICATION_TYPE + "_" + (suffix ?: UUID.randomUUID().toString())
}

/**
 * Резолвит email получателя по push-токену (FID) из документа
 * `Collection_of_all_users/Users` (карта email -> пользователь с полем token).
 */
internal fun resolveEmailByToken(users: Map<String, Any>, token: String): String? {
    if (token.isBlank()) return null
    return users.entries.firstNotNullOfOrNull { (email, value) ->
        val userData = value as? Map<*, *> ?: return@firstNotNullOfOrNull null
        val storedToken = userData["token"] as? String
        if (storedToken == token) email else null
    }
}

/**
 * Форма записи в Firestore-фиде получателя — одинакова для нового пути
 * `users/{uid}/data/Notifications` и легаси-зеркала `{email}/Notifications`;
 * поля зеркалят клиентский DataNotification (тип хранится именем enum, см. спеку
 * «Тип в ленте хранится как имя enum»).
 */
internal fun buildAdviceNotification(
    id: String,
    title: String,
    body: String,
    timestamp: Long = System.currentTimeMillis(),
): Map<String, Any> = mapOf(
    "id" to id,
    "type" to NotificationType.ADVICE.name,
    "title" to title,
    "body" to body,
    "timestamp" to timestamp,
    "pending" to false,
)

/**
 * Извлекает uid из снимка документа `email_index/{email}` (поле `uid`).
 * Пустое, отсутствующее или не-строковое значение даёт null: запись в новый
 * путь пропускается, но легаси-запись это не отменяет.
 */
internal fun uidFromEmailIndex(data: Map<String, Any?>?): String? =
    (data?.get(FIELD_UID) as? String)?.trim()?.takeIf { it.isNotBlank() }

/**
 * План записи фиды совета (change minimize-server-firestore-reads).
 *
 * [Direct] — идентичность пришла от клиента в запросе: каталог
 * `Collection_of_all_users/Users` и `email_index` НЕ читаются, пишутся только
 * пути, для которых хватает данных (uid → основной путь, email → легаси-зеркало).
 * [TokenResolve] — старый клиент без `email`/`uid`: фолбэк-резолв получателя по
 * токену через каталог и `email_index`, как до change.
 */
internal sealed interface AdviceFeedPlan {
    data class Direct(val uid: String?, val email: String?) : AdviceFeedPlan
    data object TokenResolve : AdviceFeedPlan
}

/**
 * Выбор источника идентичности получателя совета: любая непустая часть
 * идентичности из запроса даёт прямой план (ноль чтений), полное отсутствие —
 * фолбэк-резолв по токену. Blank/whitespace значения приравниваются к
 * отсутствию.
 */
internal fun adviceFeedPlan(email: String?, uid: String?): AdviceFeedPlan {
    val cleanEmail = email?.trim()?.takeIf { it.isNotBlank() }
    val cleanUid = uid?.trim()?.takeIf { it.isNotBlank() }
    return if (cleanEmail != null || cleanUid != null) {
        AdviceFeedPlan.Direct(uid = cleanUid, email = cleanEmail)
    } else {
        AdviceFeedPlan.TokenResolve
    }
}

private fun resolveUidByEmail(db: Firestore, email: String, logger: Logger): String? = try {
    uidFromEmailIndex(db.collection(COLLECTION_EMAIL_INDEX).document(email).get().get().data)
} catch (e: Exception) {
    logger.error("Failed to resolve uid for $email from email_index: ${e.message}", e)
    null
}

/**
 * Серверная запись совета в Firestore-фиду получателя при отправке
 * (спека «AI-советы»): идентичность `email`/`uid` приходит из запроса — в этом
 * случае НИКАКИХ чтений Firestore нет (change minimize-server-firestore-reads).
 * Запись идёт в ОБА пути фида — новый `users/{uid}/data/Notifications` и
 * легаси-зеркало `{email}/Notifications` (спека firestore-storage: dual-write);
 * пути независимы: сбой одного логируется и НЕ отменяет другой и НЕ роняет
 * пуш — push уже доставляется, фид дозаписывается при ретрае.
 *
 * Запросы БЕЗ `email`/`uid` (старые клиенты) деградируют к фолбэк-резолву по
 * токену: каталог `Collection_of_all_users/Users` + `email_index/{email}`.
 *
 * Запись идемпотентна: детерминированный id по messageId, merge-запись.
 */
fun writeAdviceNotificationToFeed(
    token: String,
    messageId: String?,
    title: String,
    body: String,
    email: String? = null,
    uid: String? = null,
) {
    val logger = LoggerFactory.getLogger("AdviceFeedService")
    try {
        val db = FirestoreClient.getFirestore()
        val notificationId = adviceNotificationId(messageId)
        val notification = buildAdviceNotification(notificationId, title, body)
        val feedEntry = mapOf(notificationId to notification)

        val targetUid: String?
        val targetEmail: String?
        when (val plan = adviceFeedPlan(email, uid)) {
            is AdviceFeedPlan.Direct -> {
                // Прямой путь: ни каталог, ни email_index не читаются.
                targetUid = plan.uid
                targetEmail = plan.email
                if (targetUid == null) {
                    logger.warn("Advice feed uid path skipped: no uid in request")
                }
                if (targetEmail == null) {
                    logger.warn("Advice feed legacy path skipped: no email in request")
                }
            }
            AdviceFeedPlan.TokenResolve -> {
                // Фолбэк для старых клиентов: резолв по токену (2 чтения).
                val usersSnapshot = db.collection(COLLECTION_ALL_USERS)
                    .document(DOCUMENT_USERS)
                    .get()
                    .get()
                    .data
                    ?: emptyMap<String, Any>()

                val resolvedEmail = resolveEmailByToken(usersSnapshot, token)
                if (resolvedEmail == null) {
                    logger.warn("Advice feed write skipped: no user with matching token")
                    return
                }
                targetEmail = resolvedEmail
                targetUid = resolveUidByEmail(db, resolvedEmail, logger)
                if (targetUid == null) {
                    logger.warn("Advice feed uid path skipped: no email_index entry for $resolvedEmail")
                }
            }
        }

        // Основной путь uid-модели: users/{uid}/data/Notifications.
        targetUid?.let { feedUid ->
            try {
                db.collection(COLLECTION_USERS)
                    .document(feedUid)
                    .collection(SUBCOLLECTION_DATA)
                    .document(DOCUMENT_NOTIFICATIONS)
                    .set(feedEntry, SetOptions.merge())
                logger.info("Advice notification written for $feedUid ($targetEmail), id=$notificationId")
            } catch (e: Exception) {
                logger.error("Failed to write advice notification to users/$feedUid data feed: ${e.message}", e)
            }
        }

        // Легаси-зеркало {email}/Notifications для старых клиентов и фолбэка чтения.
        targetEmail?.let { feedEmail ->
            try {
                db.collection(feedEmail).document(DOCUMENT_NOTIFICATIONS)
                    .set(feedEntry, SetOptions.merge())
                logger.info("Advice notification written (legacy) for $feedEmail, id=$notificationId")
            } catch (e: Exception) {
                logger.error("Failed to write legacy advice notification for $feedEmail: ${e.message}", e)
            }
        }
    } catch (e: Exception) {
        logger.error("Failed to write advice notification to feed: ${e.message}", e)
    }
}
