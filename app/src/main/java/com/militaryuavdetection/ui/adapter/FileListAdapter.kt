package com.militaryuavdetection.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.militaryuavdetection.R
import com.militaryuavdetection.data.FileItem
import com.militaryuavdetection.databinding.ListItemIconBinding
import com.militaryuavdetection.databinding.ListItemContentBinding
import com.militaryuavdetection.databinding.ListItemDetailBinding

class FileListAdapter(
    private val onClick: (FileItem) -> Unit
) : ListAdapter<FileItem, RecyclerView.ViewHolder>(FileDiffCallback) {

    companion object {
        const val VIEW_TYPE_ICON = 1
        const val VIEW_TYPE_DETAIL = 2
        const val VIEW_TYPE_CONTENT = 3
    }

    private var currentViewType = VIEW_TYPE_ICON

    fun setViewType(viewType: Int) {
        currentViewType = viewType
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return currentViewType
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_ICON -> IconViewHolder(ListItemIconBinding.inflate(inflater, parent, false), onClick)
            VIEW_TYPE_DETAIL -> DetailViewHolder(ListItemDetailBinding.inflate(inflater, parent, false), onClick)
            VIEW_TYPE_CONTENT -> ContentViewHolder(ListItemContentBinding.inflate(inflater, parent, false), onClick)
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is IconViewHolder -> holder.bind(item)
            is DetailViewHolder -> holder.bind(item)
            is ContentViewHolder -> holder.bind(item)
        }
    }

    class IconViewHolder(private val binding: ListItemIconBinding, val onClick: (FileItem) -> Unit) :
        RecyclerView.ViewHolder(binding.root) {
        private var currentItem: FileItem? = null

        init {
            itemView.setOnClickListener { currentItem?.let { onClick(it) } }
        }

        fun bind(item: FileItem) {
            currentItem = item
            val iconRes = if (item.isVideo) R.drawable.video_icon else R.drawable.ic_launcher_background
            binding.itemImage.setImageResource(iconRes)
        }
    }

    class DetailViewHolder(private val binding: ListItemDetailBinding, val onClick: (FileItem) -> Unit) :
        RecyclerView.ViewHolder(binding.root) {
        private var currentItem: FileItem? = null

        init {
            itemView.setOnClickListener { currentItem?.let { onClick(it) } }
        }

        fun bind(item: FileItem) {
            currentItem = item
            binding.itemName.text = item.name
            binding.itemDate.text = android.text.format.DateFormat.format("dd/MM/yy", item.date)

            val iconRes = if (item.isVideo) R.drawable.video_icon else R.drawable.ic_launcher_background
            binding.itemIcon.setImageResource(iconRes)
        }
    }

    class ContentViewHolder(private val binding: ListItemContentBinding, val onClick: (FileItem) -> Unit) :
        RecyclerView.ViewHolder(binding.root) {
        private var currentItem: FileItem? = null

        init {
            itemView.setOnClickListener { currentItem?.let { onClick(it) } }
        }

        fun bind(item: FileItem) {
            currentItem = item
            binding.itemName.text = item.name
            binding.itemDetails.text = "Ngày: ${android.text.format.DateFormat.format("dd/MM/yy", item.date)} - Kích thước: ${item.size / 1024} KB"
            val iconRes = if (item.isVideo) R.drawable.video_icon else R.drawable.ic_launcher_background
            binding.itemImage.setImageResource(iconRes)
        }
    }
}

object FileDiffCallback : DiffUtil.ItemCallback<FileItem>() {
    override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
        return oldItem == newItem
    }
}
