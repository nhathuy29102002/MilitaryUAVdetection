package com.militaryuavdetection.ui.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import com.militaryuavdetection.MarkingMode
import com.militaryuavdetection.utils.ObjectDetector
import kotlin.math.max

class ZoomableImageView(context: Context, attrs: AttributeSet?) : AppCompatImageView(context, attrs) {

    private var scaleGestureDetector: ScaleGestureDetector
    private val viewMatrix = Matrix()
    private var currentScale = 1.0f
    private var lastTouch = PointF()
    private var isDragging = false

    private var initialFitScale = 1.0f
    private var minScale = 0.5f
    private var maxScale = 8.0f

    private var detections: List<ObjectDetector.DetectionResult> = emptyList()
    private var markingMode: MarkingMode = MarkingMode.OFF
    private var instanceValues: Map<String, Int> = emptyMap()
    private var colorMap: Map<Int, Int> = emptyMap()

    private val boxPaint = Paint()
    private val textPaint = Paint()
    private val backgroundPaint = Paint()

    var onTransformChanged: (() -> Unit)? = null

    init {
        super.setScaleType(ScaleType.MATRIX)
        scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
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
        fitImage(false)
        invalidate()
    }

    fun updateMarkingMode(newMarkingMode: MarkingMode) {
        this.markingMode = newMarkingMode
        invalidate()
    }

    fun fitImage(notify: Boolean = true) {
        if (drawable == null || width == 0 || height == 0) return

        val dWidth = drawable.intrinsicWidth.toFloat()
        val dHeight = drawable.intrinsicHeight.toFloat()

        val scale = if (dWidth * height > width * dHeight) width / dWidth else height / dHeight
        initialFitScale = scale
        currentScale = scale

        val dx = (width - dWidth * scale) * 0.5f
        val dy = (height - dHeight * scale) * 0.5f

        viewMatrix.reset()
        viewMatrix.setScale(scale, scale)
        viewMatrix.postTranslate(dx, dy)
        imageMatrix = viewMatrix

        if (notify) {
            onTransformChanged?.invoke()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (drawable == null) return
        drawDetectionsOnCanvas(canvas, imageMatrix)
    }

    private fun drawDetectionsOnCanvas(canvas: Canvas, matrix: Matrix) {
        if (drawable == null || markingMode == MarkingMode.OFF) return

        val dWidth = drawable.intrinsicWidth
        val dHeight = drawable.intrinsicHeight

        val drawableRect = RectF(0f, 0f, dWidth.toFloat(), dHeight.toFloat())
        val mappedRect = RectF()
        matrix.mapRect(mappedRect, drawableRect)
        val scaleOnCanvas = mappedRect.width() / dWidth.toFloat()

        val detectionsToDraw = when (markingMode) {
            MarkingMode.SMART -> {
                val top3 = detections
                    .sortedWith(compareByDescending<ObjectDetector.DetectionResult> { instanceValues[it.label] ?: 0 }
                        .thenByDescending { it.confidence })
                    .take(3)
                val rest = detections.minus(top3.toSet())
                top3.map { it to true } + rest.map { it to false } // true = box, false = mark
            }
            else -> detections.map { it to true }
        }

        detectionsToDraw.forEach { (detection, drawBox) ->
            val value = instanceValues[detection.label] ?: 0
            val color = colorMap[value] ?: Color.WHITE
            boxPaint.color = color

            val originalBox = detection.boundingBox
            val mappedBox = RectF().apply {
                matrix.mapRect(this, originalBox)
            }

            // --- THAY ĐỔI LOGIC ĐỘ DÀY VIỀN ---
            val strokeWidth = when {
                dWidth > 2000 || dHeight > 2000 -> max(3f, scaleOnCanvas * 4)
                dWidth > 1200 || dHeight > 1200 -> max(2f, scaleOnCanvas * 3)
                else -> max(1f, scaleOnCanvas * 2)
            }
            boxPaint.strokeWidth = strokeWidth
            // ------------------------------------

            when (markingMode) {
                MarkingMode.MARK -> {
                    boxPaint.style = Paint.Style.FILL
                    val markSize = max(4f, scaleOnCanvas * 6)
                    canvas.drawRect(mappedBox.centerX() - markSize, mappedBox.centerY() - markSize, mappedBox.centerX() + markSize, mappedBox.centerY() + markSize, boxPaint)
                }
                MarkingMode.BOX -> {
                    boxPaint.style = Paint.Style.STROKE
                    canvas.drawRect(mappedBox, boxPaint)
                }
                MarkingMode.NAME, MarkingMode.CONF -> {
                    boxPaint.style = Paint.Style.STROKE
                    canvas.drawRect(mappedBox, boxPaint)
                    val labelText = if (markingMode == MarkingMode.NAME) detection.label else "${detection.label} ${String.format("%.2f", detection.confidence)}"
                    // Truyền kích thước ảnh vào hàm drawLabel
                    drawLabel(canvas, mappedBox, labelText, value, color, scaleOnCanvas, dWidth, dHeight)
                }
                MarkingMode.SMART -> {
                    if (drawBox) {
                        boxPaint.style = Paint.Style.STROKE
                        canvas.drawRect(mappedBox, boxPaint)
                    } else {
                        boxPaint.style = Paint.Style.FILL
                        val markSize = max(2f, scaleOnCanvas * 3)
                        canvas.drawRect(mappedBox.centerX() - markSize, mappedBox.centerY() - markSize, mappedBox.centerX() + markSize, mappedBox.centerY() + markSize, boxPaint)
                    }
                }
                else -> {}
            }
        }
    }

    // Thêm imageWidth và imageHeight vào tham số của hàm
    private fun drawLabel(canvas: Canvas, box: RectF, text: String, value: Int, color: Int, scale: Float, imageWidth: Int, imageHeight: Int) {
        backgroundPaint.color = color

        // --- THAY ĐỔI LOGIC MÀU CHỮ ---
        textPaint.color = if (value == 0 || value == 4) Color.BLACK else Color.WHITE
        // -----------------------------

        // --- THAY ĐỔI LOGIC KÍCH THƯỚC CHỮ ---
        val textSize = when {
            imageWidth > 2000 || imageHeight > 2000 -> max(16f, scale * 22f)
            imageWidth > 1200 || imageHeight > 1200 -> max(14f, scale * 18f)
            else -> max(12f, scale * 15f)
        }
        textPaint.textSize = textSize
        // ------------------------------------
        val padding = textSize / 4f

        val textBounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, textBounds)
        val textHeight = textBounds.height().toFloat()
        val backgroundHeight = textHeight + (2 * padding)
        val backgroundWidth = textPaint.measureText(text) + (2 * padding)

        val backgroundRect = if (box.top - backgroundHeight >= 0) {
            RectF(box.left, box.top - backgroundHeight, box.left + backgroundWidth, box.top)
        } else if (box.bottom + backgroundHeight <= canvas.height) {
            RectF(box.left, box.bottom, box.left + backgroundWidth, box.bottom + backgroundHeight)
        } else {
            RectF(box.left, box.top, box.left + backgroundWidth, box.top + backgroundHeight)
        }

        canvas.drawRect(backgroundRect, backgroundPaint)
        val textY = backgroundRect.top + padding
        canvas.drawText(text, backgroundRect.left + padding, textY - textBounds.top, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch.set(event.x, event.y)
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !scaleGestureDetector.isInProgress) {
                    val dx = event.x - lastTouch.x
                    val dy = event.y - lastTouch.y
                    viewMatrix.postTranslate(dx, dy)
                    imageMatrix = viewMatrix
                    lastTouch.set(event.x, event.y)
                    onTransformChanged?.invoke()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging = false
        }
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scale = detector.scaleFactor
            val newScale = currentScale * scale

            val absoluteMinScale = initialFitScale * minScale
            val absoluteMaxScale = initialFitScale * maxScale

            if (newScale in absoluteMinScale..absoluteMaxScale) {
                currentScale = newScale
                viewMatrix.postScale(scale, scale, detector.focusX, detector.focusY)
                imageMatrix = viewMatrix
                onTransformChanged?.invoke()
            }
            return true
        }
    }

    fun createExportBitmap(): Bitmap? {
        val bmpDrawable = drawable as? BitmapDrawable ?: return null
        val originalBitmap = bmpDrawable.bitmap
        val exportBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(exportBitmap)

        val exportMatrix = Matrix()
        drawDetectionsOnCanvas(canvas, exportMatrix)

        return exportBitmap
    }
}