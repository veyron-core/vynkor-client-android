package dev.vynkor.agent

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.vynkor.agent.agent.HostProfile

class ProfileAdapter(
    private val onSelect: (HostProfile) -> Unit,
    private val onEdit: (HostProfile) -> Unit,
    private val onDelete: (HostProfile) -> Unit,
) : RecyclerView.Adapter<ProfileAdapter.Holder>() {

    private var items: List<HostProfile> = emptyList()
    private var activeId: String? = null

    fun submit(list: List<HostProfile>, activeId: String?) {
        items = list
        this.activeId = activeId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_profile, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.profileName)
        private val host: TextView = view.findViewById(R.id.profileHost)
        private val active: TextView = view.findViewById(R.id.profileActive)
        private val edit: ImageButton = view.findViewById(R.id.profileEdit)
        private val delete: ImageButton = view.findViewById(R.id.profileDelete)

        fun bind(profile: HostProfile) {
            name.text = profile.name.ifBlank { name.context.getString(R.string.unnamed_profile) }
            host.text = profile.hostUrl
            active.visibility = if (profile.id == activeId) View.VISIBLE else View.GONE
            edit.setOnClickListener { onEdit(profile) }
            delete.setOnClickListener { onDelete(profile) }
            itemView.setOnClickListener { onSelect(profile) }
        }
    }
}
