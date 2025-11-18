package com.militaryuavdetection.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import com.militaryuavdetection.utils.ObjectDetector

class ZoomableImageView(context: Context, attrs: AttributeSet?) : AppCompatImageView(context, attrs) {

    private var scaleGestureDetector: ScaleGestureDetector
    private val matrixValues = FloatArray(9)
    private var scaleFactor = 1.0f

    private var detections: List<ObjectDetector.DetectionResult> = emptyList()
    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.RED
        strokeWidth = 5.0f
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40.0f
    }

    init {
        scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
        imageMatrix = Matrix()
        scaleType = ScaleType.MATRIX
    }

    fun setDetections(detections: List<ObjectDetector.DetectionResult>) {
        this.detections = detections
        invalidate() // Redraw the view with the new detections
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (drawable == null) return

        // Create a matrix to map original image coordinates to the current view coordinates
        val viewMatrix = imageMatrix
        val drawableRect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        val viewRect = RectF()
        viewMatrix.mapRect(viewRect, drawableRect)

        detections.forEach { detection ->
            val originalBox = detection.boundingBox

            // Map the bounding box coordinates from the original image scale to the view scale
            val mappedBox = RectF(
                originalBox.left / drawable.intrinsicWidth * viewRect.width() + viewRect.left,
                originalBox.top / drawable.intrinsicHeight * viewRect.height() + viewRect.top,
                originalBox.right / drawable.intrinsicWidth * viewRect.width() + viewRect.left,
                originalBox.bottom / drawable.intrinsicHeight * viewRect.height() + viewRect.top
            )

            canvas.drawRect(mappedBox, boxPaint)
            val label = "${detection.label} (${String.format("%.2f", detection.confidence)})"
            canvas.drawText(label, mappedBox.left, mappedBox.top - 10, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(1.0f, 5.0f) // Limit zoom

            val matrix = Matrix(imageMatrix) // Start with the current matrix
            matrix.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
            imageMatrix = matrix

            return true
        }
    }
}