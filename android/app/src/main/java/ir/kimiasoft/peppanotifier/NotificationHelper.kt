package ir.kimiasoft.peppanotifier

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_MESSAGES = "messages"
    const val SERVICE_NOTIFICATION_ID = 1
    const val BODY_TRUNCATE = 80

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                ctx.getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = ctx.getString(R.string.service_channel_desc)
                setShowBadge(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                ctx.getString(R.string.messages_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = ctx.getString(R.string.messages_channel_desc)
                // We play audio ourselves via SoundPlayer; the channel is silent.
                setSound(null, null)
                enableVibration(true)
            }
        )
    }

    fun buildServiceNotification(ctx: Context): android.app.Notification {
        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
            .setContentTitle(ctx.getString(R.string.service_running))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pi)
            .setShowWhen(false)
            .build()
    }

    fun fireMessageNotification(ctx: Context, m: Message) {
        if (Prefs.isNotified(ctx, m.id)) return
        val text = if (m.text.length > BODY_TRUNCATE) m.text.substring(0, BODY_TRUNCATE - 1) + "…" else m.text
        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("from_notification", true)
        }
        val pi = PendingIntent.getActivity(
            ctx, m.id.hashCode(), openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(m.sender)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(ctx).notify(stableId(m.id), n)
        Prefs.markNotified(ctx, m.id)
    }

    private fun stableId(s: String): Int {
        // hash to a non-zero positive int (avoid 0 to not collide with anything else)
        val h = s.hashCode()
        return if (h == Int.MIN_VALUE) 1 else if (h <= 0) -h else h
    }
}
