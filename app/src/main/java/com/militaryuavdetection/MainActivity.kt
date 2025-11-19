package com.militaryuavdetection

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.militaryuavdetection.BuildConfig
import com.militaryuavdetection.database.ImageRecord
import com.militaryuavdetection.databinding.ActivityMainBinding
import com.militaryuavdetection.ui.adapter.FileListAdapter
import com.militaryuavdetection.ui.adapter.GridSpacingItemDecoration
import com.militaryuavdetection.utils.ObjectDetector
import com.militaryuavdetection.viewmodel.ImageViewModel
import com.militaryuavdetection.viewmodel.ImageViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream

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

    private val imageViewModel: ImageViewModel by viewModels {
        ImageViewModelFactory((application as MilitaryUavApplication).database.imageRecordDao())
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                launchCamera()
            } else {
                Toast.makeText(this, "Camera permission denied.", Toast.LENGTH_SHORT).show()
            }
        }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            latestTmpUri?.let {
                insertUrisAndAnalyze(listOf(it))
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

        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
        loadInitialData()
        loadInstanceValues()
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
        currentRecord = record
        fileListAdapter.updateSelection(record)
        try {
            binding.imagePanel.setImageURI(record.uri.toUri())
            binding.imageName.text = record.name
            binding.imageSize.text = "${record.width}x${record.height}"

            val type = object : TypeToken<List<ObjectDetector.DetectionResult>>() {}.type
            val detections: List<ObjectDetector.DetectionResult> = record.boundingBoxes?.let {
                gson.fromJson(it, type)
            } ?: emptyList()
            binding.imagePanel.setDetections(detections, markingMode, instanceValues, colorMap)
            binding.fitImageButton.visibility = View.GONE

        } catch (e: Exception) {
            Toast.makeText(this, "Could not load image: ${record.name}", Toast.LENGTH_SHORT).show()
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
                // Auto-select the last imported record if nothing is selected
                if (currentRecord == null && records.isNotEmpty()) {
                    records.maxByOrNull { it.id }?.let { selectRecord(it) }
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.browseImageButton.setOnClickListener { openImagePicker() }
        binding.browseModelButton.setOnClickListener { openModelPicker() }
        binding.markTypeButton.setOnClickListener { cycleMarkingMode() }
        binding.cameraButton.setOnClickListener { takePicture() }
        binding.exportButton.setOnClickListener { exportImage() }

        binding.fitImageButton.setOnClickListener {
            binding.imagePanel.fitImage()
            binding.fitImageButton.visibility = View.GONE
        }

        binding.imagePanel.onTransformChanged = {
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

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                fileListAdapter.filter(s.toString())
            }

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

    private fun cycleMarkingMode() {
        markingMode = MarkingMode.values()[(markingMode.ordinal + 1) % MarkingMode.values().size]
        binding.markTypeButton.text = markingMode.name
        binding.imagePanel.updateMarkingMode(markingMode)
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

    private fun showCameraOptions() { /* ... */ }
    
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

    private fun takePicture() {
        if (currentModel == null) {
            Toast.makeText(this, "Please select a model first to process the image.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(this, "No camera available on this device.", Toast.LENGTH_SHORT).show()
            return
        }

        when (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)) {
            PackageManager.PERMISSION_GRANTED -> {
                launchCamera()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun launchCamera() {
        lifecycleScope.launch {
            getTmpFileUri().let {
                latestTmpUri = it
                takePictureLauncher.launch(it)
            }
        }
    }

    private fun getTmpFileUri(): Uri {
        val tmpFile = File.createTempFile("tmp_image_file", ".png", cacheDir).apply {
            createNewFile()
            deleteOnExit()
        }

        return FileProvider.getUriForFile(applicationContext, "${BuildConfig.APPLICATION_ID}.provider", tmpFile)
    }

    private fun openImagePicker() {
        if (currentModel == null) {
            Toast.makeText(this, "Please select a model first to process the image.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
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
        if (currentRecord == null) {
            Toast.makeText(this, "No image selected to export", Toast.LENGTH_SHORT).show()
            return
        }

        val bitmapToExport = binding.imagePanel.createExportBitmap()
        if (bitmapToExport == null) {
            Toast.makeText(this, "Could not create image for export", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val fileName = "Exported_${currentRecord?.name?.substringBeforeLast(".")}.png"
            val resolver = contentResolver

            try {
                val fos: OutputStream
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + "Export")
                    }
                    val imageUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        ?: throw IOException("Failed to create new MediaStore entry.")
                    fos = resolver.openOutputStream(imageUri)
                        ?: throw IOException("Failed to get output stream.")
                } else {
                    @Suppress("DEPRECATION")
                    val exportDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Export")
                    if (!exportDir.exists()) {
                        exportDir.mkdirs()
                    }
                    val imageFile = File(exportDir, fileName)
                    fos = FileOutputStream(imageFile)
                }

                fos.use { stream ->
                    if (!bitmapToExport.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw IOException("Failed to save bitmap.")
                    }
                }

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
        currentRecord = null
        fileListAdapter.updateSelection(null)
        binding.imagePanel.setImageDrawable(null)
        binding.imagePanel.setDetections(emptyList(), markingMode, instanceValues, colorMap)
        binding.imageName.text = "Image Name"
        binding.imageSize.text = "Size"
        binding.fitImageButton.visibility = View.GONE
    }

    private fun insertUrisAndAnalyze(uris: List<Uri>) {
        lifecycleScope.launch {
            val recordsToInsert = uris.mapNotNull { uri ->
                try {
                    imageViewModel.getRecordByUri(uri.toString())?.let { imageViewModel.delete(it) }

                    val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return@mapNotNull null
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
                    pfd.close()

                    val mediaType = if (options.outMimeType?.startsWith("image/") == true) "IMAGE" else "VIDEO"
                    val documentFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(this@MainActivity, uri)!!

                    val detections = if (mediaType == "IMAGE" && currentModel != null) objectDetector.analyzeImage(uri) else emptyList()
                    val bboxesJson = gson.toJson(detections)

                    ImageRecord(
                        uri = uri.toString(),
                        name = documentFile.name ?: "Unknown",
                        dateModified = documentFile.lastModified(),
                        size = documentFile.length(),
                        mediaType = mediaType,
                        width = options.outWidth,
                        height = options.outHeight,
                        boundingBoxes = bboxesJson
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            if (recordsToInsert.isNotEmpty()) {
                imageViewModel.insertAll(recordsToInsert)

                // Immediately select the last added image to display.
                val lastUriToSelect = uris.last()
                imageViewModel.getRecordByUri(lastUriToSelect.toString())?.let { record ->
                    selectRecord(record)
                }
            }
        }
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
                // Use FileProvider to get a content URI, which is required for sharing files safely.
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
}
