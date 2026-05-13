package ir.kimiasoft.peppanotifier

import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Fire-and-forget send pipeline. Renders an optimistic row in [MessageStore]
 * immediately, then runs the HTTP /send call on the application-wide
 * coroutine scope so dismissing the originating dialog doesn't cancel it.
 *
 * On success the temp row is removed and the server's real per-recipient
 * records are inserted (for ALL sends, this produces N real rows replacing
 * the single temp row). On failure the temp row is marked `failedClient`
 * so the user sees a red warning icon and can re-send manually.
 */
object SendManager {
    private const val TAG = "SendManager"

    fun optimisticSend(ctx: Context, recipient: String, text: String) {
        val app = (ctx.applicationContext as App)
        val me = Prefs.user(app) ?: return
        val now = System.currentTimeMillis() / 1000
        val tmpId = "tmp-" + System.nanoTime().toString(16) + "-" + (0..9999).random()
        val pending = Message(
            id = tmpId,
            groupId = "tmp-$tmpId",
            sender = me,
            recipient = recipient,
            text = text,
            sentAt = now,
            notifiedAt = null,
            notifiedState = null,
            openedAt = null,
            pendingClient = true,
        )
        MessageStore.apply(listOf(pending))

        app.appScope.launch {
            try {
                val res = Api.send(app, recipient, text)
                MessageStore.remove(tmpId)
                if (res.messages.isNotEmpty()) {
                    MessageStore.apply(res.messages)
                }
                PollingService.kick(app)
            } catch (t: Throwable) {
                Log.w(TAG, "send failed", t)
                MessageStore.markFailed(tmpId)
            }
        }
    }
}
