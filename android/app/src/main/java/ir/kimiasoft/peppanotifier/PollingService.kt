package ir.kimiasoft.peppanotifier

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that polls the server every [POLL_INTERVAL_MS].
 *
 * Scheduling uses **AlarmManager.setAndAllowWhileIdle** rather than a
 * coroutine `delay()`. Coroutine delays go through Handler.postDelayed,
 * which Android Doze batches into ~9-min maintenance windows once the
 * screen has been off for a while. `setAndAllowWhileIdle` is exempt from
 * Doze batching **provided** the app has been removed from battery
 * optimization (Settings → Apps → PeppaNotifier → Battery → Unrestricted).
 */
class PollingService : Service() {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        when (intent?.action) {
            ACTION_STOP -> {
                cancelAlarm()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // ACTION_START, ACTION_KICK, or null (system restart). All three
                // mean: poll now, then schedule the next alarm.
                cancelAlarm()
                triggerPoll()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAlarm()
        scope.cancel()
    }

    private fun startForegroundCompat() {
        val notif = NotificationHelper.buildServiceNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.SERVICE_NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notif)
        }
    }

    private fun triggerPoll() {
        scope.launch {
            if (Prefs.token(this@PollingService) == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            try {
                pollOnce()
            } catch (e: Api.ApiException) {
                if (e.status == 401) {
                    Log.w(TAG, "session invalid, clearing")
                    Prefs.clearSession(this@PollingService)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
                Log.w(TAG, "poll http ${e.status}")
            } catch (t: Throwable) {
                Log.w(TAG, "poll error: ${t.message}")
            }
            scheduleNextPoll()
        }
    }

    private fun pollOnce() {
        val result = Api.poll(this)
        MuteState.apply(result.myMutedUntil, result.mutes)
        val muted = MuteState.isMyMuted()
        if (result.newMessages.isNotEmpty()) {
            MessageStore.apply(result.newMessages)
            for (m in result.newMessages) {
                if (Prefs.isNotified(this, m.id)) continue
                if (muted) {
                    Prefs.markNotified(this, m.id)
                } else {
                    NotificationHelper.fireMessageNotification(this, m)
                    SoundPlayer.play(this)
                }
            }
        }
        if (result.sentUpdates.isNotEmpty()) {
            MessageStore.apply(result.sentUpdates)
        }
    }

    private fun scheduleNextPoll() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + POLL_INTERVAL_MS
        val pi = pendingKick()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "setAndAllowWhileIdle failed", t)
        }
    }

    private fun cancelAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingKick())
    }

    private fun pendingKick(): PendingIntent {
        val intent = Intent(this, PollingService::class.java).setAction(ACTION_KICK)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, 1, intent, flags)
        } else {
            PendingIntent.getService(this, 1, intent, flags)
        }
    }

    companion object {
        private const val TAG = "PollingService"
        private const val POLL_INTERVAL_MS = 15_000L

        const val ACTION_START = "ir.kimiasoft.peppanotifier.START"
        const val ACTION_KICK  = "ir.kimiasoft.peppanotifier.KICK"
        const val ACTION_STOP  = "ir.kimiasoft.peppanotifier.STOP"

        fun start(ctx: Context) {
            if (Prefs.token(ctx) == null) return
            val intent = Intent(ctx, PollingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun kick(ctx: Context) {
            if (Prefs.token(ctx) == null) return
            val intent = Intent(ctx, PollingService::class.java).setAction(ACTION_KICK)
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, PollingService::class.java).setAction(ACTION_STOP)
            try { ctx.startService(intent) } catch (_: Throwable) { /* ignore */ }
        }
    }
}
