package com.militaryuavdetection

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.militaryuavdetection.databinding.ActivityMainBinding
import com.bumptech.glide.Glide
import com.militaryuavdetection.objectdetector.CameraActivity
import com.militaryuavdetection.ui.adapter.FileListAdapter
import com.militaryuavdetection.viewmodel.MainViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var fileListAdapter: FileListAdapter

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                Toast.makeText(this, "Đã cấp quyền", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Ứng dụng cần quyền Camera và Bộ nhớ", Toast.LENGTH_LONG).show()
            }
        }

    private val browseDirectoryLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let {
                Log.d("MainActivity", "Thư mục đã chọn: $it")
                try {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    viewModel.loadDirectory(it)
                } catch (e: SecurityException) {
                    Log.e("MainActivity", "Lỗi cấp quyền thư mục", e)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestPermissions()
        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun setupRecyclerView() {
        fileListAdapter = FileListAdapter { fileItem ->
            viewModel.onFileSelected(fileItem)
        }

        binding.recyclerViewFiles.apply {
            adapter = fileListAdapter
            layoutManager = GridLayoutManager(this@MainActivity, 4)
        }
        fileListAdapter.setViewType(FileListAdapter.VIEW_TYPE_ICON)
    }

    private fun setupClickListeners() {
        binding.btnBrowseImage.setOnClickListener {
            browseDirectoryLauncher.launch(null)
        }

        binding.btnMarkType.setOnClickListener {
            viewModel.cycleMarkType()
        }

        binding.btnCameraActions.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menuInflater.inflate(R.menu.camera_options_menu, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.option_take_photo -> {
                        val intent = Intent(this, CameraActivity::class.java).apply {
                            putExtra("CAPTURE_MODE", "PHOTO")
                        }
                        startActivity(intent)
                        true
                    }
                    R.id.option_record_video -> {
                        val intent = Intent(this, CameraActivity::class.java).apply {
                            putExtra("CAPTURE_MODE", "VIDEO")
                        }
                        startActivity(intent)
                        true
                    }
                    R.id.option_realtime -> {
                        val intent = Intent(this, CameraActivity::class.java).apply {
                            putExtra("CAPTURE_MODE", "REALTIME")
                        }
                        startActivity(intent)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        binding.btnExportLocation.setOnClickListener {
        }

        binding.btnBrowseModel.setOnClickListener {
            Toast.makeText(this, "Model được tự động tải từ assets", Toast.LENGTH_SHORT).show()
        }

        binding.viewModeBar.btnViewIcon.setOnClickListener {
            fileListAdapter.setViewType(FileListAdapter.VIEW_TYPE_ICON)
            binding.recyclerViewFiles.layoutManager = GridLayoutManager(this, 4)
        }

        binding.viewModeBar.btnViewDetail.setOnClickListener {
            fileListAdapter.setViewType(FileListAdapter.VIEW_TYPE_DETAIL)
            binding.recyclerViewFiles.layoutManager = LinearLayoutManager(this)
        }

        binding.viewModeBar.btnViewContent.setOnClickListener {
            fileListAdapter.setViewType(FileListAdapter.VIEW_TYPE_CONTENT)
            binding.recyclerViewFiles.layoutManager = LinearLayoutManager(this)
        }
    }

    private fun observeViewModel() {
        viewModel.fileList.observe(this) { fileList ->
            fileListAdapter.submitList(fileList)
            binding.imagePanelPlaceholder.visibility = if (fileList.isEmpty() && viewModel.selectedFile.value == null) View.VISIBLE else View.GONE
        }

        viewModel.isModelLoaded.observe(this) { isLoaded ->
            if (isLoaded) {
                binding.btnBrowseModel.setImageResource(R.drawable.importmodel_check)
                binding.btnBrowseModel.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_light))
            } else {
                binding.btnBrowseModel.setImageResource(R.drawable.ic_launcher_background)
                binding.btnBrowseModel.clearColorFilter()
            }
        }

        viewModel.markType.observe(this) { markType ->
        }

        viewModel.selectedFile.observe(this) { fileItem ->
            if (fileItem != null) {
                binding.imagePanelPlaceholder.visibility = View.GONE

                Glide.with(this)
                    .load(fileItem.uri)
                    .into(binding.zoomableImageView)

                binding.detailBarContainer.findViewById<android.widget.TextView>(R.id.text_detail_bar)
                    .text = "Tên: ${fileItem.name} - Kích thước: ${fileItem.size / 1024} KB"
            } else {
                binding.imagePanelPlaceholder.visibility = View.VISIBLE
                binding.zoomableImageView.setImageResource(0)
            }
        }
    }
}