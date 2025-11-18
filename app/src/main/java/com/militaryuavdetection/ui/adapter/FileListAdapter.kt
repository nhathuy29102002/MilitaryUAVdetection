package com.militaryuavdetection.ui.adapter

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

class FileListAdapter(private var records: List<ImageRecord>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onItemClick: ((ImageRecord) -> Unit)? = null
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
        viewMode = mode
        notifyDataSetChanged()
    }

    fun updateData(newRecords: List<ImageRecord>) {
        records = newRecords
        notifyDataSetChanged()
    }

    inner class IconViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.item_image)
        fun bind(record: ImageRecord) {
            imageView.setImageURI(record.uri.toUri())
        }
    }

    inner class DetailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.item_icon)
        private val nameView: TextView = itemView.findViewById(R.id.item_name)
        private val dateView: TextView = itemView.findViewById(R.id.item_date)
        private val sizeView: TextView = itemView.findViewById(R.id.item_size)

        fun bind(record: ImageRecord) {
            val icon = if (record.mediaType == "VIDEO") R.drawable.importvideo else R.drawable.importimage
            iconView.setImageResource(icon)
            nameView.text = record.name
            dateView.text = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(record.dateModified))
            sizeView.text = "${record.width}x${record.height}"
        }
    }

    inner class ContentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.item_image)
        private val nameView: TextView = itemView.findViewById(R.id.item_name)
        private val detailsView: TextView = itemView.findViewById(R.id.item_details)

        fun bind(record: ImageRecord) {
            imageView.setImageURI(record.uri.toUri())
            nameView.text = record.name
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(record.dateModified))
            detailsView.text = "$date - ${record.width}x${record.height}"
        }
    }
}