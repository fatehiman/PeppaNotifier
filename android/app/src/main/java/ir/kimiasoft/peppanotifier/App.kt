package ir.kimiasoft.peppanotifier

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    /** Process-wide scope for fire-and-forget work that must outlive any single
     *  activity/fragment — e.g. a /send HTTP call started from a dialog that the
     *  user immediately dismisses. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationHelper.ensureChannels(this)
        if (Prefs.token(this) != null && !Prefs.isExited(this)) {
            PollingService.start(this)
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
