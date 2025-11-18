package com.militaryuavdetection.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.FloatBuffer
import java.util.*

class ObjectDetector(private val context: Context) {

    private var ortSession: OrtSession? = null
    private lateinit var ortEnvironment: OrtEnvironment

    private val labels = mutableListOf<String>()

    suspend fun loadModel(modelPath: String) {
        withContext(Dispatchers.IO) {
            try {
                ortEnvironment = OrtEnvironment.getEnvironment()
                val modelBytes = context.assets.open(modelPath).readBytes()
                ortSession = ortEnvironment.createSession(modelBytes)

                // Load labels from a file in the assets folder
                context.assets.open("models/coco_labels.txt").bufferedReader().useLines { lines ->
                    lines.forEach { labels.add(it) }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun analyzeImage(uri: Uri): List<DetectionResult> = withContext(Dispatchers.IO) {
        if (ortSession == null) {
            return@withContext emptyList()
        }

        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream)

        val (processedBitmap, scaleFactorX, scaleFactorY) = preprocess(bitmap)

        val inputTensor = bitmapToFloatBuffer(processedBitmap)

        val inputName = ortSession?.inputNames?.iterator()?.next()
        val shape = longArrayOf(1, 3, 640, 640) // YOLOv8 default
        val env = OrtEnvironment.getEnvironment()
        val tensor = OnnxTensor.createTensor(env, inputTensor, shape)

        val results = ortSession?.run(Collections.singletonMap(inputName, tensor))

        val outputTensor = results?.get(0) as OnnxTensor
        val outputBuffer = outputTensor.floatBuffer

        return@withContext postprocess(outputBuffer, scaleFactorX, scaleFactorY)
    }

    private fun preprocess(bitmap: Bitmap): Triple<Bitmap, Float, Float> {
        val targetWidth = 640
        val targetHeight = 640
        val scaleFactorX = targetWidth.toFloat() / bitmap.width
        val scaleFactorY = targetHeight.toFloat() / bitmap.height
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        return Triple(scaledBitmap, scaleFactorX, scaleFactorY)
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val imageSize = bitmap.width * bitmap.height
        val pixels = IntArray(imageSize)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val floatBuffer = FloatBuffer.allocate(3 * imageSize)
        floatBuffer.rewind()

        for (i in 0 until imageSize) {
            val pixel = pixels[i]
            floatBuffer.put(((pixel shr 16) and 0xFF) / 255.0f) // R
            floatBuffer.put(((pixel shr 8) and 0xFF) / 255.0f)  // G
            floatBuffer.put((pixel and 0xFF) / 255.0f)           // B
        }
        floatBuffer.rewind()
        return floatBuffer
    }

    private fun postprocess(outputBuffer: FloatBuffer, scaleFactorX: Float, scaleFactorY: Float): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        outputBuffer.rewind()

        // The output format depends on your model. This is a common format for YOLO.
        // [batch, num_detections, 4 (box) + num_classes]
        // We'll iterate through the detections and extract the bounding boxes and confidences.
        // This part needs to be adjusted based on the exact output shape of your model.
        // Assuming a simple [x1, y1, x2, y2, conf, class] format for each detection.
        while (outputBuffer.hasRemaining()) {
            val x1 = outputBuffer.get() / scaleFactorX
            val y1 = outputBuffer.get() / scaleFactorY
            val x2 = outputBuffer.get() / scaleFactorX
            val y2 = outputBuffer.get() / scaleFactorY
            val confidence = outputBuffer.get()
            val classId = outputBuffer.get().toInt()

            if (confidence > 0.5) { // Confidence threshold
                val label = labels.getOrElse(classId) { "Unknown" }
                results.add(DetectionResult(RectF(x1, y1, x2, y2), label, confidence))
            }
        }

        return results
    }

    data class DetectionResult(val boundingBox: RectF, val label: String, val confidence: Float)
}
