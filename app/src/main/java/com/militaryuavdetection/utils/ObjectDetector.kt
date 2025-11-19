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
import java.nio.FloatBuffer
import java.util.*
import kotlin.math.max
import kotlin.math.min

class ObjectDetector(private val context: Context) {

    // --- CẤU HÌNH ---
    private val INPUT_SIZE = 416
    private val CONFIDENCE_THRESHOLD = 0.25f
    private val IOU_THRESHOLD = 0.5f

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
                ortSession = ortEnvironment?.createSession(modelBytes)

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
        if (ortSession == null) return@withContext emptyList()

        // Lấy kích thước ảnh gốc để tính toán tỷ lệ chính xác
        val originalDimensions = getOriginalImageDimensions(uri) ?: return@withContext emptyList()
        val originalWidth = originalDimensions.first
        val originalHeight = originalDimensions.second

        // 1. TẢI ẢNH TỐI ƯU (Tránh OutOfMemory với ảnh lớn)
        // Tải bitmap đã được giảm tỷ lệ (sub-sampled) để xử lý
        val processingBitmap = decodeBitmapFromUri(uri, INPUT_SIZE, INPUT_SIZE) ?: return@withContext emptyList()

        // 2. TIỀN XỬ LÝ (Resize & Normalize)
        // Resize ảnh đã sub-sample về kích thước input của model
        val (processedBitmap, _, _) = preprocess(processingBitmap)
        val inputTensorBuffer = bitmapToFloatBuffer(processedBitmap)

        // Tính toán tỷ lệ scale cuối cùng dựa trên kích thước ẢNH GỐC
        val finalScaleX = INPUT_SIZE.toFloat() / originalWidth
        val finalScaleY = INPUT_SIZE.toFloat() / originalHeight


        // 3. CHẠY MODEL (INFERENCE)
        val inputName = ortSession?.inputNames?.iterator()?.next() ?: return@withContext emptyList()
        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val env = OrtEnvironment.getEnvironment()
        val inputTensor = OnnxTensor.createTensor(env, inputTensorBuffer, shape)
        val results = ortSession?.run(Collections.singletonMap(inputName, inputTensor))

        // 4. HẬU XỬ LÝ (POST-PROCESS)
        val outputTensor = results?.get(0) as OnnxTensor
        val outputBuffer = outputTensor.floatBuffer

        val tensorShape = outputTensor.info.shape
        val numChannels = tensorShape[1].toInt()
        val numAnchors = tensorShape[2].toInt()

        // Trả về danh sách đã lọc NMS với tỷ lệ scale chính xác
        return@withContext postprocess(outputBuffer, numChannels, numAnchors, finalScaleX, finalScaleY)
    }

    // --- CÁC HÀM HỖ TRỢ ---

    // Hàm mới: Lấy kích thước ảnh gốc mà không cần tải toàn bộ ảnh vào bộ nhớ
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


    // Tải bitmap thông minh: Tự tính toán inSampleSize để không tải ảnh gốc quá lớn
    private fun decodeBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val contentResolver = context.contentResolver

            // Bước 1: Chỉ đọc kích thước ảnh để tính toán inSampleSize
            var pfd = contentResolver.openFileDescriptor(uri, "r") ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
            pfd.close()

            // Bước 2: Tính toán tỷ lệ nén (inSampleSize)
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false

            // Bước 3: Đọc ảnh thật với tỷ lệ nén
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

    private fun preprocess(bitmap: Bitmap): Triple<Bitmap, Float, Float> {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        // Tỷ lệ này chỉ dùng để tham khảo, không còn dùng để tính toán tọa độ cuối cùng
        val scaleX = INPUT_SIZE.toFloat() / bitmap.width
        val scaleY = INPUT_SIZE.toFloat() / bitmap.height
        return Triple(scaledBitmap, scaleX, scaleY)
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val imageSize = INPUT_SIZE * INPUT_SIZE
        val floatBuffer = FloatBuffer.allocate(3 * imageSize)
        floatBuffer.rewind()

        val pixels = IntArray(imageSize)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        // Chuẩn hóa 0-255 về 0-1 và sắp xếp theo kênh RRRGGGBBB (Planar)
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
        outputBuffer: FloatBuffer, numChannels: Int, numAnchors: Int, scaleX: Float, scaleY: Float
    ): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()
        outputBuffer.rewind()

        for (i in 0 until numAnchors) {
            // Tìm class có điểm cao nhất
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

                // Chuyển về tọa độ gốc của ảnh ban đầu (chia cho scaleX, scaleY)
                // scaleX và scaleY giờ đây được tính toán dựa trên kích thước ảnh gốc
                val left = (cx - w / 2) / scaleX
                val top = (cy - h / 2) / scaleY
                val right = (cx + w / 2) / scaleX
                val bottom = (cy + h / 2) / scaleY

                val label = labels.getOrElse(classId) { "Unknown" }
                detections.add(DetectionResult(RectF(left, top, right, bottom), label, maxScore))
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

    data class DetectionResult(val boundingBox: RectF, val label: String, val confidence: Float)
}