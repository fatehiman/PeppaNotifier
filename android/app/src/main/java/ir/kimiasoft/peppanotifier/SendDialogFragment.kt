package ir.kimiasoft.peppanotifier

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import ir.kimiasoft.peppanotifier.databinding.DialogSendBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SendDialogFragment : DialogFragment() {

    private var _b: DialogSendBinding? = null
    private val b get() = _b!!

    private val recipient: String get() = requireArguments().getString(ARG_RECIPIENT)!!
    private val online: Boolean get() = requireArguments().getBoolean(ARG_ONLINE, false)
    private val lastSeen: Long get() = requireArguments().getLong(ARG_LAST_SEEN, 0)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _b = DialogSendBinding.inflate(layoutInflater)
        renderTitle()
        b.btnCancel.setOnClickListener { dismissAllowingStateLoss() }
        b.btnSend.setOnClickListener { doSend(b.editText.text.toString().trim()) }
        b.btnPing.setOnClickListener { doSend(getString(R.string.ping_text)) }

        // If we don't have fresh user state, fetch in background to update the title.
        if (recipient != "ALL" && lastSeen == 0L && !online) refreshTitleFromServer()

        return AlertDialog.Builder(requireContext())
            .setView(b.root)
            .create()
    }

    private fun renderTitle() {
        b.textRecipient.text = recipient
        val isAll = recipient == "ALL"
        b.dot.setImageResource(if (online || isAll) R.drawable.ic_dot_online else R.drawable.ic_dot_offline)
        if (isAll || online) {
            b.textLastSeen.visibility = View.GONE
        } else {
            b.textLastSeen.visibility = View.VISIBLE
            b.textLastSeen.text = if (lastSeen > 0)
                getString(R.string.last_seen_paren_fmt, TimeFmt.fmt(lastSeen))
            else
                getString(R.string.offline)
        }
    }

    private fun refreshTitleFromServer() {
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { Api.users(requireContext()) }
                val u = list.firstOrNull { it.username == recipient } ?: return@launch
                requireArguments().putBoolean(ARG_ONLINE, u.online)
                requireArguments().putLong(ARG_LAST_SEEN, u.lastSeen)
                renderTitle()
            } catch (_: Throwable) { /* keep stale */ }
        }
    }

    private fun doSend(text: String) {
        if (text.isEmpty()) return
        val ctx = requireContext().applicationContext
        SendManager.optimisticSend(ctx, recipient, text)
        dismissAllowingStateLoss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        private const val ARG_RECIPIENT = "recipient"
        private const val ARG_ONLINE = "online"
        private const val ARG_LAST_SEEN = "last_seen"

        fun newInstance(recipient: String, online: Boolean, lastSeen: Long) =
            SendDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_RECIPIENT, recipient)
                    putBoolean(ARG_ONLINE, online)
                    putLong(ARG_LAST_SEEN, lastSeen)
                }
            }
    }
}
