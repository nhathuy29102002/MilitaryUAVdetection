package com.militaryuavdetection

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.common.util.concurrent.ListenableFuture
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.militaryuavdetection.BuildConfig
import com.militaryuavdetection.database.ImageRecord
import com.militaryuavdetection.databinding.ActivityMainBinding
import com.militaryuavdetection.ui.adapter.FileListAdapter
import com.militaryuavdetection.ui.adapter.GridSpacingItemDecoration
import com.militaryuavdetection.utils.ByteTrackManager
import com.militaryuavdetection.utils.ObjectDetector
import com.militaryuavdetection.viewmodel.ImageViewModel
import com.militaryuavdetection.viewmodel.ImageViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class MarkingMode { OFF, MARK, BOX, NAME, CONF, SMART }

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var fileListAdapter: FileListAdapter
    private var currentModel: String? = null
    private var isListPanelExtended = false
    private lateinit var sharedPreferences: SharedPreferences
    private var currentRecord: ImageRecord? = null
    private var markingMode = MarkingMode.OFF
    private var itemDecoration: GridSpacingItemDecoration? = null
    private lateinit var objectDetector: ObjectDetector
    private lateinit var byteTrackManager: ByteTrackManager
    private val gson = Gson()
    private val instanceValues = mutableMapOf<String, Int>()
    private val colorMap = mapOf(
        0 to Color.parseColor("#cfcfcf"),
        1 to Color.parseColor("#7cffff"),
        2 to Color.parseColor("#beff7f"),
        3 to Color.parseColor("#feff7f"),
        4 to Color.parseColor("#ffdf80"),
        5 to Color.parseColor("#ffbf7f"),
        6 to Color.parseColor("#ffbf7f"),
        7 to Color.parseColor("#ed3c4d"),
        8 to Color.parseColor("#fe7ef7")
    )
    private var latestTmpUri: Uri? = null

    // --- Video Related ---
    private var videoDetections = mapOf<Long, List<ObjectDetector.DetectionResult>>()
    private val syncHandler = Handler(Looper.getMainLooper())
    private var syncRunnable: Runnable? = null
    // ---------------------

    // --- Real-time Detection ---
    private var isRealTimeDetectionActive = false
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var frameCounter = 0
    private var lastDetections: List<ObjectDetector.DetectionResult> = emptyList()
    // --------------------------

    private val imageViewModel: ImageViewModel by viewModels {
        ImageViewModelFactory((application as MilitaryUavApplication).database.imageRecordDao())
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                if (isRealTimeDetectionActive) {
                    startRealTimeDetection()
                } else {
                    launchCamera()
                }
            } else {
                Toast.makeText(this, "Camera permission denied.", Toast.LENGTH_SHORT).show()
            }
        }

    private val captureMediaLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            latestTmpUri?.let { uri ->
                val contentResolver = applicationContext.contentResolver
                try {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        if (inputStream.available() > 0) {
                            insertUrisAndAnalyze(listOf(uri))
                        } else {
                            Toast.makeText(this, "Failed to get media data.", Toast.LENGTH_SHORT).show()
                        }
                    } ?: Toast.makeText(this, "Failed to get media data.", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error accessing media file.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    private val openFilesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = result.data?.clipData?.let { clipData ->
                (0 until clipData.itemCount).map { clipData.getItemAt(it).uri }
            } ?: result.data?.data?.let { listOf(it) } ?: emptyList()

            if (uris.isNotEmpty()) {
                insertUrisAndAnalyze(uris)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences("MilitaryUavPrefs", Context.MODE_PRIVATE)
        objectDetector = ObjectDetector(this)
        byteTrackManager = ByteTrackManager()

        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
        loadInitialData()
        loadInstanceValues()
    }

    override fun onPause() {
        super.onPause()
        stopVideoPlaybackSync()
        binding.videoView.pause()
        binding.playPauseButton.setImageResource(android.R.drawable.ic_media_play)
        if(isRealTimeDetectionActive) {
            stopRealTimeDetection()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }


    private fun loadInstanceValues() {
        try {
            assets.open("models/instance_values.txt").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).forEachLine { line ->
                    val parts = line.split(" ")
                    if (parts.size == 2) {
                        instanceValues[parts[0]] = parts[1].toInt()
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun loadInitialData() {
        lifecycleScope.launch {
            imageViewModel.clearAll()
            currentModel = sharedPreferences.getString("selected_model", null)
            currentModel?.let {
                objectDetector.loadModel(it)
                binding.browseModelButton.setImageResource(R.drawable.importmodel_check)
            }
            loadSampleImages()
        }
    }

    private fun setupRecyclerView() {
        fileListAdapter = FileListAdapter(emptyList(), emptyList())
        binding.recyclerView.adapter = fileListAdapter
        setViewMode(FileListAdapter.ViewMode.ICON)

        fileListAdapter.onItemClick = { record ->
            selectRecord(record)
        }
    }

    private fun selectRecord(record: ImageRecord) {
        if(isRealTimeDetectionActive) stopRealTimeDetection()
        currentRecord = record
        fileListAdapter.updateSelection(record)
        stopVideoPlaybackSync()

        try {
            binding.imageName.text = record.name
            binding.imageSize.text = "${record.width}x${record.height}"

            if (record.mediaType == "VIDEO") {
                binding.videoView.visibility = View.VISIBLE
                binding.imageView.visibility = View.VISIBLE // Overlay
                binding.playPauseButton.visibility = View.VISIBLE
                binding.fitImageButton.visibility = View.GONE
                binding.imageView.setBackgroundColor(Color.TRANSPARENT)

                if (record.width > 0 && record.height > 0) {
                    val placeholderBitmap = Bitmap.createBitmap(record.width, record.height, Bitmap.Config.ARGB_8888)
                    val placeholderDrawable = BitmapDrawable(resources, placeholderBitmap)
                    binding.imageView.setImageDrawable(placeholderDrawable)
                } else {
                    binding.imageView.setImageDrawable(null)
                }

                binding.videoView.setVideoURI(record.uri.toUri())
                binding.videoView.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    binding.imageView.fitImage()
                }

                val type = object : TypeToken<Map<Long, List<ObjectDetector.DetectionResult>>>() {}.type
                videoDetections = record.boundingBoxes?.let { gson.fromJson(it, type) } ?: emptyMap()

            } else { // IMAGE
                binding.videoView.visibility = View.GONE
                binding.playPauseButton.visibility = View.GONE
                binding.imageView.visibility = View.VISIBLE
                binding.imageView.setBackgroundColor(Color.TRANSPARENT)
                binding.imageView.setImageURI(record.uri.toUri())
                val type = object : TypeToken<List<ObjectDetector.DetectionResult>>() {}.type
                val detections: List<ObjectDetector.DetectionResult> = record.boundingBoxes?.let {
                    gson.fromJson(it, type)
                } ?: emptyList()
                binding.imageView.setDetections(detections, markingMode, instanceValues, colorMap, record.width, record.height)
                binding.fitImageButton.visibility = View.GONE
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Could not load media: ${record.name}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }


    private fun observeViewModel() {
        lifecycleScope.launch {
            imageViewModel.allRecords.collect { records ->
                fileListAdapter.updateData(records)
                binding.clearAllButton.isVisible = records.isNotEmpty()
                if (currentRecord != null && records.none { it.id == currentRecord!!.id }) {
                    clearSelection()
                }
                if (currentRecord == null && records.isNotEmpty()) {
                    records.maxByOrNull { it.id }?.let { selectRecord(it) }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupClickListeners() {
        binding.browseImageButton.setOnClickListener { openMediaPicker() }
        binding.browseModelButton.setOnClickListener { openModelPicker() }
        binding.markTypeButton.setOnClickListener { cycleMarkingMode() }
        binding.exportButton.setOnClickListener { exportImage() }

        binding.cameraButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressHandler.postDelayed({
                        toggleRealTimeDetection()
                    }, 2000) // 2 seconds
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacksAndMessages(null)
                }
            }
            // Also handle click for normal camera operation
            if (event.action == MotionEvent.ACTION_UP) {
                if (!isRealTimeDetectionActive) {
                    takeMedia()
                }
            }
            true
        }

        binding.playPauseButton.setOnClickListener {
            if (binding.videoView.isPlaying) {
                binding.videoView.pause()
                stopVideoPlaybackSync()
                binding.playPauseButton.setImageResource(android.R.drawable.ic_media_play)
            } else {
                binding.videoView.start()
                startVideoPlaybackSync()
                binding.playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
            }
        }

        binding.fitImageButton.setOnClickListener {
            binding.imageView.fitImage()
            binding.fitImageButton.visibility = View.GONE
        }

        binding.imageView.onTransformChanged = {
            binding.fitImageButton.visibility = View.VISIBLE
        }

        binding.clearAllButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear All")
                .setMessage("Are you sure you want to delete all items?")
                .setPositiveButton("Yes") { _, _ ->
                    imageViewModel.clearAll()
                }
                .setNegativeButton("No", null)
                .show()
        }

        binding.viewModeIcon.setOnClickListener { setViewMode(FileListAdapter.ViewMode.ICON) }
        binding.viewModeDetail.setOnClickListener { setViewMode(FileListAdapter.ViewMode.DETAIL) }
        binding.viewModeContent.setOnClickListener { setViewMode(FileListAdapter.ViewMode.CONTENT) }

        binding.extendListPanelButton.setOnClickListener { toggleListPanelExtension() }
        binding.searchIcon.setOnClickListener {
            if (binding.searchEditText.visibility == View.VISIBLE) {
                hideSearch()
            } else {
                showSearch()
            }
        }

        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { fileListAdapter.filter(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                hideSearch()
                true
            } else {
                false
            }
        }
    }

    private fun startVideoPlaybackSync() {
        if (syncRunnable != null) return

        syncRunnable = object : Runnable {
            override fun run() {
                if (binding.videoView.isPlaying) {
                    val currentTimeMs = binding.videoView.currentPosition.toLong()
                    val closestTimestamp = videoDetections.keys.minByOrNull { abs(it - currentTimeMs) }

                    closestTimestamp?.let {
                        val detections = videoDetections[it] ?: emptyList()
                        binding.imageView.setDetections(detections, markingMode, instanceValues, colorMap, currentRecord?.width ?: 0, currentRecord?.height ?: 0)
                    }
                    syncHandler.postDelayed(this, 40) // ~25 FPS
                }
            }
        }
        syncHandler.post(syncRunnable!!)
    }

    private fun stopVideoPlaybackSync() {
        syncRunnable?.let { syncHandler.removeCallbacks(it) }
        syncRunnable = null
        binding.imageView.setDetections(emptyList(), markingMode, instanceValues, colorMap, 0, 0)
    }

    private fun cycleMarkingMode() {
        markingMode = MarkingMode.values()[(markingMode.ordinal + 1) % MarkingMode.values().size]
        binding.markTypeButton.text = markingMode.name
        binding.imageView.updateMarkingMode(markingMode)
    }

    private fun toggleListPanelExtension() {
        isListPanelExtended = !isListPanelExtended
        if (isListPanelExtended) {
            binding.imagePanel.visibility = View.GONE
            binding.taskbar.visibility = View.GONE
            binding.viewModeButtons.visibility = View.GONE
            binding.detailBar.visibility = View.VISIBLE
            binding.extendListPanelButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        } else {
            binding.imagePanel.visibility = View.VISIBLE
            binding.taskbar.visibility = View.VISIBLE
            binding.viewModeButtons.visibility = View.VISIBLE
            binding.extendListPanelButton.setImageResource(R.drawable.screenshot)
            hideSearch()
        }
    }

    private fun showSearch() {
        binding.imageName.visibility = View.GONE
        binding.imageSize.visibility = View.GONE
        binding.searchEditText.visibility = View.VISIBLE
        binding.searchEditText.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSearch() {
        binding.searchEditText.visibility = View.GONE
        binding.imageName.visibility = View.VISIBLE
        binding.imageSize.visibility = View.VISIBLE
        binding.searchEditText.text.clear()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
    }

    private fun takeMedia() {
        if (currentModel == null) {
            Toast.makeText(this, "Please select a model first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(this, "No camera available.", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val options = arrayOf("Take Photo", "Record Video")
        AlertDialog.Builder(this)
            .setTitle("Choose an action")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchImageCamera()
                    1 -> launchVideoCamera()
                }
            }
            .show()
    }

    private fun launchImageCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        latestTmpUri = getTmpFileUri(".jpg")
        intent.putExtra(MediaStore.EXTRA_OUTPUT, latestTmpUri)
        if (intent.resolveActivity(packageManager) != null) {
            captureMediaLauncher.launch(intent)
        } else {
            Toast.makeText(this, "No app to handle image capture.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchVideoCamera() {
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        latestTmpUri = getTmpFileUri(".mp4")
        intent.putExtra(MediaStore.EXTRA_OUTPUT, latestTmpUri)
        if (intent.resolveActivity(packageManager) != null) {
            captureMediaLauncher.launch(intent)
        } else {
            Toast.makeText(this, "No app to handle video recording.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getTmpFileUri(extension: String = ".png"): Uri {
        val tmpFile = File.createTempFile("tmp_media_file", extension, cacheDir).apply {
            createNewFile()
            deleteOnExit()
        }
        return FileProvider.getUriForFile(applicationContext, "${BuildConfig.APPLICATION_ID}.provider", tmpFile)
    }

    private fun openMediaPicker() {
        if (currentModel == null) {
            Toast.makeText(this, "Please select a model first.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        openFilesLauncher.launch(intent)
    }


    private fun openModelPicker() {
        try {
            val models = assets.list("models")?.filter { it.endsWith(".onnx") }?.toTypedArray()
            if (models.isNullOrEmpty()) {
                Toast.makeText(this, "No models found in assets.", Toast.LENGTH_SHORT).show()
                return
            }

            AlertDialog.Builder(this)
                .setTitle("Select a model")
                .setItems(models) { _, which ->
                    val selectedModelName = models[which]
                    val modelPath = "models/$selectedModelName"
                    lifecycleScope.launch {
                        objectDetector.loadModel(modelPath)
                        currentModel = modelPath
                        sharedPreferences.edit().putString("selected_model", currentModel).apply()
                        binding.browseModelButton.setImageResource(R.drawable.importmodel_check)
                        Toast.makeText(this@MainActivity, "$selectedModelName selected", Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Error listing models.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportImage() {
        if (currentRecord == null || currentRecord!!.mediaType != "IMAGE") {
            Toast.makeText(this, "No image selected to export", Toast.LENGTH_SHORT).show()
            return
        }

        val bitmapToExport = binding.imageView.createExportBitmap()
        if (bitmapToExport == null) {
            Toast.makeText(this, "Could not create image for export", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val fileName = "Exported_${currentRecord?.name?.substringBeforeLast(".")}.png"
            val resolver = contentResolver

            try {
                val fos: OutputStream?
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + "Export")
                    }
                    val imageUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    fos = imageUri?.let { resolver.openOutputStream(it) }
                } else {
                    @Suppress("DEPRECATION")
                    val exportDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Export")
                    if (!exportDir.exists()) exportDir.mkdirs()
                    val imageFile = File(exportDir, fileName)
                    fos = FileOutputStream(imageFile)
                }

                fos?.use { stream ->
                    bitmapToExport.compress(Bitmap.CompressFormat.PNG, 100, stream)
                } ?: throw IOException("Failed to get output stream.")


                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Image saved to Downloads/Export", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to save image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun clearSelection() {
        stopVideoPlaybackSync()
        binding.videoView.stopPlayback()
        binding.videoView.visibility = View.GONE
        binding.playPauseButton.visibility = View.GONE
        binding.imageView.visibility = View.VISIBLE
        binding.imageView.setImageDrawable(null)

        currentRecord = null
        fileListAdapter.updateSelection(null)
        binding.imageView.setDetections(emptyList(), markingMode, instanceValues, colorMap, 0, 0)
        binding.imageName.text = "Image Name"
        binding.imageSize.text = "Size"
        binding.fitImageButton.visibility = View.GONE
    }

    private fun insertUrisAndAnalyze(uris: List<Uri>) {
        lifecycleScope.launch {
            uris.forEach { uri ->
                imageViewModel.getRecordByUri(uri.toString())?.let { imageViewModel.delete(it) }
            }

            val recordsToInsert = uris.mapNotNull { uri ->
                try {
                    val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return@mapNotNull null
                    val mimeType = contentResolver.getType(uri)

                    val recordWidth: Int
                    val recordHeight: Int
                    val mediaType: String
                    val bboxesJson: String

                    if (mimeType?.startsWith("video/") == true) {
                        mediaType = "VIDEO"
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(this@MainActivity, uri)
                        recordWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                        recordHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0

                        val detectionsMap = processVideo(uri)
                        bboxesJson = gson.toJson(detectionsMap)
                        retriever.release()
                    } else {
                        mediaType = "IMAGE"
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
                        recordWidth = options.outWidth
                        recordHeight = options.outHeight
                        val detections = if (currentModel != null) objectDetector.analyzeImage(uri) else emptyList()
                        bboxesJson = gson.toJson(detections)
                    }
                    pfd.close()

                    val documentFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(this@MainActivity, uri)!!
                    ImageRecord(
                        uri = uri.toString(),
                        name = documentFile.name ?: "Unknown",
                        dateModified = documentFile.lastModified(),
                        size = documentFile.length(),
                        mediaType = mediaType,
                        width = recordWidth,
                        height = recordHeight,
                        boundingBoxes = bboxesJson
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to process: ${uri.path}", Toast.LENGTH_SHORT).show()
                    }
                    null
                }
            }
            if (recordsToInsert.isNotEmpty()) {
                imageViewModel.insertAll(recordsToInsert)
                val lastUriToSelect = uris.last()
                imageViewModel.getRecordByUri(lastUriToSelect.toString())?.let { record ->
                    selectRecord(record)
                }
            }
        }
    }
    
    private fun calculateIOU(box1: RectF, box2: RectF): Float {
        val xA = max(box1.left, box2.left)
        val yA = max(box1.top, box2.top)
        val xB = min(box1.right, box2.right)
        val yB = min(box1.bottom, box2.bottom)

        val interArea = max(0f, xB - xA) * max(0f, yB - yA)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val iou = interArea / (box1Area + box2Area - interArea)
        return iou
    }


    private suspend fun processVideo(uri: Uri): Map<Long, List<ObjectDetector.DetectionResult>> = withContext(Dispatchers.IO) {
        val detectionsMap = mutableMapOf<Long, List<ObjectDetector.DetectionResult>>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this@MainActivity, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLong() ?: 0
            val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 1
            val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 1

            val intervalMs = 200L
            var currentTimeMs = 0L

            while (currentTimeMs < durationMs) {
                val bitmap = retriever.getFrameAtTime(currentTimeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bitmap != null) {
                    val detections = objectDetector.analyzeBitmap(bitmap, videoWidth, videoHeight)
                    detectionsMap[currentTimeMs] = detections
                    bitmap.recycle()
                }
                currentTimeMs += intervalMs
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            retriever.release()
        }

        val interpolatedDetectionsMap = mutableMapOf<Long, List<ObjectDetector.DetectionResult>>()
        val sortedTimestamps = detectionsMap.keys.sorted()

        for (i in 0 until sortedTimestamps.size - 1) {
            val ts1 = sortedTimestamps[i]
            val ts2 = sortedTimestamps[i+1]
            val detections1 = detectionsMap[ts1] ?: continue
            val detections2 = detectionsMap[ts2] ?: continue

            val interpolatedTs = (ts1 + ts2) / 2
            val newDetections = mutableListOf<ObjectDetector.DetectionResult>()

            detections1.forEach { d1 ->
                detections2.forEach { d2 ->
                    val conf1 = d1.confidence
                    val conf2 = d2.confidence
                    val iou = calculateIOU(d1.boundingBox, d2.boundingBox)
                    
                    if (d1.label == d2.label && abs(conf2 - conf1) <= 0.2 && (conf1 > 0.6 || conf2 > 0.6) && iou > 0.7) {
                        val newBox = RectF(
                            (d1.boundingBox.left + d2.boundingBox.left) / 2,
                            (d1.boundingBox.top + d2.boundingBox.top) / 2,
                            (d1.boundingBox.right + d2.boundingBox.right) / 2,
                            (d1.boundingBox.bottom + d2.boundingBox.bottom) / 2
                        )
                        val newConf = (conf1 + conf2) / 2
                        newDetections.add(ObjectDetector.DetectionResult(newBox, d1.label, newConf))
                    }
                }
            }
            if(newDetections.isNotEmpty()){
                 interpolatedDetectionsMap[interpolatedTs] = newDetections
            }
        }

        val finalDetections = (detectionsMap.asSequence() + interpolatedDetectionsMap.asSequence())
            .distinct()
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, values) -> values.flatten() }

        return@withContext finalDetections
    }


    private suspend fun loadSampleImages() = withContext(Dispatchers.IO) {
        val importDir = "import"
        val sampleImageNames = assets.list(importDir)?.filter { it.endsWith(".jpg") || it.endsWith(".png") } ?: return@withContext

        val tempFileUris = sampleImageNames.mapNotNull { fileName ->
            try {
                val tempFile = File(cacheDir, fileName)
                assets.open("$importDir/$fileName").use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                FileProvider.getUriForFile(this@MainActivity, "${BuildConfig.APPLICATION_ID}.provider", tempFile)
            } catch (e: IOException) {
                e.printStackTrace()
                null
            }
        }
        if (tempFileUris.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                insertUrisAndAnalyze(tempFileUris)
            }
        }
    }

    private fun setViewMode(viewMode: FileListAdapter.ViewMode) {
        fileListAdapter.setViewMode(viewMode)
        itemDecoration?.let { binding.recyclerView.removeItemDecoration(it) }
        when (viewMode) {
            FileListAdapter.ViewMode.ICON -> {
                val spanCount = 4
                val spacing = 8
                val includeEdge = true
                itemDecoration = GridSpacingItemDecoration(spanCount, spacing, includeEdge)
                binding.recyclerView.addItemDecoration(itemDecoration!!)
                binding.recyclerView.layoutManager = GridLayoutManager(this, spanCount)
            }
            FileListAdapter.ViewMode.DETAIL, FileListAdapter.ViewMode.CONTENT -> {
                binding.recyclerView.layoutManager = LinearLayoutManager(this)
            }
        }
    }

    // --- Real-time Detection Methods ---

    private fun toggleRealTimeDetection() {
        if (isRealTimeDetectionActive) {
            stopRealTimeDetection()
        } else {
            if (currentModel == null) {
                Toast.makeText(this, "Please select a model first.", Toast.LENGTH_SHORT).show()
                return
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startRealTimeDetection()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startRealTimeDetection() {
        isRealTimeDetectionActive = true
        Toast.makeText(this, "Real-time detection started.", Toast.LENGTH_SHORT).show()
        
        // Reset tracker
        byteTrackManager = ByteTrackManager()

        // Prepare UI for real-time feed
        clearSelection()
        binding.previewView.visibility = View.VISIBLE
        binding.videoView.visibility = View.GONE
        binding.imageView.setBackgroundColor(Color.TRANSPARENT)
        binding.imageView.setImageDrawable(null)

        startCamera()
    }

    private fun stopRealTimeDetection() {
        isRealTimeDetectionActive = false
        cameraProviderFuture.get().unbindAll()
        binding.previewView.visibility = View.GONE
        val emptyDetections = emptyList<ObjectDetector.DetectionResult>()
        binding.imageView.setDetections(emptyDetections, markingMode, instanceValues, colorMap, 0, 0)
        Toast.makeText(this, "Real-time detection stopped.", Toast.LENGTH_SHORT).show()
    }

    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        val preview = Preview.Builder()
            .setTargetResolution(Size(640, 480)) // Sử dụng API mới
            .build()
            .also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480)) // Sử dụng API mới
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                if (rotationDegrees == 0 || rotationDegrees == 180) {
                    postScale(
                        binding.previewView.width.toFloat() / imageProxy.width,
                        binding.previewView.height.toFloat() / imageProxy.height
                    )
                } else {
                    postScale(
                        binding.previewView.width.toFloat() / imageProxy.height,
                        binding.previewView.height.toFloat() / imageProxy.width
                    )
                }
                when (rotationDegrees) {
                    90 -> postTranslate(binding.previewView.width.toFloat(), 0f)
                    270 -> postTranslate(0f, binding.previewView.height.toFloat())
                }
            }

            frameCounter++
            // Giảm tần suất xử lý để tối ưu hiệu suất
            if (frameCounter % 3 == 0) {
                val bitmap = imageProxy.toBitmap()
                if (bitmap != null) {
                    runBlocking {
                        val results = objectDetector.analyzeBitmap(bitmap, imageProxy.width, imageProxy.height)
                        
                        // --- TÍCH HỢP BYTETRACK ---
                        val trackedObjects = byteTrackManager.update(results)
                        val trackedDetections = trackedObjects.map { track ->
                            ObjectDetector.DetectionResult(
                                boundingBox = track.toTlbr(),
                                label = track.label,
                                confidence = track.score,
                                trackId = track.trackId
                            )
                        }
                        // -------------------------

                        lastDetections = trackedDetections
                        withContext(Dispatchers.Main) {
                            binding.imageView.setDetectionsWithTransform(trackedDetections, imageProxy.width, imageProxy.height, matrix)
                        }
                    }
                    bitmap.recycle()
                }
            } else {
                 runOnUiThread {
                    binding.imageView.setDetectionsWithTransform(lastDetections, imageProxy.width, imageProxy.height, matrix)
                 }
            }
            imageProxy.close()
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
        } catch (exc: Exception) {
            Log.e("MainActivity", "Use case binding failed", exc)
        }
    }

    private fun filterDetections(detections: List<ObjectDetector.DetectionResult>): List<ObjectDetector.DetectionResult> {
        val screenBox = RectF(0f, 0f, 1f, 1f)
        return detections.filterNot {
            val iou = calculateIOU(it.boundingBox, screenBox)
            iou >= 0.9 && it.confidence < 0.4
        }
    }

    private fun ImageProxy.toBitmap(): Bitmap? {
        val yBuffer = planes[0].buffer // Y
        val uBuffer = planes[1].buffer // U
        val vBuffer = planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
}