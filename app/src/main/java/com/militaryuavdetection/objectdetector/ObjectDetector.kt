package com.militaryuavdetection.objectdetector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import com.militaryuavdetection.data.DetectionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

class ObjectDetector(
    private val context: Context,
    private val assetFileName: String
) {
    companion object {
        private const val INPUT_WIDTH = 416
        private const val INPUT_HEIGHT = 416
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val IOU_THRESHOLD = 0.4f

        private const val LABELS_FILE = "coco_labels.txt"
        val LABELS = mutableListOf<String>()
    }

    private var ortSession: OrtSession? = null
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()

    suspend fun initialize() {
        if (ortSession != null) return
        withContext(Dispatchers.IO) {
            try {
                if (LABELS.isEmpty()) {
                    context.assets.open(LABELS_FILE).bufferedReader().useLines { lines ->
                        lines.forEach { LABELS.add(it) }
                    }
                    Log.d("ObjectDetector", "Loaded ${LABELS.size} labels.")
                }

                val modelFile = copyAssetToCache(assetFileName)
                val sessionOptions = OrtSession.SessionOptions()
                try {
                    sessionOptions.addNnapi()
                    Log.i("ObjectDetector", "NNAPI enabled.")
                } catch (e: Exception) {
                    Log.w("ObjectDetector", "Could not enable NNAPI: ${e.message}")
                }
                ortSession = ortEnv.createSession(modelFile.absolutePath, sessionOptions)
            } catch (e: Exception) {
                Log.e("ObjectDetector", "Model initialization failed", e)
            }
        }
    }

    suspend fun detect(bitmap: Bitmap): List<DetectionResult> {
        if (ortSession == null) {
            initialize()
        }
        if (ortSession == null) {
            Log.e("ObjectDetector", "Session not initialized.")
            return emptyList()
        }

        return withContext(Dispatchers.IO) {
            val (inputBuffer, scaleX, scaleY) = preprocess(bitmap)

            val inputShape = longArrayOf(1, 3, INPUT_HEIGHT.toLong(), INPUT_WIDTH.toLong())
            val inputTensor = OnnxTensor.createTensor(ortEnv, inputBuffer, inputShape)
            val inputs = Collections.singletonMap("images", inputTensor)

            val results = ortSession!!.run(inputs)
            inputTensor.close()

            val detections = postprocess(results, bitmap, scaleX, scaleY)
            results.close()
            detections
        }
    }

    private fun copyAssetToCache(fileName: String): File {
        val cacheFile = File(context.cacheDir, fileName)
        if (!cacheFile.exists()) {
            context.assets.open(fileName).use { inputStream ->
                FileOutputStream(cacheFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        return cacheFile
    }

    private fun preprocess(originalBitmap: Bitmap): Triple<FloatBuffer, Float, Float> {
        val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, INPUT_WIDTH, INPUT_HEIGHT, true)

        val scaleX = originalBitmap.width.toFloat() / INPUT_WIDTH.toFloat()
        val scaleY = originalBitmap.height.toFloat() / INPUT_HEIGHT.toFloat()

        val inputBuffer = FloatBuffer.allocate(1 * 3 * INPUT_HEIGHT * INPUT_WIDTH)
        inputBuffer.rewind()

        val intValues = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
        scaledBitmap.getPixels(intValues, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)

        for (row in 0 until INPUT_HEIGHT) {
            for (col in 0 until INPUT_WIDTH) {
                val pixelValue = intValues[row * INPUT_WIDTH + col]
                inputBuffer.put(row * INPUT_WIDTH + col, (pixelValue shr 16 and 0xFF) / 255.0f) // R
                inputBuffer.put((INPUT_WIDTH * INPUT_HEIGHT) + row * INPUT_WIDTH + col, (pixelValue shr 8 and 0xFF) / 255.0f)  // G
                inputBuffer.put((2 * INPUT_WIDTH * INPUT_HEIGHT) + row * INPUT_WIDTH + col, (pixelValue and 0xFF) / 255.0f) // B
            }
        }
        inputBuffer.rewind()
        return Triple(inputBuffer, scaleX, scaleY)
    }

    private fun postprocess(results: OrtSession.Result, originalBitmap: Bitmap, scaleX: Float, scaleY: Float): List<DetectionResult> {
        val outputBuffer = (results.get(0).value as Array<Array<FloatArray>>)[0]
        val numClasses = LABELS.size
        if (numClasses == 0) {
            Log.e("ObjectDetector", "Labels not loaded, cannot process results.")
            return emptyList()
        }

        val numDetections = outputBuffer[0].size
        val boxes = mutableListOf<RectF>()
        val scores = mutableListOf<Float>()
        val classIds = mutableListOf<Int>()

        for (i in 0 until numDetections) {
            var maxScore = 0f
            var maxClassId = -1
            for (j in 4 until (4 + numClasses)) {
                 if (outputBuffer.size <= j || outputBuffer[j].size <= i) {
                     Log.e("ObjectDetector", "Index out of bounds when accessing outputBuffer.")
                     continue
                }
                if (outputBuffer[j][i] > maxScore) {
                    maxScore = outputBuffer[j][i]
                    maxClassId = j - 4
                }
            }

            if (maxScore > CONFIDENCE_THRESHOLD) {
                val cx = outputBuffer[0][i]
                val cy = outputBuffer[1][i]
                val w = outputBuffer[2][i]
                val h = outputBuffer[3][i]
                val left = (cx - w / 2)
                val top = (cy - h / 2)
                val right = (cx + w / 2)
                val bottom = (cy + h / 2)
                boxes.add(RectF(left, top, right, bottom))
                scores.add(maxScore)
                classIds.add(maxClassId)
            }
        }

        val nmsIndices = nonMaxSuppression(boxes, scores, IOU_THRESHOLD)
        val detections = mutableListOf<DetectionResult>()

        for (index in nmsIndices) {
            val box = boxes[index]
            val classId = classIds[index]
            val score = scores[index]

            val scaledLeft = max(0f, box.left * scaleX) / originalBitmap.width
            val scaledTop = max(0f, box.top * scaleY) / originalBitmap.height
            val scaledRight = min(originalBitmap.width.toFloat(), box.right * scaleX) / originalBitmap.width
            val scaledBottom = min(originalBitmap.height.toFloat(), box.bottom * scaleY) / originalBitmap.height

            detections.add(
                DetectionResult(
                    boundingBox = RectF(scaledLeft, scaledTop, scaledRight, scaledBottom),
                    label = LABELS.getOrElse(classId) { "Unknown" },
                    confidence = score
                )
            )
        }

        return detections
    }

    private fun nonMaxSuppression(boxes: List<RectF>, scores: List<Float>, iouThreshold: Float): List<Int> {
        val indices = scores.indices.sortedByDescending { scores[it] }.toMutableList()
        val selectedIndices = mutableListOf<Int>()

        while (indices.isNotEmpty()) {
            val currentIdx = indices.removeAt(0)
            selectedIndices.add(currentIdx)

            val iterator = indices.iterator()
            while (iterator.hasNext()) {
                val nextIdx = iterator.next()
                val iou = calculateIoU(boxes[currentIdx], boxes[nextIdx])
                if (iou > iouThreshold) {
                    iterator.remove()
                }
            }
        }
        return selectedIndices
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val x1 = max(box1.left, box2.left)
        val y1 = max(box1.top, box2.top)
        val x2 = min(box1.right, box2.right)
        val y2 = min(box1.bottom, box2.bottom)
        val intersectionArea = max(0f, x2 - x1) * max(0f, y2 - y1)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = box1Area + box2Area - intersectionArea
        return if (unionArea == 0f) 0f else intersectionArea / unionArea
    }

    fun close() {
        ortSession?.close()
    }
}