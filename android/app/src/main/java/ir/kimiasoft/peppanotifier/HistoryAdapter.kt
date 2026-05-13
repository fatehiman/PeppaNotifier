package ir.kimiasoft.peppanotifier

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ir.kimiasoft.peppanotifier.databinding.ItemHistoryBinding

class HistoryAdapter(
    private val me: String,
    private val onRowClick: (otherParty: String) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private var items: List<Message> = emptyList()

    fun submit(list: List<Message>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val b: ItemHistoryBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(m: Message) {
            val ctx = b.root.context
            b.textTs.text = TimeFmt.fmt(m.sentAt)
            val isMine = m.sender == me
            val whoLabel = if (isMine) "me → ${m.recipient}" else m.sender
            b.textWho.text = "$whoLabel:"
            b.textMsg.text = m.text

            // Icon: sender side uses tick states (Telegram-style); receiver side uses bell.
            val iconRes: Int = when {
                m.pendingClient -> R.drawable.ic_clock
                m.failedClient  -> R.drawable.ic_warning
                isMine          -> if (m.notifiedAt != null) R.drawable.ic_check_double else R.drawable.ic_check
                m.notifiedState == "notified" -> R.drawable.ic_bell
                m.notifiedState == "missed"   -> R.drawable.ic_bell_off
                else                          -> R.drawable.ic_bell_off
            }
            b.iconRing.setImageResource(iconRes)
            b.iconRing.clearColorFilter()       // each drawable is pre-tinted; don't double-tint
            b.iconRing.alpha = if (m.pendingClient) 0.5f else 1.0f
            b.root.alpha     = if (m.pendingClient) 0.7f else 1.0f

            // Timing line.
            //   sender:  opened HH:MM    (recipient has opened the app)
            //            delivered HH:MM (recipient's client has the message, not yet opened)
            //   receiver: received HH:MM (my own client got the message)
            val timing: Pair<String, Int>? = when {
                m.failedClient ->
                    "failed to send" to androidx.core.content.ContextCompat.getColor(ctx, R.color.failed)
                m.pendingClient ->
                    "sending…" to androidx.core.content.ContextCompat.getColor(ctx, R.color.pending)
                isMine && m.openedAt != null ->
                    "opened ${TimeFmt.fmt(m.openedAt)}" to androidx.core.content.ContextCompat.getColor(ctx, R.color.opened)
                isMine && m.notifiedAt != null ->
                    "delivered ${TimeFmt.fmt(m.notifiedAt)}" to androidx.core.content.ContextCompat.getColor(ctx, R.color.delivered)
                !isMine && m.notifiedAt != null ->
                    "received ${TimeFmt.fmt(m.notifiedAt)}" to androidx.core.content.ContextCompat.getColor(ctx, R.color.delivered)
                else -> null
            }
            if (timing != null) {
                b.textOpened.visibility = android.view.View.VISIBLE
                b.textOpened.text = timing.first
                b.textOpened.setTextColor(timing.second)
            } else {
                b.textOpened.visibility = android.view.View.GONE
            }

            val other = if (isMine) m.recipient else m.sender
            b.root.setOnClickListener {
                if (m.pendingClient || m.failedClient) return@setOnClickListener
                if (other.isNotEmpty() && other != me) onRowClick(other)
            }
        }
    }
}
