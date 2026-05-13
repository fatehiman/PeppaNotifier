package ir.kimiasoft.peppanotifier

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ir.kimiasoft.peppanotifier.databinding.ItemUserBinding

class UsersAdapter(
    private val onPick: (UserInfo) -> Unit,
    private val onPing: (UserInfo) -> Unit,
) : RecyclerView.Adapter<UsersAdapter.VH>() {

    private var items: List<UserInfo> = emptyList()

    fun submit(list: List<UserInfo>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val b: ItemUserBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(u: UserInfo) {
            b.textName.text = u.username
            b.dot.setImageResource(if (u.online || u.isAll) R.drawable.ic_dot_online else R.drawable.ic_dot_offline)
            if (u.isAll) {
                b.textLastSeen.visibility = View.GONE
                b.btnPing.visibility = View.GONE
            } else if (u.online) {
                b.textLastSeen.visibility = View.GONE
                b.btnPing.visibility = View.VISIBLE
            } else {
                b.textLastSeen.visibility = View.VISIBLE
                b.textLastSeen.text = if (u.lastSeen > 0)
                    b.root.context.getString(R.string.last_seen_fmt, TimeFmt.fmt(u.lastSeen))
                else
                    b.root.context.getString(R.string.offline)
                b.btnPing.visibility = View.VISIBLE
            }
            val now = System.currentTimeMillis() / 1000
            if (!u.isAll && u.mutedUntil > now) {
                b.textZzz.visibility = View.VISIBLE
                b.textZzz.text = b.root.context.getString(
                    R.string.zzz_fmt, TimeFmt.remaining(u.mutedUntil - now)
                )
            } else {
                b.textZzz.visibility = View.GONE
            }
            b.btnPing.setOnClickListener { onPing(u) }
            b.root.setOnClickListener { onPick(u) }
        }
    }
}
