package com.militaryuavdetection.utils

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

// Lớp chính để quản lý việc theo dõi
class ByteTrackManager(
    private val frameRate: Int = 30,
    private val trackBuffer: Int = 30, // Số frame giữ lại một track đã mất
    private val highConfidenceThreshold: Float = 0.6f,
    private val lowConfidenceThreshold: Float = 0.1f,
    private val nmsThreshold: Float = 0.7f
) {

    private var trackIdCounter = 0
    private var frameId = 0

    private var trackedTracks = mutableListOf<STrack>()
    private var lostTracks = mutableListOf<STrack>()
    private var removedTracks = mutableListOf<STrack>()

    // Hàm chính, nhận diện các phát hiện từ model và trả về các track đã cập nhật
    fun update(detections: List<ObjectDetector.DetectionResult>): List<STrack> {
        frameId++

        // --- Bước 1: Phân loại detections thành 2 nhóm: high-confidence và low-confidence ---
        val highConfDetections = detections.filter { it.confidence >= highConfidenceThreshold }
        val lowConfDetections = detections.filter { it.confidence >= lowConfidenceThreshold && it.confidence < highConfidenceThreshold }

        // Chuyển đổi Detections thành STrack
        val highConfTracks = highConfDetections.map { STrack.fromDetection(it) }
        val lowConfTracks = lowConfDetections.map { STrack.fromDetection(it) }

        // --- Bước 2: Dự đoán vị trí tiếp theo cho các track đang có ---
        val predictedTracks = mutableListOf<STrack>()
        for (track in trackedTracks) {
            track.predict()
            predictedTracks.add(track)
        }
        
        // --- Bước 3: Ghép cặp (match) lần 1: high-confidence detections với các track đang theo dõi ---
        val (matchedTracks, unmatchedTracks, unmatchedDetections) = 
            match(predictedTracks, highConfTracks)

        // --- Bước 4: Ghép cặp (match) lần 2: unmatched tracks còn lại với low-confidence detections ---
        val (refoundTracks, stillUnmatchedTracks, _) =
            match(unmatchedTracks, lowConfTracks)

        // --- Bước 5: Xử lý các track đã mất và các track không khớp ---
        for (track in stillUnmatchedTracks) {
            track.markLost()
            lostTracks.add(track)
        }
        
        // --- Bước 6: Khởi tạo các track mới từ các high-confidence detections không khớp ---
        val newTracks = mutableListOf<STrack>()
        for (detection in unmatchedDetections) {
            if (detection.score >= highConfidenceThreshold) {
                detection.activate(frameId, ++trackIdCounter)
                newTracks.add(detection)
            }
        }

        // --- Bước 7: Cập nhật trạng thái và dọn dẹp ---
        // Thêm các track đã khớp và tìm lại vào danh sách tracked
        trackedTracks = (matchedTracks + refoundTracks + newTracks).toMutableList()
        
        // Xóa các track đã quá hạn khỏi danh sách lost
        lostTracks.retainAll { it.state == TrackState.Lost && (frameId - it.frameId) < trackBuffer }
        removedTracks.addAll(lostTracks.filterNot { it.state == TrackState.Lost && (frameId - it.frameId) < trackBuffer })

        // Trả về các track đang được theo dõi (active)
        return trackedTracks.filter { it.isActivated }
    }

    // Hàm ghép cặp dựa trên IoU
    private fun match(
        tracks: List<STrack>,
        detections: List<STrack>
    ): Triple<List<STrack>, List<STrack>, List<STrack>> {
        if (tracks.isEmpty() || detections.isEmpty()) {
            return Triple(emptyList(), tracks, detections)
        }

        val iouMatrix = STrack.iouDistance(tracks, detections)
        val matchedIndices = mutableListOf<Pair<Int, Int>>()
        val matchedRows = BooleanArray(iouMatrix.size)
        val matchedCols = BooleanArray(iouMatrix[0].size)

        for (i in iouMatrix.indices) {
            var maxVal = -1.0
            var maxIdx = -1
            for (j in iouMatrix[0].indices) {
                // Sửa: ma trận khoảng cách IoU, giá trị càng nhỏ càng tốt. Nhưng code đang tìm maxVal.
                // Ta tìm cặp có IoU lớn nhất, tương đương với khoảng cách (1-IoU) nhỏ nhất.
                // Để đơn giản, ta tính ma trận IoU thay vì khoảng cách.
                if (!matchedCols[j] && iouMatrix[i][j] > maxVal) {
                    maxVal = iouMatrix[i][j]
                    maxIdx = j
                }
            }
            // Sửa: ngưỡng là IoU, không phải khoảng cách.
            if (maxVal >= nmsThreshold) { 
                matchedIndices.add(Pair(i, maxIdx))
                matchedRows[i] = true
                matchedCols[maxIdx] = true
            }
        }

        val matchedTracks = mutableListOf<STrack>()
        val unmatchedTracks = mutableListOf<STrack>()
        val unmatchedDetections = mutableListOf<STrack>()

        for (match in matchedIndices) {
            val track = tracks[match.first]
            val detection = detections[match.second]
            track.update(detection, frameId)
            matchedTracks.add(track)
        }
        
        tracks.forEachIndexed { i, track ->
            if (!matchedRows[i]) unmatchedTracks.add(track)
        }
        
        detections.forEachIndexed { i, detection ->
            if (!matchedCols[i]) unmatchedDetections.add(detection)
        }
        
        return Triple(matchedTracks, unmatchedTracks, unmatchedDetections)
    }
}


// Enum để định nghĩa trạng thái của một track
enum class TrackState {
    New, Tracked, Lost, Removed
}

// Lớp đại diện cho một đối tượng đang được theo dõi
class STrack(
    // RectF được sử dụng để lưu trữ tlwh (top, left, width, height)
    // Cụ thể: tlwh.left = left, tlwh.top = top, tlwh.right = width, tlwh.bottom = height
    var tlwh: RectF,
    val score: Float,
    val label: String
) {
    var isActivated: Boolean = false
    var trackId: Int = 0
    var state: TrackState = TrackState.New

    var frameId: Int = 0
    var startFrame: Int = 0

    companion object {
        // Chuyển đổi từ DetectionResult của ObjectDetector (dạng tlbr) sang STrack (dạng tlwh)
        fun fromDetection(detection: ObjectDetector.DetectionResult): STrack {
            val box = detection.boundingBox
            val tlwh = RectF(box.left, box.top, box.width(), box.height())
            return STrack(tlwh, detection.confidence, detection.label)
        }

        // Tính toán ma trận IoU giữa tracks và detections
        fun iouDistance(tracks: List<STrack>, detections: List<STrack>): Array<DoubleArray> {
            val costMatrix = Array(tracks.size) { DoubleArray(detections.size) }
            for (i in tracks.indices) {
                for (j in detections.indices) {
                    // Sửa: Tính trực tiếp IoU thay vì khoảng cách để logic matching đơn giản hơn
                    costMatrix[i][j] = iou(tracks[i].tlwh, detections[j].tlwh).toDouble()
                }
            }
            return costMatrix
        }

        // Tính IoU giữa hai bounding box (định dạng tlwh)
        private fun iou(rect1: RectF, rect2: RectF): Float {
            // Chuyển đổi tlwh sang tlbr để tính toán giao cắt
            val boxA = RectF(rect1.left, rect1.top, rect1.left + rect1.right, rect1.top + rect1.bottom)
            val boxB = RectF(rect2.left, rect2.top, rect2.left + rect2.right, rect2.top + rect2.bottom)

            val xA = max(boxA.left, boxB.left)
            val yA = max(boxA.top, boxB.top)
            val xB = min(boxA.right, boxB.right)
            val yB = min(boxA.bottom, boxB.bottom)
            val interArea = max(0f, xB - xA) * max(0f, yB - yA)

            // Diện tích là width * height (lưu trong right và bottom của tlwh)
            val boxAArea = rect1.right * rect1.bottom
            val boxBArea = rect2.right * rect2.bottom

            val unionArea = boxAArea + boxBArea - interArea
            return if (unionArea > 0) interArea / unionArea else 0f
        }
    }
    
    // Kích hoạt track lần đầu tiên
    fun activate(frameId: Int, trackId: Int) {
        this.trackId = trackId
        this.frameId = frameId
        this.startFrame = frameId
        this.state = TrackState.Tracked
        this.isActivated = true
    }
    
    // Cập nhật track với một detection mới
    fun update(newTrack: STrack, frameId: Int) {
        this.frameId = frameId
        this.state = TrackState.Tracked
        this.isActivated = true
        this.tlwh = newTrack.tlwh
    }

    fun predict() {
        // Kalman Filter prediction could be implemented here for smoother tracking
        // For now, we assume static position
    }

    fun markLost() {
        this.state = TrackState.Lost
    }

    // Chuyển đổi tlwh sang tlbr (top-left-bottom-right) để vẽ
    fun toTlbr(): RectF {
        return RectF(tlwh.left, tlwh.top, tlwh.left + tlwh.right, tlwh.top + tlwh.bottom)
    }
}