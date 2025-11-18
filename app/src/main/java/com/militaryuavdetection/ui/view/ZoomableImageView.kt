package com.militaryuavdetection.ui.view

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomableImageView(context: Context, attrs: AttributeSet?) : AppCompatImageView(context, attrs) {

    private var scaleGestureDetector: ScaleGestureDetector
    private var matrixValues = FloatArray(9)
    private var scaleFactor = 1.0f

    init {
        scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
        imageMatrix = Matrix()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = Math.max(1.0f, Math.min(scaleFactor, 5.0f)) // Limit zoom
            val matrix = Matrix()
            matrix.setScale(scaleFactor, scaleFactor)
            imageMatrix = matrix
            return true
        }
    }
}