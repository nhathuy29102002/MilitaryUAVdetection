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
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.militaryuavdetection.database.ImageRecord
import com.militaryuavdetection.databinding.ActivityMainBinding
import com.militaryuavdetection.ui.adapter.FileListAdapter
import com.militaryuavdetection.viewmodel.ImageViewModel
import com.militaryuavdetection.viewmodel.ImageViewModelFactory
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var fileListAdapter: FileListAdapter
    private var currentModel: String? = null
    private var isListPanelExtended = false
    private lateinit var sharedPreferences: SharedPreferences

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
                insertUrisAsRecords(uris)
                lifecycleScope.launch{
                    imageViewModel.getLastInsertedId()?.let { lastId ->
                        imageViewModel.getRecordById(lastId)?.let { selectRecord(it) }
                    }
                }
            }
        }
    }

    private val openModelLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                currentModel = uri.toString()
                sharedPreferences.edit().putString("selected_model", currentModel).apply()
                binding.browseModelButton.setImageResource(R.drawable.importmodel_check)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences("MilitaryUavPrefs", Context.MODE_PRIVATE)
        currentModel = sharedPreferences.getString("selected_model", null)
        if (currentModel != null) {
            binding.browseModelButton.setImageResource(R.drawable.importmodel_check)
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
        try {
            binding.imagePanel.setImageURI(record.uri.toUri())
            binding.imageName.text = record.name
            binding.imageSize.text = "${record.width}x${record.height}"
        } catch (e: SecurityException) {
            Toast.makeText(this, "Permission denied for ${record.name}. Please re-import the file.", Toast.LENGTH_LONG).show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            imageViewModel.allRecords.collect { records ->
                fileListAdapter.updateData(records)
            }
        }
    }

    private fun setupClickListeners() {
        binding.browseImageButton.setOnClickListener { showImageImportOptions() }
        binding.browseModelButton.setOnClickListener { openModelPicker() }
        binding.markTypeButton.setOnClickListener { showMarkingOptions() }
        binding.cameraButton.setOnClickListener { showCameraOptions() }
        binding.exportButton.setOnClickListener { selectExportLocation() }

        binding.viewModeIcon.setOnClickListener { setViewMode(FileListAdapter.ViewMode.ICON) }
        binding.viewModeDetail.setOnClickListener { setViewMode(FileListAdapter.ViewMode.DETAIL) }
        binding.viewModeContent.setOnClickListener { setViewMode(FileListAdapter.ViewMode.CONTENT) }

        binding.extendListPanelButton.setOnClickListener { toggleListPanelExtension() }
        binding.searchIcon.setOnClickListener { showSearch() }

        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                fileListAdapter.filter(s.toString())
            }

            override fun afterTextChanged(s: Editable?) {}
        })
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

    private fun showImageImportOptions(){
        val options = arrayOf("Import Files", "Import Folder")
        AlertDialog.Builder(this)
            .setTitle("Import Media")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                        openFilesLauncher.launch(intent)
                    }
                    1 -> { /* Logic for folder import - requires ACTION_OPEN_DOCUMENT_TREE */ }
                }
            }
            .show()
    }

    private fun openModelPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // Should be application/octet-stream for .onnx, but * a safer bet
        }
        openModelLauncher.launch(intent)
    }

    private fun showMarkingOptions() {
        val options = arrayOf("No Bounding Box", "Bounding Box", "Box + Instance", "Box + Confidence")
        AlertDialog.Builder(this)
            .setTitle("Select Marking Type")
            .setItems(options) { _, which ->
                // Handle selection
            }
            .show()
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
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
    }

    private fun insertUrisAsRecords(uris: List<Uri>) {
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

                val documentFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(this, uri)

                ImageRecord(
                    uri = uri.toString(),
                    name = documentFile?.name ?: "Unknown",
                    dateModified = documentFile?.lastModified() ?: 0,
                    size = documentFile?.length() ?: 0,
                    mediaType = mediaType,
                    width = options.outWidth,
                    height = options.outHeight,
                    boundingBoxes = null,
                    confidence = null,
                    isSaved = false
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
        if (records.isNotEmpty()) {
            imageViewModel.insertAll(records)
        }
    }

    private fun setViewMode(viewMode: FileListAdapter.ViewMode) {
        fileListAdapter.setViewMode(viewMode)
        binding.recyclerView.layoutManager = when (viewMode) {
            FileListAdapter.ViewMode.ICON -> GridLayoutManager(this, 4)
            FileListAdapter.ViewMode.DETAIL, FileListAdapter.ViewMode.CONTENT -> LinearLayoutManager(this)
        }
    }
}
