package ir.kimiasoft.peppanotifier

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide in-memory cache of messages. The polling service writes here,
 * the UI reads. Updates are coalesced and delivered on the main thread so the
 * RecyclerView can re-bind without thread-hopping.
 */
object MessageStore {
    private val map: MutableMap<String, Message> = ConcurrentHashMap()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun snapshot(): List<Message> = map.values.sortedByDescending { it.sentAt }

    fun apply(messages: Collection<Message>) {
        if (messages.isEmpty()) return
        for (m in messages) map[m.id] = m
        notifyChanged()
    }

    fun replaceAll(messages: Collection<Message>) {
        map.clear()
        for (m in messages) map[m.id] = m
        notifyChanged()
    }

    fun clear() {
        map.clear()
        notifyChanged()
    }

    fun get(id: String): Message? = map[id]

    fun remove(id: String) {
        if (map.remove(id) != null) notifyChanged()
    }

    fun markFailed(id: String) {
        val m = map[id] ?: return
        if (m.failedClient) return
        map[id] = m.copy(pendingClient = false, failedClient = true)
        notifyChanged()
    }

    fun unopenedReceivedIds(me: String): List<String> =
        map.values.filter { it.recipient == me && it.openedAt == null }.map { it.id }

    fun markOpenedLocally(ids: Collection<String>, at: Long) {
        if (ids.isEmpty()) return
        var changed = false
        for (id in ids) {
            val m = map[id] ?: continue
            if (m.openedAt == null) {
                map[id] = m.copy(openedAt = at)
                changed = true
            }
        }
        if (changed) notifyChanged()
    }

    fun addListener(l: () -> Unit): () -> Unit {
        listeners.add(l)
        return { listeners.remove(l) }
    }

    private fun notifyChanged() {
        for (l in listeners) mainHandler.post(l)
    }
}
