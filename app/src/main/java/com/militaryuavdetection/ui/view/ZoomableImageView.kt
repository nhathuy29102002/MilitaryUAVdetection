package com.militaryuavdetection.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import com.militaryuavdetection.MarkingMode
import com.militaryuavdetection.utils.ObjectDetector

class ZoomableImageView(context: Context, attrs: AttributeSet?) : AppCompatImageView(context, attrs) {

    private var scaleGestureDetector: ScaleGestureDetector
    private val matrixValues = FloatArray(9)
    private var scaleFactor = 1.0f

    private var detections: List<ObjectDetector.DetectionResult> = emptyList()
    private var markingMode: MarkingMode = MarkingMode.OFF
    private var instanceValues: Map<String, Int> = emptyMap()
    private var colorMap: Map<Int, Int> = emptyMap()

    private val boxPaint = Paint()
    private val textPaint = Paint().apply {
        textSize = 40.0f
    }
    private val backgroundPaint = Paint()

    init {
        scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
        imageMatrix = Matrix()
        scaleType = ScaleType.MATRIX
    }

    fun setDetections(
        detections: List<ObjectDetector.DetectionResult>,
        markingMode: MarkingMode,
        instanceValues: Map<String, Int>,
        colorMap: Map<Int, Int>
    ) {
        this.detections = detections
        this.markingMode = markingMode
        this.instanceValues = instanceValues
        this.colorMap = colorMap
        invalidate() // Redraw the view with the new detections
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (drawable == null || markingMode == MarkingMode.OFF) return

        val viewMatrix = imageMatrix
        val drawableRect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        val viewRect = RectF()
        viewMatrix.mapRect(viewRect, drawableRect)

        val detectionsToDraw = when (markingMode) {
            MarkingMode.SMART -> {
                val top3 = detections
                    .sortedWith(compareByDescending<ObjectDetector.DetectionResult> { instanceValues[it.label] ?: 0 }
                        .thenByDescending { it.confidence })
                    .take(3)
                val rest = detections.minus(top3.toSet())
                top3.map { it to true } + rest.map { it to false } // true = box, false = mark
            }
            else -> detections.map { it to true } // All others get a "box" (or label)
        }

        detectionsToDraw.forEach { (detection, drawBox) ->
            val value = instanceValues[detection.label] ?: 0
            val color = colorMap[value] ?: Color.WHITE
            boxPaint.color = color

            val originalBox = detection.boundingBox
            val mappedBox = RectF(
                originalBox.left / drawable.intrinsicWidth * viewRect.width() + viewRect.left,
                originalBox.top / drawable.intrinsicHeight * viewRect.height() + viewRect.top,
                originalBox.right / drawable.intrinsicWidth * viewRect.width() + viewRect.left,
                originalBox.bottom / drawable.intrinsicHeight * viewRect.height() + viewRect.top
            )

            val centerX = mappedBox.centerX()
            val centerY = mappedBox.centerY()

            when (markingMode) {
                MarkingMode.MARK -> {
                    boxPaint.style = Paint.Style.FILL
                    canvas.drawRect(centerX - 18, centerY - 18, centerX + 18, centerY + 18, boxPaint)
                }
                MarkingMode.BOX -> {
                    boxPaint.style = Paint.Style.STROKE
                    boxPaint.strokeWidth = 5.0f
                    canvas.drawRect(mappedBox, boxPaint)
                }
                MarkingMode.NAME -> {
                    boxPaint.style = Paint.Style.STROKE
                    boxPaint.strokeWidth = 5.0f
                    canvas.drawRect(mappedBox, boxPaint)
                    drawLabel(canvas, mappedBox, detection.label, value, color)
                }
                MarkingMode.CONF -> {
                    boxPaint.style = Paint.Style.STROKE
                    boxPaint.strokeWidth = 5.0f
                    canvas.drawRect(mappedBox, boxPaint)
                    val label = "${detection.label} ${String.format("%.2f", detection.confidence)}"
                    drawLabel(canvas, mappedBox, label, value, color)
                }
                MarkingMode.SMART -> {
                    if (drawBox) {
                        boxPaint.style = Paint.Style.STROKE
                        boxPaint.strokeWidth = 5.0f
                        canvas.drawRect(mappedBox, boxPaint)
                    } else {
                        boxPaint.style = Paint.Style.FILL
                        canvas.drawRect(centerX - 8, centerY - 8, centerX + 8, centerY + 8, boxPaint)
                    }
                }
                else -> {}
            }
        }
    }

    private fun drawLabel(canvas: Canvas, box: RectF, text: String, value: Int, color: Int) {
        // 1. Set colors
        backgroundPaint.color = color
        textPaint.color = if (value == 0) Color.BLACK else Color.WHITE

        // 2. Measure text
        val padding = 8f
        val textBounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, textBounds)
        val textHeight = textBounds.height().toFloat()

        val backgroundHeight = textHeight + (2 * padding)
        val backgroundWidth = textPaint.measureText(text) + (2 * padding)

        // 3. Determine position and draw
        var backgroundRect: RectF

        // Try position above the box
        if (box.top - backgroundHeight >= 0) {
            backgroundRect = RectF(box.left, box.top - backgroundHeight, box.left + backgroundWidth, box.top)
        } 
        // Else, try position below the box
        else if (box.bottom + backgroundHeight <= height) {
             backgroundRect = RectF(box.left, box.bottom, box.left + backgroundWidth, box.bottom + backgroundHeight)
        } 
        // Else, position inside top-left corner
        else {
            backgroundRect = RectF(box.left, box.top, box.left + backgroundWidth, box.top + backgroundHeight)
        }

        canvas.drawRect(backgroundRect, backgroundPaint)
        // Adjust Y position for text baseline
        val textY = backgroundRect.centerY() + textHeight / 2 - textBounds.bottom / 2
        canvas.drawText(text, backgroundRect.left + padding, textY, textPaint)
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(1.0f, 5.0f)

            val matrix = Matrix(imageMatrix)
            matrix.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
            imageMatrix = matrix

            return true
        }
    }
}
