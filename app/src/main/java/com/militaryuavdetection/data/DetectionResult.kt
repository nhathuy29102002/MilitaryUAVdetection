package com.militaryuavdetection.data

import android.graphics.RectF

/**
 * Data class to hold detection results for a single object.
 */
data class DetectionResult(
    val boundingBox: RectF, // Bounding box in normalized coordinates (0.0f - 1.0f)
    val label: String,
    val confidence: Float
)
