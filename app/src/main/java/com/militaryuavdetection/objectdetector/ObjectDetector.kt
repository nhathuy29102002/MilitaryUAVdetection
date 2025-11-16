package com.example.militaryuavdetection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

// Lớp nội bộ để chứa kết quả (tọa độ 0.0f - 1.0f)
data class DetectionResult(
    val boundingBox: RectF,
    val classId: Int,
    val confidence: Float
) {
    fun getLabel(labels: List<String>): String {
        return if (classId >= 0 && classId < labels.size) labels[classId] else "Unknown"
    }
}

class ObjectDetector(
    private val context: Context,
    private val assetFileName: String // Tên file model trong thư mục assets (ví dụ: "yolov8n.onnx")
) {
    companion object {
        // Cài đặt model (PHẢI GIỐNG VỚI MODEL CỦA BẠN)
        private const val INPUT_WIDTH = 416
        private const val INPUT_HEIGHT = 416
        private const val CONFIDENCE_THRESHOLD = 0.5f // Ngưỡng tin cậy
        private const val IOU_THRESHOLD = 0.4f        // Ngưỡng IoU cho NMS
        private const val NUM_CLASSES = 80            // Số lượng class (ví dụ: 80 cho COCO)
    }

    private var ortSession: OrtSession? = null
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()

    // Khởi tạo model (chạy trên luồng IO)
    suspend fun initialize() {
        if (ortSession != null) return
        withContext(Dispatchers.IO) {
            try {
                val modelFile = copyAssetToCache(assetFileName)
                val sessionOptions = OrtSession.SessionOptions()
                // TODO: Bật NNAPI (để dùng NPU/GPU)
                // sessionOptions.addNnapi(OrtSession.NnapiFlags.USE_FP16)
                ortSession = ortEnv.createSession(modelFile.absolutePath, sessionOptions)
            } catch (e: Exception) {
                Log.e("ObjectDetector", "Khởi tạo model thất bại", e)
            }
        }
    }

    // Hàm chính để phát hiện (chạy trên luồng IO)
    suspend fun detect(bitmap: Bitmap): List<DetectionResult> {
        if (ortSession == null) {
            initialize()
        }
        if (ortSession == null) {
            Log.e("ObjectDetector", "Session chưa được khởi tạo.")
            return emptyList()
        }

        return withContext(Dispatchers.IO) {
            val (inputBuffer, scaleX, scaleY) = preprocess(bitmap)

            val inputShape = longArrayOf(1, 3, INPUT_HEIGHT.toLong(), INPUT_WIDTH.toLong())
            val inputTensor = OnnxTensor.createTensor(ortEnv, inputBuffer, inputShape)
            val inputs = Collections.singletonMap("images", inputTensor) // Tên đầu vào là "images"

            val results = ortSession!!.run(inputs)
            inputTensor.close()

            val detections = postprocess(results, scaleX, scaleY)
            results.close()
            detections
        }
    }

    // Sao chép model từ assets ra cache (ONNX cần đường dẫn file)
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

    // === PHẦN QUAN TRỌNG: TIỀN XỬ LÝ ===
    private fun preprocess(originalBitmap: Bitmap): Triple<FloatBuffer, Float, Float> {
        val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, INPUT_WIDTH, INPUT_HEIGHT, true)

        // Tính toán tỉ lệ scale để map ngược tọa độ
        val scaleX = originalBitmap.width.toFloat() / INPUT_WIDTH.toFloat()
        val scaleY = originalBitmap.height.toFloat() / INPUT_HEIGHT.toFloat()

        val inputBuffer = FloatBuffer.allocate(1 * 3 * INPUT_HEIGHT * INPUT_WIDTH)
        inputBuffer.rewind()

        val intValues = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
        scaledBitmap.getPixels(intValues, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)

        for (row in 0 until INPUT_HEIGHT) {
            for (col in 0 until INPUT_WIDTH) {
                val pixelValue = intValues[row * INPUT_WIDTH + col]
                // YOLOv8 dùng RGB và chuẩn hóa (0-1)
                // [C, H, W]
                inputBuffer.put(row * INPUT_WIDTH + col, (pixelValue shr 16 and 0xFF) / 255.0f) // R
                inputBuffer.put((INPUT_WIDTH * INPUT_HEIGHT) + row * INPUT_WIDTH + col, (pixelValue shr 8 and 0xFF) / 255.0f)  // G
                inputBuffer.put((2 * INPUT_WIDTH * INPUT_HEIGHT) + row * INPUT_WIDTH + col, (pixelValue and 0xFF) / 255.0f) // B
            }
        }
        inputBuffer.rewind()
        return Triple(inputBuffer, scaleX, scaleY)
    }

    // === PHẦN QUAN TRỌNG: HẬU XỬ LÝ (VỚI NMS) ===
    private fun postprocess(results: OrtSession.Result, scaleX: Float, scaleY: Float): List<DetectionResult> {
        // Model YOLOv8 ONNX có 1 đầu ra, shape [1, 84, 8400]
        // 84 = 4 (box) + 80 (classes)
        // 8400 = số lượng box dự đoán
        val outputBuffer = (results.get(0).value as Array<Array<FloatArray>>)[0] // [84, 8400]

        // Chuyển vị (Transpose) từ [84, 8400] -> [8400, 84]
        val numDetections = outputBuffer[0].size // 8400
        val detections = mutableListOf<DetectionResult>()
        val boxes = mutableListOf<RectF>()
        val scores = mutableListOf<Float>()
        val classIds = mutableListOf<Int>()

        for (i in 0 until numDetections) {
            // Tìm class có điểm cao nhất
            var maxScore = 0f
            var maxClassId = -1
            for (j in 4 until (4 + NUM_CLASSES)) {
                if (outputBuffer[j][i] > maxScore) {
                    maxScore = outputBuffer[j][i]
                    maxClassId = j - 4
                }
            }

            if (maxScore > CONFIDENCE_THRESHOLD) {
                // Lấy tọa độ box (cx, cy, w, h)
                val cx = outputBuffer[0][i]
                val cy = outputBuffer[1][i]
                val w = outputBuffer[2][i]
                val h = outputBuffer[3][i]

                // Chuyển sang (left, top, right, bottom)
                val left = (cx - w / 2)
                val top = (cy - h / 2)
                val right = (cx + w / 2)
                val bottom = (cy + h / 2)

                boxes.add(RectF(left, top, right, bottom))
                scores.add(maxScore)
                classIds.add(maxClassId)
            }
        }

        // Chạy Non-Maximum Suppression (NMS)
        val nmsIndices = nonMaxSuppression(boxes, scores, IOU_THRESHOLD)

        for (index in nmsIndices) {
            val box = boxes[index]

            // Map tọa độ về ảnh gốc (0.0 - 1.0)
            // Lưu ý: Tọa độ này là 0-416, chúng ta cần scale về 0-1 của ảnh gốc
            val scaledLeft = max(0f, box.left * scaleX / (INPUT_WIDTH * scaleX))
            val scaledTop = max(0f, box.top * scaleY / (INPUT_HEIGHT * scaleY))
            val scaledRight = min(1f, box.right * scaleX / (INPUT_WIDTH * scaleX))
            val scaledBottom = min(1f, box.bottom * scaleY / (INPUT_HEIGHT * scaleY))

            detections.add(
                DetectionResult(
                    boundingBox = RectF(scaledLeft, scaledTop, scaledRight, scaledBottom),
                    classId = classIds[index],
                    confidence = scores[index]
                )
            )
        }

        return detections
    }

    // Hàm NMS
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

    // Hàm tính IoU (Intersection over Union)
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