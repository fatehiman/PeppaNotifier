package ir.kimiasoft.peppanotifier

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ir.kimiasoft.peppanotifier.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: HistoryAdapter
    private var unsubscribeStore: (() -> Unit)? = null
    private var unsubscribeMute: (() -> Unit)? = null

    private val tickHandler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderMuteButton()
            tickHandler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val me = Prefs.user(this)
        val token = Prefs.token(this)
        if (token == null || me == null) {
            goToLogin()
            return
        }
        // Reaching MainActivity always means the user wants the app running again.
        Prefs.setExited(this, false)

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.textMe.text = me
        adapter = HistoryAdapter(me) { other -> openSendTo(other) }
        b.recyclerHistory.layoutManager = LinearLayoutManager(this)
        b.recyclerHistory.adapter = adapter

        b.btnSend.setOnClickListener {
            UsersDialogFragment().show(supportFragmentManager, "users")
        }
        b.btnMute.setOnClickListener {
            MuteDialogFragment().show(supportFragmentManager, "mute")
        }
        b.btnExit.setOnClickListener { doExit() }
        b.btnLogout.setOnClickListener { doLogout() }

        unsubscribeStore = MessageStore.addListener { renderHistory() }
        unsubscribeMute = MuteState.addListener { renderMuteButton() }

        PollingService.start(this)
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
        renderMuteButton()
        loadHistory()
        PollingService.kick(this)
        ackVisibleOpens()
        tickHandler.postDelayed(tick, 30_000L)
    }

    override fun onPause() {
        super.onPause()
        tickHandler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        unsubscribeStore?.invoke()
        unsubscribeMute?.invoke()
        unsubscribeStore = null
        unsubscribeMute = null
    }

    private fun renderHistory() {
        adapter.submit(MessageStore.snapshot())
    }

    private fun renderMuteButton() {
        val until = MuteState.myMutedUntil
        val now = System.currentTimeMillis() / 1000
        if (until > now) {
            b.btnMute.text = getString(R.string.action_mute) + " " + TimeFmt.remaining(until - now)
        } else {
            b.btnMute.text = getString(R.string.action_mute)
        }
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            try {
                val hist = withContext(Dispatchers.IO) { Api.history(this@MainActivity) }
                MessageStore.replaceAll(hist)
            } catch (e: Api.ApiException) {
                if (e.status == 401) doLogout()
            } catch (_: Throwable) { /* keep stale */ }
        }
    }

    private fun ackVisibleOpens() {
        val me = Prefs.user(this) ?: return
        val ids = MessageStore.unopenedReceivedIds(me)
        if (ids.isEmpty()) return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { Api.ackOpened(this@MainActivity, ids) }
                MessageStore.markOpenedLocally(ids, System.currentTimeMillis() / 1000)
            } catch (_: Throwable) { /* will retry on next resume */ }
        }
    }

    private fun openSendTo(other: String) {
        SendDialogFragment.newInstance(other, online = false, lastSeen = 0L)
            .show(supportFragmentManager, "send")
    }

    private fun doLogout() {
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { Api.logout(this@MainActivity) } } catch (_: Throwable) {}
            PollingService.stop(this@MainActivity)
            Prefs.clearSession(this@MainActivity)
            Prefs.setExited(this@MainActivity, false)
            MessageStore.clear()
            MuteState.clear()
            goToLogin()
        }
    }

    private fun doExit() {
        Prefs.setExited(this, true)
        PollingService.stop(this)
        MessageStore.clear()
        MuteState.clear()
        finishAffinity()
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
