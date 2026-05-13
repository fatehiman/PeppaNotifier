package ir.kimiasoft.peppanotifier

import org.json.JSONObject

data class Message(
    val id: String,
    val groupId: String,
    val sender: String,
    val recipient: String,
    val text: String,
    val sentAt: Long,
    val notifiedAt: Long?,
    val notifiedState: String?,   // "notified" | "missed" | null
    val openedAt: Long?,
    val pendingClient: Boolean = false,   // client-only: send HTTP not yet returned
    val failedClient: Boolean = false,    // client-only: send HTTP failed
) {
    companion object {
        fun fromJson(o: JSONObject): Message = Message(
            id = o.getString("id"),
            groupId = o.optString("group_id", o.getString("id")),
            sender = o.getString("sender"),
            recipient = o.getString("recipient"),
            text = o.optString("text", ""),
            sentAt = o.optLong("sent_at", 0),
            notifiedAt = o.optLongOrNull("notified_at"),
            notifiedState = o.optStringOrNull("notified_state"),
            openedAt = o.optLongOrNull("opened_at"),
        )
    }
}

data class UserInfo(
    val username: String,
    val lastSeen: Long,
    val online: Boolean,
    val mutedUntil: Long = 0,
) {
    val isAll: Boolean get() = username == "ALL"

    companion object {
        fun fromJson(o: JSONObject): UserInfo = UserInfo(
            username = o.getString("username"),
            lastSeen = o.optLong("last_seen", 0),
            online = o.optBoolean("online", false),
            mutedUntil = o.optLong("muted_until", 0),
        )
    }
}

fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key) || !has(key)) null else optLong(key)

fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key, null)
