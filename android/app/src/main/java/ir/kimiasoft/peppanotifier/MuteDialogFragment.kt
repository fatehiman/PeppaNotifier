package ir.kimiasoft.peppanotifier

import android.app.Dialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import ir.kimiasoft.peppanotifier.databinding.DialogMuteBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MuteDialogFragment : DialogFragment() {

    private var _b: DialogMuteBinding? = null
    private val b get() = _b!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _b = DialogMuteBinding.inflate(layoutInflater)
        val now = { System.currentTimeMillis() / 1000 }
        fun off(seconds: Long) { apply(now() + seconds) }

        b.btn5m.setOnClickListener  { off(5 * 60L) }
        b.btn15m.setOnClickListener { off(15 * 60L) }
        b.btn1h.setOnClickListener  { off(60 * 60L) }
        b.btn2h.setOnClickListener  { off(2 * 3600L) }
        b.btn3h.setOnClickListener  { off(3 * 3600L) }
        b.btn8h.setOnClickListener  { off(8 * 3600L) }
        b.btn10h.setOnClickListener { off(10 * 3600L) }
        b.btn12h.setOnClickListener { off(12 * 3600L) }
        b.btn2d.setOnClickListener  { apply(compute2DaysUntil()) }
        b.btnUnmute.setOnClickListener { apply(0L) }
        b.btnUnmute.isEnabled = MuteState.isMyMuted()

        return AlertDialog.Builder(requireContext())
            .setView(b.root)
            .create()
    }

    private fun apply(until: Long) {
        lifecycleScope.launch {
            try {
                val stored = withContext(Dispatchers.IO) { Api.mute(requireContext(), until) }
                MuteState.setMyMute(stored)
                dismissAllowingStateLoss()
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), "Mute failed: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        /** Mirrors web app.js: now + 2 days, snapped forward to the next local 08:00. */
        fun compute2DaysUntil(): Long {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, 2)
            val twoDaysMs = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, 8)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis <= twoDaysMs) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis / 1000
        }
    }
}
