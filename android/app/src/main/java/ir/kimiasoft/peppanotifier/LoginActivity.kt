package ir.kimiasoft.peppanotifier

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ir.kimiasoft.peppanotifier.databinding.ActivityLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding

    private val requestNotifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored — service still runs */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.token(this) != null) {
            goToMain()
            return
        }
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnLogin.setOnClickListener { attemptLogin() }
    }

    private fun attemptLogin() {
        val u = b.editUsername.text.toString().trim()
        val p = b.editPassword.text.toString()
        if (u.isEmpty() || p.isEmpty()) return
        b.btnLogin.isEnabled = false
        b.textError.text = ""
        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) { Api.login(this@LoginActivity, u, p) }
                Prefs.saveSession(this@LoginActivity, res.token, res.user)
                Prefs.setExited(this@LoginActivity, false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                PollingService.start(this@LoginActivity)
                goToMain()
            } catch (e: Api.ApiException) {
                b.textError.text = if (e.status == 401) getString(R.string.invalid_credentials)
                else getString(R.string.login_error_generic, "HTTP ${e.status}")
            } catch (e: Throwable) {
                b.textError.text = getString(R.string.login_error_generic, e.message ?: e.javaClass.simpleName)
            } finally {
                b.btnLogin.isEnabled = true
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
