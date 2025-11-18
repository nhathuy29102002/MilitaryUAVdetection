package com.militaryuavdetection.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "image_records")
data class ImageRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val uri: String,
    val name: String,
    val dateModified: Long,
    val size: Long,
    val mediaType: String, // "IMAGE" or "VIDEO"
    val width: Int,
    val height: Int,

    var boundingBoxes: String? = null,
    var confidence: String? = null,
    var isSaved: Boolean = false
)
