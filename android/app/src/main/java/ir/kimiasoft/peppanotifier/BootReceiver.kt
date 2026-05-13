package ir.kimiasoft.peppanotifier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.token(context) == null) return
        if (Prefs.isExited(context)) return
        PollingService.start(context)
    }
}
