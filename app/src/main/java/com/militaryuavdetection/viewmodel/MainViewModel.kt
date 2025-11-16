package com.example.militaryuavdetection

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

// ... (Data class FileItem giữ nguyên) ...
data class FileItem(
    val id: String,
    val name: String,
    val uri: Uri,
    val isVideo: Boolean,
    val date: Long,
    val size: Long
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("UAV_DETECTOR_PREFS", Context.MODE_PRIVATE)

    // --- Trạng thái Model ---
    private val _isModelLoaded = MutableLiveData(false)
    val isModelLoaded: LiveData<Boolean> = _isModelLoaded

    // (THAY ĐỔI) Khởi tạo ObjectDetector
    private var objectDetector: ObjectDetector? = null

    // --- Trạng thái MarkType ---
    private val _markType = MutableLiveData(3) // 0: None, 1: Box, 2: Box+Class, 3: Box+Class+Conf
    val markType: LiveData<Int> = _markType

    // --- Trạng thái Danh sách File ---
    private val _fileList = MutableLiveData<List<FileItem>>(emptyList())
    val fileList: LiveData<List<FileItem>> = _fileList

    // --- Trạng thái File/Video đang chọn ---
    private val _selectedFile = MutableLiveData<FileItem?>(null)
    val selectedFile: LiveData<FileItem?> = _selectedFile

    // --- Trạng thái Kết quả Realtime ---
    private val _realtimeResults = MutableLiveData<List<DetectionResult>>()
    val realtimeResults: LiveData<List<DetectionResult>> = _realtimeResults

    // (THAY ĐỔI) Tự động khởi tạo model khi ViewModel được tạo
    init {
        initializeModel()
    }

    // (MỚI) Khởi tạo model từ assets
    private fun initializeModel() {
        if (_isModelLoaded.value == true) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // (THAY ĐỔI Ở ĐÂY) Thay "yolov8n.onnx" bằng tên file .onnx của bạn
                val modelName = "yolov8n.onnx"

                objectDetector = ObjectDetector(getApplication(), modelName)
                objectDetector?.initialize()
                _isModelLoaded.postValue(true)
                Log.d("ViewModel", "Model $modelName đã được khởi tạo.")
            } catch (e: Exception) {
                _isModelLoaded.postValue(false)
                Log.e("ViewModel", "Khởi tạo model thất bại", e)
            }
        }
    }

    // (THAY ĐỔI) Xóa hàm loadModel(uri) và loadSavedModelPath()

    fun cycleMarkType() {
        val currentType = _markType.value ?: 0
        _markType.value = (currentType + 1) % 4
    }

    // (MỚI) Xử lý khi 1 file được chọn
    fun onFileSelected(fileItem: FileItem) {
        _selectedFile.value = fileItem
        // TODO: Nếu là ảnh, chạy detect 1 lần
        // if (!fileItem.isVideo) {
        //     viewModelScope.launch(Dispatchers.IO) {
        //         val bitmap = ... (Lấy bitmap từ fileItem.uri)
        //         val results = objectDetector?.detect(bitmap)
        //         _realtimeResults.postValue(results) // Dùng chung LiveData
        //     }
        // }
    }

    // (MỚI) Xử lý detect realtime
    fun detectInRealtime(bitmap: Bitmap) {
        if (_isModelLoaded.value != true) return

        viewModelScope.launch(Dispatchers.IO) {
            val results = objectDetector?.detect(bitmap)
            results?.let {
                _realtimeResults.postValue(it)
            }
        }
    }


    fun loadDirectory(treeUri: Uri) {
        // ... (Hàm này giữ nguyên như cũ) ...
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            val fileItems = mutableListOf<FileItem>()

            // Sử dụng DocumentTree để duyệt thư mục (cách chuẩn)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )

            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val name = cursor.getString(1)
                    val mimeType = cursor.getString(2)
                    val date = cursor.getLong(3)
                    val size = cursor.getLong(4)

                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                    if ((mimeType.startsWith("image/") || mimeType.startsWith("video/")) && !name.startsWith(".")) {
                        fileItems.add(
                            FileItem(
                                id = docId,
                                name = name,
                                uri = fileUri,
                                isVideo = mimeType.startsWith("video/"),
                                date = date,
                                size = size
                            )
                        )
                    }
                }
            }
            _fileList.postValue(fileItems)
        }
    }

    override fun onCleared() {
        super.onCleared()
        objectDetector?.close()
    }
}