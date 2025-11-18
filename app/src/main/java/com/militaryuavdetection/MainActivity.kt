package com.militaryuavdetection

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.militaryuavdetection.database.ImageRecord
import com.militaryuavdetection.databinding.ActivityMainBinding
import com.militaryuavdetection.ui.adapter.FileListAdapter
import com.militaryuavdetection.ui.adapter.GridSpacingItemDecoration
import com.militaryuavdetection.ui.view.ZoomableImageView
import com.militaryuavdetection.utils.ObjectDetector
import com.militaryuavdetection.viewmodel.ImageViewModel
import com.militaryuavdetection.viewmodel.ImageViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader

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


    private val imageViewModel: ImageViewModel by viewModels {
        ImageViewModelFactory((application as MilitaryUavApplication).database.imageRecordDao())
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
            }
        }
    }

    private fun setupClickListeners() {
        binding.browseImageButton.setOnClickListener { openImagePicker() }
        binding.browseModelButton.setOnClickListener { openModelPicker() }
        binding.markTypeButton.setOnClickListener { cycleMarkingMode() }
        binding.cameraButton.setOnClickListener { showCameraOptions() }
        binding.exportButton.setOnClickListener { selectExportLocation() }

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
        currentRecord?.let { selectRecord(it) }
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

    private fun openImagePicker() {
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

    private fun showCameraOptions() { /* ... */ }
    private fun selectExportLocation() { /* ... */ }
    private fun showSearch() { /* ... */ }
    private fun hideSearch() { /* ... */ }

    private fun clearSelection() {
        currentRecord = null
        fileListAdapter.updateSelection(null)
        binding.imagePanel.setImageDrawable(null)
        binding.imagePanel.setDetections(emptyList(), markingMode, instanceValues, colorMap)
        binding.imageName.text = "Image Name"
        binding.imageSize.text = "Size"
    }

    private fun insertUrisAndAnalyze(uris: List<Uri>) {
        lifecycleScope.launch {
            var reprocessed = false
            val recordsToInsert = uris.mapNotNull { uri ->
                try {
                    val existingRecord = imageViewModel.getRecordByUri(uri.toString())
                    if (existingRecord != null) {
                        imageViewModel.delete(existingRecord)
                        reprocessed = true
                    }

                    val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return@mapNotNull null
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
                    pfd.close()

                    val mediaType = if (options.outMimeType?.startsWith("image/") == true) "IMAGE" else "VIDEO"
                    val documentFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(this@MainActivity, uri)

                    val detections = if (mediaType == "IMAGE" && currentModel != null) objectDetector.analyzeImage(uri) else emptyList()
                    val bboxesJson = gson.toJson(detections)

                    ImageRecord(
                        uri = uri.toString(),
                        name = documentFile?.name ?: "Unknown",
                        dateModified = documentFile?.lastModified() ?: 0,
                        size = documentFile?.length() ?: 0,
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
                if (reprocessed) {
                    Toast.makeText(this@MainActivity, "Frame updated for existing image(s)", Toast.LENGTH_SHORT).show()
                }
                imageViewModel.getLastInsertedId()?.let { lastId ->
                    imageViewModel.getRecordById(lastId)?.let { selectRecord(it) }
                }
            }
        }
    }

    private suspend fun loadSampleImages() = withContext(Dispatchers.IO) {
        val importDir = "import"
        try {
            val sampleImageNames = assets.list(importDir)
            if (sampleImageNames.isNullOrEmpty()) return@withContext

            val tempFileUris = sampleImageNames.mapNotNull { fileName ->
                try {
                    val inputStream = assets.open("$importDir/$fileName")
                    val tempFile = File(cacheDir, fileName)
                    val outputStream = FileOutputStream(tempFile)
                    inputStream.copyTo(outputStream)
                    Uri.fromFile(tempFile)
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

        } catch (e: IOException) {
            e.printStackTrace()
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
