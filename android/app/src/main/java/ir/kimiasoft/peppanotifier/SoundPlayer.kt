package ir.kimiasoft.peppanotifier

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log

/**
 * Plays the bundled notify.mp3 on each new message. If STREAM_MUSIC is below 5%
 * of max we boost it to 30% temporarily, then restore once playback ends. Uses
 * a ref-counted boost so overlapping plays don't stomp on each other.
 *
 * DND is respected (we do NOT request ACCESS_NOTIFICATION_POLICY).
 */
object SoundPlayer {
    private const val TAG = "SoundPlayer"
    private const val LOW_VOLUME_FRACTION = 0.05
    private const val BOOST_FRACTION = 0.30

    private var originalVolume: Int = -1
    private var boostedPlays: Int = 0
    private val lock = Any()

    fun play(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val boostedThisCall: Boolean = synchronized(lock) {
            val below = cur.toDouble() / max < LOW_VOLUME_FRACTION
            if (below) {
                if (boostedPlays == 0) originalVolume = cur
                val target = (BOOST_FRACTION * max).toInt().coerceAtLeast(1)
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                } catch (t: Throwable) {
                    Log.w(TAG, "could not set volume", t)
                }
                boostedPlays++
                true
            } else {
                false
            }
        }

        val player = try {
            MediaPlayer.create(ctx, R.raw.notify) ?: run {
                if (boostedThisCall) restore(am)
                return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaPlayer.create failed", t)
            if (boostedThisCall) restore(am)
            return
        }
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        player.setOnCompletionListener {
            try { it.release() } catch (_: Throwable) {}
            if (boostedThisCall) restore(am)
        }
        player.setOnErrorListener { mp, _, _ ->
            try { mp.release() } catch (_: Throwable) {}
            if (boostedThisCall) restore(am)
            true
        }
        try {
            player.start()
        } catch (t: Throwable) {
            Log.w(TAG, "player.start failed", t)
            try { player.release() } catch (_: Throwable) {}
            if (boostedThisCall) restore(am)
        }
    }

    private fun restore(am: AudioManager) {
        synchronized(lock) {
            boostedPlays--
            if (boostedPlays <= 0) {
                boostedPlays = 0
                if (originalVolume >= 0) {
                    try {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                    } catch (t: Throwable) {
                        Log.w(TAG, "could not restore volume", t)
                    }
                    originalVolume = -1
                }
            }
        }
    }
}
