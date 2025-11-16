package com.example.militaryuavdetection

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

data class DetectionResult(
    val boundingBox: RectF, // Tọa độ (0.0f - 1.0f)
    val label: String,
    val confidence: Float
)

class BoundingBoxOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var results: List<DetectionResult> = emptyList()

    // 0: None, 1: Box, 2: Box+Class, 3: Box+Class+Conf
    private var markType: Int = 3

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 50f
        style = Paint.Style.FILL
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    // Hàm này được gọi từ MainActivity/ViewModel
    fun setResults(detections: List<DetectionResult>, type: Int) {
        this.results = detections
        this.markType = type
        // Yêu cầu View vẽ lại
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (markType == 0) return // Không vẽ gì cả

        for (result in results) {
            // Chuyển đổi tọa độ tương đối (0.0-1.0) sang tọa độ tuyệt đối của View
            val left = result.boundingBox.left * width
            val top = result.boundingBox.top * height
            val right = result.boundingBox.right * width
            val bottom = result.boundingBox.bottom * height

            val screenRect = RectF(left, top, right, bottom)

            // 1. Vẽ Bounding Box
            canvas.drawRect(screenRect, boxPaint)

            if (markType > 1) { // 2: Box+Class, 3: Box+Class+Conf
                val label = if (markType == 2) {
                    result.label
                } else {
                    "${result.label} ${"%.2f".format(result.confidence)}"
                }

                // 2. Vẽ nền cho text
                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(label, 0, label.length, textBounds)
                val textBgRect = RectF(
                    screenRect.left,
                    screenRect.top - textBounds.height() - 10,
                    screenRect.left + textBounds.width() + 10,
                    screenRect.top
                )
                canvas.drawRect(textBgRect, textBackgroundPaint)

                // 3. Vẽ Text
                canvas.drawText(
                    label,
                    screenRect.left + 5,
                    screenRect.top - 5,
                    textPaint
                )
            }
        }
    }
}