package osa.phomouse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(
    private val onItemClick: (DeviceItem) -> Unit,
    private val onInfoClick: (DeviceItem) -> Unit
) : ListAdapter<DeviceItem, DeviceAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.tv_device_name)
        val infoButton: ImageButton = view.findViewById(R.id.btn_device_info)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.nameText.text = item.name
        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.infoButton.setOnClickListener { onInfoClick(item) }
    }

    class DiffCallback : DiffUtil.ItemCallback<DeviceItem>() {
        override fun areItemsTheSame(oldItem: DeviceItem, newItem: DeviceItem) = oldItem.address == newItem.address
        override fun areContentsTheSame(oldItem: DeviceItem, newItem: DeviceItem) = oldItem == newItem
    }
}
