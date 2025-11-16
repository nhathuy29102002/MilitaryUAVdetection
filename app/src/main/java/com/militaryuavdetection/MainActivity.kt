package com.example.militaryuavdetection

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
// Import thư viện binding của bạn
import com.example.militaryuavdetection.databinding.ActivityMainBinding
// Import thư viện Glide để tải ảnh
import com.bumptech.glide.Glide

class MainActivity : AppCompatActivity() {

    // (SỬA LỖI) Khởi tạo ViewBinding
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var fileListAdapter: FileListAdapter

    // --- Trình khởi chạy (Launcher) để xin quyền ---
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                Toast.makeText(this, "Đã cấp quyền", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Ứng dụng cần quyền Camera và Bộ nhớ", Toast.LENGTH_LONG).show()
            }
        }

    // --- Trình khởi chạy để chọn Thư mục (Browse Image) ---
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

    // (SỬA LỖI) Xóa browseModelLauncher vì ViewModel tự xử lý

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // (SỬA LỖI) Inflate và set content bằng ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestPermissions()
        setupRecyclerView()
        setupClickListeners()
        observeViewModel()

        // (SỬA LỖI) Không cần gọi hàm này nữa, ViewModel tự chạy
        // viewModel.loadSavedModelPath()
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        // (SỬA LỖI) Thêm quyền ghi âm cho quay video
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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

        // (SỬA LỖI) Dùng binding để truy cập
        binding.listPanel.recyclerViewFiles.apply {
            adapter = fileListAdapter
            layoutManager = GridLayoutManager(this@MainActivity, 4)
        }
        fileListAdapter.setViewType(FileListAdapter.VIEW_TYPE_ICON)
    }

    private fun setupClickListeners() {
        // (SỬA LỖI) Dùng binding để truy cập

        // --- Taskbar Listeners ---
        binding.taskbar.btnBrowseImage.setOnClickListener {
            browseDirectoryLauncher.launch(null)
        }

        binding.taskbar.btnMarkType.setOnClickListener {
            viewModel.cycleMarkType()
        }

        binding.taskbar.btnCameraActions.setOnClickListener { view ->
            val popup = PopupMenu(this, view) // (SỬA LỖI) 'it' đổi thành 'view'
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

        binding.taskbar.btnExportLocation.setOnClickListener {
            // TODO: Mở trình chọn thư mục để lưu vị trí export
        }

        binding.taskbar.btnBrowseModel.setOnClickListener {
            // (SỬA LỖI) Không cần chọn model thủ công nữa, ViewModel tự tải
            Toast.makeText(this, "Model được tự động tải từ assets", Toast.LENGTH_SHORT).show()
            // browseModelLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }

        // --- ListPanel Listeners (ViewMode) ---
        binding.listPanel.viewModeBar.btnViewIcon.setOnClickListener {
            fileListAdapter.setViewType(FileListAdapter.VIEW_TYPE_ICON)
            binding.listPanel.recyclerViewFiles.layoutManager = GridLayoutManager(this, 4)
        }

        binding.listPanel.viewModeBar.btnViewDetail.setOnClickListener {
            fileListAdapter.setViewType(FileListAdapter.VIEW_TYPE_DETAIL)
            binding.listPanel.recyclerViewFiles.layoutManager = LinearLayoutManager(this)
        }

        binding.listPanel.viewModeBar.btnViewContent.setOnClickListener {
            fileListAdapter.setViewType(FileListAdapter.VIEW_TYPE_CONTENT)
            binding.listPanel.recyclerViewFiles.layoutManager = LinearLayoutManager(this)
        }
    }

    private fun observeViewModel() {
        // (SỬA LỖI) Dùng binding để truy cập

        // Lắng nghe danh sách file
        viewModel.fileList.observe(this) { fileList ->
            fileListAdapter.submitList(fileList)
            binding.imagePanel.imagePanelPlaceholder.visibility = if (fileList.isEmpty() && viewModel.selectedFile.value == null) View.VISIBLE else View.GONE
        }

        // Lắng nghe trạng thái model
        viewModel.isModelLoaded.observe(this) { isLoaded ->
            if (isLoaded) {
                // (SỬA LỖI) Sử dụng R.drawable
                binding.taskbar.btnBrowseModel.setImageResource(R.drawable.importmodel_check)
                binding.taskbar.btnBrowseModel.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_light))
            } else {
                binding.taskbar.btnBrowseModel.setImageResource(R.drawable.importmodel)
                binding.taskbar.btnBrowseModel.clearColorFilter()
            }
        }

        // Lắng nghe trạng thái MarkType
        viewModel.markType.observe(this) { markType ->
            // TODO: Cập nhật icon/trạng thái nút MarkType
        }

        // Lắng nghe file được chọn
        viewModel.selectedFile.observe(this) { fileItem ->
            if (fileItem != null) {
                binding.imagePanel.imagePanelPlaceholder.visibility = View.GONE

                // (MỚI) Dùng Glide để tải ảnh
                Glide.with(this)
                    .load(fileItem.uri)
                    .into(binding.imagePanel.zoomableImageView)

                // Cập nhật DetailBar
                binding.listPanel.detailBarContainer.findViewById<android.widget.TextView>(R.id.text_detail_bar)
                    .text = "Tên: ${fileItem.name} - Kích thước: ${fileItem.size / 1024} KB"
            } else {
                binding.imagePanel.imagePanelPlaceholder.visibility = View.VISIBLE
                binding.imagePanel.zoomableImageView.setImageResource(0) // Xóa ảnh
            }
        }
    }
}