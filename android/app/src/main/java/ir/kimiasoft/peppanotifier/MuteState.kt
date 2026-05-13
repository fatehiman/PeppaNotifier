package ir.kimiasoft.peppanotifier

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide cache of mute state pulled from `/poll`.
 *
 * - [myMutedUntil] controls whether *this* client suppresses sound + OS notifications.
 * - [userMutes] is the snapshot of who else is currently muted, used to render the
 *   Zzz badge in the user list.
 */
object MuteState {
    @Volatile var myMutedUntil: Long = 0
        private set
    @Volatile var userMutes: Map<String, Long> = emptyMap()
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

    fun apply(myUntil: Long, others: Map<String, Long>) {
        val changed = myUntil != myMutedUntil || others != userMutes
        myMutedUntil = myUntil
        userMutes = others
        if (changed) for (l in listeners) handler.post(l)
    }

    fun setMyMute(untilTs: Long) {
        if (untilTs == myMutedUntil) return
        myMutedUntil = untilTs
        for (l in listeners) handler.post(l)
    }

    fun isMyMuted(): Boolean = myMutedUntil > System.currentTimeMillis() / 1000

    fun clear() {
        myMutedUntil = 0
        userMutes = emptyMap()
        for (l in listeners) handler.post(l)
    }

    fun addListener(l: () -> Unit): () -> Unit {
        listeners.add(l)
        return { listeners.remove(l) }
    }
}
