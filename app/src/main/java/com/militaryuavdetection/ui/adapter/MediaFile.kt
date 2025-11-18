package com.militaryuavdetection.ui.adapter

import android.net.Uri

enum class MediaType {
    IMAGE,
    VIDEO
}

data class MediaFile(
    val uri: Uri,
    val name: String,
    val dateModified: Long,
    val size: Long,
    val type: MediaType
)
