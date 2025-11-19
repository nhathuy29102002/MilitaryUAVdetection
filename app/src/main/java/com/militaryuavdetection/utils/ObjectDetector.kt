package com.militaryuavdetection.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.util.*
import kotlin.math.max
import kotlin.math.min
import ai.onnxruntime.providers.NNAPIFlags
import android.graphics.Canvas
import android.graphics.Color


class ObjectDetector(private val context: Context) {

    // --- CẤU HÌNH ---
    private val INPUT_SIZE = 640
    private val CONFIDENCE_THRESHOLD = 0.25f
    private val IOU_THRESHOLD = 0.6f

    private var ortSession: OrtSession? = null
    private var ortEnvironment: OrtEnvironment? = null
    private val labels = mutableListOf<String>()

    // Hàm khởi tạo Model (gọi 1 lần lúc init app)
    suspend fun loadModel(modelPath: String, labelPath: String = "models/coco_labels.txt") {
        withContext(Dispatchers.IO) {
            try {
                if (ortEnvironment == null) {
                    ortEnvironment = OrtEnvironment.getEnvironment()
                }
                val modelBytes = context.assets.open(modelPath).readBytes()

                val sessionOptions = OrtSession.SessionOptions()
                try {
                    val nnapiFlags = EnumSet.of(NNAPIFlags.USE_FP16)
                    sessionOptions.addNnapi(nnapiFlags)
                    Log.d("ObjectDetector", "NNAPI Execution Provider is enabled.")
                } catch (e: Exception) {
                    Log.e("ObjectDetector", "Failed to enable NNAPI, falling back to CPU.", e)
                }
                // ------------------------------------

                ortSession = ortEnvironment?.createSession(modelBytes, sessionOptions)

                labels.clear()
                context.assets.open(labelPath).bufferedReader().useLines { lines ->
                    lines.forEach { labels.add(it) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Hàm chính: Phân tích ảnh từ URI
    suspend fun analyzeImage(uri: Uri): List<DetectionResult> = withContext(Dispatchers.IO) {
        // Lấy kích thước ảnh gốc để tính toán tỷ lệ chính xác
        val originalDimensions = getOriginalImageDimensions(uri) ?: return@withContext emptyList()
        val processingBitmap = decodeBitmapFromUri(uri, INPUT_SIZE, INPUT_SIZE) ?: return@withContext emptyList()
        return@withContext analyzeBitmap(processingBitmap, originalDimensions.first, originalDimensions.second)
    }

    // Hàm mới: Phân tích từ Bitmap (dùng cho video frames)
    suspend fun analyzeBitmap(bitmap: Bitmap, originalWidth: Int, originalHeight: Int): List<DetectionResult> = withContext(Dispatchers.IO) {
        if (ortSession == null) return@withContext emptyList()

        // 1. TIỀN XỬ LÝ (Resize & Normalize)
        val (processedBitmap, ratio, padding) = preprocess(bitmap) // Updated to get ratio and padding
        val inputTensorBuffer = bitmapToFloatBuffer(processedBitmap)

        // 2. CHẠY MODEL (INFERENCE)
        val inputName = ortSession?.inputNames?.iterator()?.next() ?: return@withContext emptyList()
        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val env = OrtEnvironment.getEnvironment()
        val inputTensor = OnnxTensor.createTensor(env, inputTensorBuffer, shape)
        val results = ortSession?.run(Collections.singletonMap(inputName, inputTensor))

        // 3. HẬU XỬ LÝ (POST-PROCESS)
        val outputTensor = results?.get(0) as OnnxTensor
        val outputBuffer = outputTensor.floatBuffer
        val tensorShape = outputTensor.info.shape
        val numChannels = tensorShape[1].toInt()
        val numAnchors = tensorShape[2].toInt()

        return@withContext postprocess(outputBuffer, numChannels, numAnchors, ratio, padding.first, padding.second, originalWidth, originalHeight)
    }


    // --- CÁC HÀM HỖ TRỢ ---

    private fun getOriginalImageDimensions(uri: Uri): Pair<Int, Int>? {
        return try {
            val contentResolver = context.contentResolver
            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
            pfd.close()
            Pair(options.outWidth, options.outHeight)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun decodeBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val contentResolver = context.contentResolver
            var pfd = contentResolver.openFileDescriptor(uri, "r") ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
            pfd.close()
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            pfd = contentResolver.openFileDescriptor(uri, "r") ?: return null
            val bitmap = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
            pfd.close()
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun preprocess(bitmap: Bitmap): Triple<Bitmap, Float, Pair<Float, Float>> {
        val originalWidth = bitmap.width.toFloat()
        val originalHeight = bitmap.height.toFloat()

        // Tính tỷ lệ giữ nguyên aspect ratio
        val ratio = min(INPUT_SIZE.toFloat() / originalWidth, INPUT_SIZE.toFloat() / originalHeight)

        val newWidth = (originalWidth * ratio).toInt()
        val newHeight = (originalHeight * ratio).toInt()

        // Tạo ảnh nền đen
        val paddedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(paddedBitmap)
        canvas.drawColor(Color.BLACK)

        // Vẽ ảnh đã resize vào giữa
        val left = (INPUT_SIZE - newWidth) / 2f
        val top = (INPUT_SIZE - newHeight) / 2f
        val rectF = RectF(left, top, left + newWidth, top + newHeight)
        canvas.drawBitmap(bitmap, null, rectF, null)

        // Trả về Padded Bitmap, tỷ lệ scale, và offset (paddingX, paddingY)
        return Triple(paddedBitmap, ratio, Pair(left, top))
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val imageSize = INPUT_SIZE * INPUT_SIZE
        val floatBuffer = FloatBuffer.allocate(3 * imageSize)
        floatBuffer.rewind()
        val pixels = IntArray(imageSize)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in 0 until imageSize) {
            val pixel = pixels[i]
            floatBuffer.put(((pixel shr 16) and 0xFF) / 255.0f) // R
        }
        for (i in 0 until imageSize) {
            val pixel = pixels[i]
            floatBuffer.put(((pixel shr 8) and 0xFF) / 255.0f)  // G
        }
        for (i in 0 until imageSize) {
            val pixel = pixels[i]
            floatBuffer.put((pixel and 0xFF) / 255.0f)          // B
        }
        floatBuffer.rewind()
        return floatBuffer
    }

    private fun postprocess(
        outputBuffer: FloatBuffer, numChannels: Int, numAnchors: Int, ratio: Float, paddingX: Float, paddingY: Float, originalWidth: Int, originalHeight: Int
    ): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()
        outputBuffer.rewind()
        for (i in 0 until numAnchors) {
            var maxScore = -Float.MAX_VALUE
            var classId = -1
            for (c in 4 until numChannels) {
                val score = outputBuffer.get(c * numAnchors + i)
                if (score > maxScore) {
                    maxScore = score
                    classId = c - 4
                }
            }
            if (maxScore > CONFIDENCE_THRESHOLD) {
                val cx = outputBuffer.get(0 * numAnchors + i)
                val cy = outputBuffer.get(1 * numAnchors + i)
                val w = outputBuffer.get(2 * numAnchors + i)
                val h = outputBuffer.get(3 * numAnchors + i)

                // Adjust for padding and scale back to original dimensions
                val x_in_padded_input = cx // Model output coordinates are already in paddedBitmap (INPUT_SIZE x INPUT_SIZE) space
                val y_in_padded_input = cy
                val w_in_padded_input = w
                val h_in_padded_input = h

                // Convert from padded input space to scaled original image space
                val left_scaled = (x_in_padded_input - w_in_padded_input / 2) - paddingX
                val top_scaled = (y_in_padded_input - h_in_padded_input / 2) - paddingY
                val right_scaled = (x_in_padded_input + w_in_padded_input / 2) - paddingX
                val bottom_scaled = (y_in_padded_input + h_in_padded_input / 2) - paddingY

                // Scale back to original image dimensions
                val left = left_scaled / ratio
                val top = top_scaled / ratio
                val right = right_scaled / ratio
                val bottom = bottom_scaled / ratio

                // Clamp the bounding box to the original image dimensions
                val finalLeft = max(0f, min(left, originalWidth.toFloat()))
                val finalTop = max(0f, min(top, originalHeight.toFloat()))
                val finalRight = max(0f, min(right, originalWidth.toFloat()))
                val finalBottom = max(0f, min(bottom, originalHeight.toFloat()))

                val label = labels.getOrElse(classId) { "Unknown" }
                detections.add(DetectionResult(RectF(finalLeft, finalTop, finalRight, finalBottom), label, maxScore))
            }
        }
        return nms(detections)
    }

    private fun nms(detections: List<DetectionResult>): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            results.add(best)
            val iter = sorted.iterator()
            while (iter.hasNext()) {
                val other = iter.next()
                if (calculateIoU(best.boundingBox, other.boundingBox) > IOU_THRESHOLD) {
                    iter.remove()
                }
            }
        }
        return results
    }

    private fun calculateIoU(boxA: RectF, boxB: RectF): Float {
        val xA = max(boxA.left, boxB.left)
        val yA = max(boxA.top, boxB.top)
        val xB = min(boxA.right, boxB.right)
        val yB = min(boxA.bottom, boxB.bottom)
        val interArea = max(0f, xB - xA) * max(0f, yB - yA)
        val boxAArea = (boxA.right - boxA.left) * (boxA.bottom - boxA.top)
        val boxBArea = (boxB.right - boxB.left) * (boxB.bottom - boxB.top)
        return interArea / (boxAArea + boxBArea - interArea)
    }

    data class DetectionResult(
        val boundingBox: RectF,
        val label: String,
        val confidence: Float,
        var trackId: Int? = null // Thêm trường trackId
    )
}