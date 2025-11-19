package com.militaryuavdetection.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.militaryuavdetection.utils.ObjectDetector
import kotlin.math.max

class BoundingBoxOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var results: List<ObjectDetector.DetectionResult> = emptyList()
    private var labels: List<String> = emptyList()
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0

    private var markType: Int = 3

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    fun setResults(
        detections: List<ObjectDetector.DetectionResult>,
        type: Int,
        labels: List<String>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        this.results = detections
        this.markType = type
        this.labels = labels
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (markType == 0 || results.isEmpty()) return

        // Adjust stroke width and text size based on image size
        val imageArea = imageWidth * imageHeight
        val strokeWidth = when {
            imageArea > 2000 * 2000 -> 8f
            imageArea > 1000 * 1000 -> 5f
            else -> 3f
        }
        val textSize = when {
            imageArea > 2000 * 2000 -> 50f
            imageArea > 1000 * 1000 -> 35f
            else -> 25f
        }
        boxPaint.strokeWidth = strokeWidth
        textPaint.textSize = textSize
        
        for (result in results) {
            val left = result.boundingBox.left * width
            val top = result.boundingBox.top * height
            val right = result.boundingBox.right * width
            val bottom = result.boundingBox.bottom * height

            val screenRect = RectF(left, top, right, bottom)

            // Ensure the color is set correctly, maybe based on label
            // For now, let's keep it simple as red.
            boxPaint.color = Color.RED
            textBackgroundPaint.color = Color.RED

            canvas.drawRect(screenRect, boxPaint)

            if (markType > 1) {
                val labelText = buildString {
                    result.trackId?.let { append("ID: $it ") }
                    
                    when (markType) {
                        2 -> append(result.label)
                        else -> append("${result.label} ${"%.2f".format(result.confidence)}")
                    }
                }

                val textBounds = Rect()
                textPaint.getTextBounds(labelText, 0, labelText.length, textBounds)
                val textHeight = textBounds.height()
                val textWidth = textBounds.width()

                val textBgRect = RectF(
                    screenRect.left,
                    max(0f, screenRect.top - textHeight - 10), // Prevent drawing outside view
                    screenRect.left + textWidth + 10,
                    screenRect.top
                )
                canvas.drawRect(textBgRect, textBackgroundPaint)

                canvas.drawText(
                    labelText,
                    screenRect.left + 5,
                    screenRect.top - 5,
                    textPaint
                )
            }
        }
    }
}