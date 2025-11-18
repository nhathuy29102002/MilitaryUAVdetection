package com.militaryuavdetection

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.militaryuavdetection.database.ImageRecord
import com.militaryuavdetection.databinding.ActivityMainBinding
import com.militaryuavdetection.ui.adapter.FileListAdapter
import com.militaryuavdetection.ui.adapter.GridSpacingItemDecoration
import com.militaryuavdetection.utils.ObjectDetector
import com.militaryuavdetection.viewmodel.ImageViewModel
import com.militaryuavdetection.viewmodel.ImageViewModelFactory
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.IOException

enum class MarkingMode { OFF, MARK, BOX, NAME, CONFIDENCE }

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

    private val imageViewModel: ImageViewModel by viewModels {
        ImageViewModelFactory((application as MilitaryUavApplication).database.imageRecordDao())
    }

    private val openFilesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = result.data?.clipData?.let {
                clipData -> (0 until clipData.itemCount).map { clipData.getItemAt(it).uri }
            } ?: result.data?.data?.let { listOf(it) } ?: emptyList()

            if (uris.isNotEmpty()) {
                uris.forEach { uri ->
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
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

        currentModel = sharedPreferences.getString("selected_model", null)
        currentModel?.let {
            lifecycleScope.launch {
                objectDetector.loadModel(it)
                binding.browseModelButton.setImageResource(R.drawable.importmodel_check)
            }
        }

        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        fileListAdapter = FileListAdapter(emptyList(), emptyList())
        binding.recyclerView.adapter = fileListAdapter
        setViewMode(FileListAdapter.ViewMode.ICON)

        fileListAdapter.onItemClick = { record ->
            selectRecord(record)
        }
    }

    private fun selectRecord(record: ImageRecord){
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
            binding.imagePanel.setDetections(detections)

        } catch (e: SecurityException) {
            Toast.makeText(this, "Permission denied for ${record.name}. Please re-import the file.", Toast.LENGTH_LONG).show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            imageViewModel.allRecords.collect { records ->
                fileListAdapter.updateData(records)
                currentRecord?.let { fileListAdapter.updateSelection(it) }
            }
        }
    }

    private fun setupClickListeners() {
        binding.browseImageButton.setOnClickListener { openImagePicker() }
        binding.browseModelButton.setOnClickListener { openModelPicker() }
        binding.markTypeButton.setOnClickListener { cycleMarkingMode() }
        binding.cameraButton.setOnClickListener { showCameraOptions() }
        binding.exportButton.setOnClickListener { selectExportLocation() }

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
        // You might want to update the image panel to reflect the new marking mode
        binding.imagePanel.invalidate() // Redraws the view
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

    private fun showCameraOptions() {
        val options = arrayOf("Take Picture", "Record Video", "Real-time Processing")
        AlertDialog.Builder(this)
            .setTitle("Camera Options")
            .setItems(options) { _, which ->
                // Handle camera actions
            }
            .show()
    }

    private fun selectExportLocation() {
        // Use ACTION_OPEN_DOCUMENT_TREE to select a directory
        Toast.makeText(this, "Export location selected (Placeholder)", Toast.LENGTH_SHORT).show()
    }

    private fun showSearch() {
        binding.imageName.visibility = View.GONE
        binding.imageSize.visibility = View.GONE
        binding.extendListPanelButton.visibility = View.GONE
        binding.searchEditText.visibility = View.VISIBLE
        binding.searchEditText.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSearch() {
        binding.imageName.visibility = View.VISIBLE
        binding.imageSize.visibility = View.VISIBLE
        binding.extendListPanelButton.visibility = View.VISIBLE
        binding.searchEditText.visibility = View.GONE
        binding.searchEditText.text.clear()

        currentRecord?.let {
            binding.imageName.text = it.name
            binding.imageSize.text = "${it.width}x${it.height}"
        } ?: run {
            binding.imageName.text = "Image Name"
            binding.imageSize.text = "Size"
        }

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
    }

    private fun insertUrisAndAnalyze(uris: List<Uri>) {
        lifecycleScope.launch {
            val records = uris.mapNotNull { uri ->
                try {
                    val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return@mapNotNull null
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
                    pfd.close()

                    val mediaType = when {
                        options.outMimeType?.startsWith("image/") == true -> "IMAGE"
                        options.outMimeType?.startsWith("video/") == true -> "VIDEO"
                        else -> return@mapNotNull null
                    }

                    val documentFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(this@MainActivity, uri)

                    val detections = if (mediaType == "IMAGE") objectDetector.analyzeImage(uri) else emptyList()
                    val bboxesJson = gson.toJson(detections)

                    ImageRecord(
                        uri = uri.toString(),
                        name = documentFile?.name ?: "Unknown",
                        dateModified = documentFile?.lastModified() ?: 0,
                        size = documentFile?.length() ?: 0,
                        mediaType = mediaType,
                        width = options.outWidth,
                        height = options.outHeight,
                        boundingBoxes = bboxesJson,
                        confidence = null, // This can be populated from detections if needed
                        isSaved = false
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            if (records.isNotEmpty()) {
                imageViewModel.insertAll(records)
                // Automatically select the last imported image
                imageViewModel.getLastInsertedId()?.let { lastId ->
                    imageViewModel.getRecordById(lastId)?.let { selectRecord(it) }
                }
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