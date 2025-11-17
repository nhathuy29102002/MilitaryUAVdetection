package com.militaryuavdetection.viewmodel

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
import com.militaryuavdetection.data.DetectionResult
import com.militaryuavdetection.data.FileItem
import com.militaryuavdetection.objectdetector.ObjectDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("UAV_DETECTOR_PREFS", Context.MODE_PRIVATE)

    private val _isModelLoaded = MutableLiveData(false)
    val isModelLoaded: LiveData<Boolean> = _isModelLoaded

    private var objectDetector: ObjectDetector? = null

    private val _markType = MutableLiveData(3)
    val markType: LiveData<Int> = _markType

    private val _fileList = MutableLiveData<List<FileItem>>(emptyList())
    val fileList: LiveData<List<FileItem>> = _fileList

    private val _selectedFile = MutableLiveData<FileItem?>(null)
    val selectedFile: LiveData<FileItem?> = _selectedFile

    private val _realtimeResults = MutableLiveData<Pair<List<DetectionResult>, List<String>>>()
    val realtimeResults: LiveData<Pair<List<DetectionResult>, List<String>>> = _realtimeResults

    init {
        initializeModel()
    }

    private fun initializeModel() {
        if (_isModelLoaded.value == true) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val modelName = "yolov8n.onnx"

                objectDetector = ObjectDetector(getApplication(), modelName)
                objectDetector?.initialize()
                _isModelLoaded.postValue(true)
                Log.d("ViewModel", "Model $modelName initialized.")
            } catch (e: Exception) {
                _isModelLoaded.postValue(false)
                Log.e("ViewModel", "Model initialization failed", e)
            }
        }
    }

    fun cycleMarkType() {
        _markType.value = (_markType.value!! + 1) % 4
    }

    fun onFileSelected(fileItem: FileItem) {
        _selectedFile.value = fileItem
    }

    fun detectInRealtime(bitmap: Bitmap) {
        if (_isModelLoaded.value != true) return
        viewModelScope.launch(Dispatchers.IO) {
            val results = objectDetector?.detect(bitmap)
            results?.let {
                _realtimeResults.postValue(Pair(it, ObjectDetector.LABELS))
            }
        }
    }

    fun loadDirectory(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            val fileItems = mutableListOf<FileItem>()
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)

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
                            FileItem(id = docId, name = name, uri = fileUri, isVideo = mimeType.startsWith("video/"), date = date, size = size)
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