package ir.kimiasoft.peppanotifier

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ir.kimiasoft.peppanotifier.databinding.DialogUsersBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UsersDialogFragment : DialogFragment() {

    private var _b: DialogUsersBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: UsersAdapter

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _b = DialogUsersBinding.inflate(layoutInflater)
        adapter = UsersAdapter(
            onPick = { u ->
                dismissAllowingStateLoss()
                SendDialogFragment.newInstance(u.username, u.online, u.lastSeen)
                    .show(requireActivity().supportFragmentManager, "send")
            },
            onPing = { u ->
                val ctx = requireContext().applicationContext
                SendManager.optimisticSend(ctx, u.username, getString(R.string.ping_text))
                dismissAllowingStateLoss()
            }
        )
        b.recyclerUsers.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerUsers.adapter = adapter

        loadUsers()

        return AlertDialog.Builder(requireContext())
            .setView(b.root)
            .create()
    }

    private fun loadUsers() {
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { Api.users(requireContext()) }
                adapter.submit(list)
            } catch (_: Throwable) {
                adapter.submit(emptyList())
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
