package ir.kimiasoft.peppanotifier

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Prefs {
    private const val FILE = "peppa_prefs"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER = "user"
    private const val KEY_NOTIFIED_IDS = "notified_ids"
    private const val KEY_EXITED = "exited"

    private fun prefs(ctx: Context): SharedPreferences {
        val key = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun token(ctx: Context): String? = prefs(ctx).getString(KEY_TOKEN, null)
    fun user(ctx: Context): String? = prefs(ctx).getString(KEY_USER, null)

    fun isExited(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_EXITED, false)
    fun setExited(ctx: Context, exited: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_EXITED, exited).apply()
    }

    fun saveSession(ctx: Context, token: String, user: String) {
        prefs(ctx).edit().putString(KEY_TOKEN, token).putString(KEY_USER, user).apply()
    }

    fun clearSession(ctx: Context) {
        prefs(ctx).edit().remove(KEY_TOKEN).remove(KEY_USER).remove(KEY_NOTIFIED_IDS).apply()
    }

    /** Tracks which message ids we've already fired OS notifications for, so the service doesn't double-notify. */
    fun isNotified(ctx: Context, id: String): Boolean {
        val set = prefs(ctx).getStringSet(KEY_NOTIFIED_IDS, emptySet()) ?: emptySet()
        return id in set
    }

    fun markNotified(ctx: Context, id: String) {
        val p = prefs(ctx)
        val set = (p.getStringSet(KEY_NOTIFIED_IDS, emptySet()) ?: emptySet()).toMutableSet()
        set.add(id)
        // cap the set to the most recent 500 ids
        val trimmed = if (set.size > 500) set.toList().takeLast(500).toSet() else set
        p.edit().putStringSet(KEY_NOTIFIED_IDS, trimmed).apply()
    }
}
