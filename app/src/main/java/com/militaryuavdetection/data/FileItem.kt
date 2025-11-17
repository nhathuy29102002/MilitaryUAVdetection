package com.militaryuavdetection.data

import android.net.Uri

data class FileItem(
    val id: String,
    val name: String,
    val uri: Uri,
    val isVideo: Boolean,
    val date: Long,
    val size: Long
)
