package com.militaryuavdetection.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.net.toUri
import androidx.recyclerview.widget.RecyclerView
import com.militaryuavdetection.R
import com.militaryuavdetection.database.ImageRecord
import java.text.SimpleDateFormat
import java.util.*

class FileListAdapter(private var records: List<ImageRecord>, private var allRecords: List<ImageRecord>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onItemClick: ((ImageRecord) -> Unit)? = null
    var selectedRecord: ImageRecord? = null
    private var viewMode = ViewMode.ICON

    enum class ViewMode {
        ICON, DETAIL, CONTENT
    }

    override fun getItemViewType(position: Int): Int {
        return viewMode.ordinal
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (ViewMode.values()[viewType]) {
            ViewMode.ICON -> {
                val view = inflater.inflate(R.layout.item_icon_layout, parent, false)
                IconViewHolder(view)
            }
            ViewMode.DETAIL -> {
                val view = inflater.inflate(R.layout.item_detail_layout, parent, false)
                DetailViewHolder(view)
            }
            ViewMode.CONTENT -> {
                val view = inflater.inflate(R.layout.item_content_layout, parent, false)
                ContentViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val record = records[position]

        // Handle selection highlight
        val isSelected = record.id == selectedRecord?.id
        holder.itemView.setBackgroundColor(
            if (isSelected) Color.parseColor("#4c4c52") else Color.TRANSPARENT
        )

        when (holder) {
            is IconViewHolder -> holder.bind(record)
            is DetailViewHolder -> holder.bind(record)
            is ContentViewHolder -> holder.bind(record)
        }
        holder.itemView.setOnClickListener {
            onItemClick?.invoke(record)
        }
    }

    override fun getItemCount() = records.size

    fun setViewMode(mode: ViewMode) {
        if (viewMode != mode) {
            viewMode = mode
            notifyDataSetChanged()
        }
    }

    fun updateSelection(newSelectedRecord: ImageRecord?) {
        val oldSelectedId = selectedRecord?.id
        val newSelectedId = newSelectedRecord?.id

        if (oldSelectedId == newSelectedId) return // No change

        val oldPosition = records.indexOfFirst { it.id == oldSelectedId }
        val newPosition = records.indexOfFirst { it.id == newSelectedId }

        selectedRecord = newSelectedRecord

        if (oldPosition != -1) {
            notifyItemChanged(oldPosition)
        }
        if (newPosition != -1) {
            notifyItemChanged(newPosition)
        }
    }


    fun updateData(newRecords: List<ImageRecord>) {
        allRecords = newRecords
        records = newRecords
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        records = if (query.isEmpty()) {
            allRecords
        } else {
            allRecords.filter { it.name.contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }

    inner class IconViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.item_image)
        fun bind(record: ImageRecord) {
            try {
                imageView.setImageURI(record.uri.toUri())
            } catch (e: SecurityException) {
                imageView.setImageResource(R.drawable.ic_launcher_background) // Example placeholder
            }
        }
    }

    inner class DetailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.item_icon)
        private val nameView: TextView = itemView.findViewById(R.id.item_name)
        private val sizeView: TextView = itemView.findViewById(R.id.item_size)

        fun bind(record: ImageRecord) {
            val icon = if (record.mediaType == "VIDEO") R.drawable.video_icon else R.drawable.image_icon
            iconView.setImageResource(icon)
            nameView.text = record.name
            sizeView.text = "${record.width}x${record.height}"
        }
    }

    inner class ContentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.item_image)
        private val nameView: TextView = itemView.findViewById(R.id.item_name)
        private val dateView: TextView = itemView.findViewById(R.id.item_date)
        private val fileSizeView: TextView = itemView.findViewById(R.id.item_file_size)
        private val imageSizeView: TextView = itemView.findViewById(R.id.item_image_size)

        fun bind(record: ImageRecord) {
            try {
                imageView.setImageURI(record.uri.toUri())
            } catch (e: SecurityException) {
                imageView.setImageResource(R.drawable.ic_launcher_background) // Example placeholder
            }
            nameView.text = record.name
            val date = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(record.dateModified))
            dateView.text = date

            val fileSizeInKB = record.size / 1024
            fileSizeView.text = if (fileSizeInKB > 0) "${fileSizeInKB}KB" else "${record.size}B"

            imageSizeView.text = "${record.width}x${record.height}"
        }
    }
}