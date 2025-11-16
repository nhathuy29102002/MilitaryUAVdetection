package com.example.militaryuavdetection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

// LƯU Ý: Đây là bản demo đơn giản. Để có hiệu suất tốt nhất,
// hãy xem xét dùng thư viện như "PhotoView"
class ZoomableImageView(context: Context, attrs: AttributeSet?) :
    AppCompatImageView(context, attrs) {

    private val mMatrix = Matrix()
    private val mGestureDetector: GestureDetector
    private val mScaleGestureDetector: ScaleGestureDetector

    private val mMatrixValues = FloatArray(9)
    private var mFocusX = 0f
    private var mFocusY = 0f

    // Trạng thái
    private val NONE = 0
    private val DRAG = 1
    private val ZOOM = 2
    private var mMode = NONE

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = mMatrix

        // 1. Gesture Detector (cho Pan)
        mGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (mMode == DRAG) {
                    mMatrix.postTranslate(-distanceX, -distanceY)
                    checkAndFixBounds()
                    imageMatrix = mMatrix
                    return true
                }
                return false
            }
        })

        // 2. Scale Gesture Detector (cho Zoom)
        mScaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                mFocusX = detector.focusX
                mFocusY = detector.focusY
                mMode = ZOOM
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                var scaleFactor = detector.scaleFactor
                val currentScale = getScale()

                // Giới hạn zoom
                if (currentScale * scaleFactor < 0.5f) { // Min zoom
                    scaleFactor = 0.5f / currentScale
                } else if (currentScale * scaleFactor > 10f) { // Max zoom
                    scaleFactor = 10f / currentScale
                }

                mMatrix.postScale(scaleFactor, scaleFactor, mFocusX, mFocusY)
                checkAndFixBounds()
                imageMatrix = mMatrix
                return true
            }
        })
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        // Khi set ảnh mới, reset zoom/pan
        bm?.let {
            mMatrix.reset()
            // Fit-center ảnh vào view
            val scale: Float
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val bitmapWidth = bm.width.toFloat()
            val bitmapHeight = bm.height.toFloat()

            if (bitmapWidth / viewWidth > bitmapHeight / viewHeight) {
                scale = viewWidth / bitmapWidth
            } else {
                scale = viewHeight / bitmapHeight
            }

            mMatrix.setScale(scale, scale)
            val redundantYSpace = viewHeight - (bitmapHeight * scale)
            val redundantXSpace = viewWidth - (bitmapWidth * scale)
            mMatrix.postTranslate(redundantXSpace / 2, redundantYSpace / 2)

            imageMatrix = mMatrix
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        mScaleGestureDetector.onTouchEvent(event)
        mGestureDetector.onTouchEvent(event)

        val action = event.action
        when (action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                mMode = DRAG
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mMode = NONE
            }
        }
        return true
    }

    private fun getScale(): Float {
        mMatrix.getValues(mMatrixValues)
        return mMatrixValues[Matrix.MSCALE_X]
    }

    // TODO: Thêm logic "checkAndFixBounds()" để ngăn người dùng kéo ảnh ra khỏi màn hình
    private fun checkAndFixBounds() {
        // Đây là phần phức tạp, cần tính toán
    }
}