package com.example.militaryuavdetection

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.example.militaryuavdetection.databinding.ActivityCameraBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var yuvToRgbConverter: YuvToRgbConverter

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null

    private var captureMode: String = "REALTIME"
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        captureMode = intent.getStringExtra("CAPTURE_MODE") ?: "REALTIME"
        yuvToRgbConverter = YuvToRgbConverter(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        startCamera()
        setupListeners()
        observeViewModel()

        if (captureMode != "REALTIME") {
            binding.cameraBboxOverlay.visibility = View.GONE
            binding.btnCapture.visibility = View.VISIBLE
        } else {
            binding.btnCapture.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        binding.btnCapture.setOnClickListener {
            when (captureMode) {
                "PHOTO" -> takePhoto()
                "VIDEO" -> toggleVideoRecording()
            }
        }
        binding.btnCloseCamera.setOnClickListener { finish() }
    }

    private fun observeViewModel() {
        if (captureMode == "REALTIME") {
            // (SỬA LỖI) Nhận cả results và labels
            viewModel.realtimeResults.observe(this) { (results, labels) ->
                binding.cameraBboxOverlay.setResults(results, viewModel.markType.value ?: 3, labels)
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        cameraProvider.unbindAll()

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
            .also { it.setSurfaceProvider(binding.cameraPreviewView.surfaceProvider) }

        try {
            when (captureMode) {
                "REALTIME" -> {
                    if (viewModel.isModelLoaded.value != true) {
                        Toast.makeText(this, "Lỗi: Model chưa được tải", Toast.LENGTH_LONG).show()
                        finish()
                        return
                    }
                    imageAnalyzer = ImageAnalysis.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(cameraExecutor, RealtimeAnalyzer()) }
                    camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
                }
                "PHOTO" -> {
                    imageCapture = ImageCapture.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                        .build()
                    camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                }
                "VIDEO" -> {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD))
                        .build()
                    videoCapture = VideoCapture.withOutput(recorder)
                    camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
                }
            }
        } catch (exc: Exception) {
            Log.e("CameraActivity", "Lỗi bind use cases", exc)
        }
    }

    private inner class RealtimeAnalyzer : ImageAnalysis.Analyzer {
        private var lastAnalyzedTimestamp = 0L
        private var bitmap: Bitmap? = null

        @SuppressLint("UnsafeOptInUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            val currentTimestamp = System.currentTimeMillis()
            if (currentTimestamp - lastAnalyzedTimestamp < 100) { // 10 FPS
                imageProxy.close()
                return
            }
            lastAnalyzedTimestamp = currentTimestamp

            val image = imageProxy.image ?: run { imageProxy.close(); return }

            if (bitmap == null || bitmap!!.width != imageProxy.width || bitmap!!.height != imageProxy.height) {
                bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            }

            yuvToRgbConverter.yuvToRgb(image, bitmap!!)
            val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
            val rotatedBitmap = Bitmap.createBitmap(bitmap!!, 0, 0, bitmap!!.width, bitmap!!.height, matrix, true)

            viewModel.detectInRealtime(rotatedBitmap)
            imageProxy.close()
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val photoFile = File(cacheDir, SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Log.e("CameraActivity", "Chụp ảnh thất bại: ${exc.message}", exc)
            }
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val msg = "Đã chụp ảnh: ${photoFile.name}"
                runOnUiThread { Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show() }
                // TODO: Gửi photoFile.path về MainActivity/ViewModel
                finish()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun toggleVideoRecording() {
        val videoCapture = videoCapture ?: return
        if (recording != null) {
            recording?.stop()
            recording = null
            binding.btnCapture.setImageResource(android.R.drawable.ic_menu_camera)
            return
        }

        val videoFile = File(cacheDir, SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".mp4")
        val outputOptions = FileOutputOptions.Builder(videoFile).build()

        if (PermissionChecker.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED) {
            recording = videoCapture.output.prepareRecording(this, outputOptions).withAudioEnabled().start(cameraExecutor) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        runOnUiThread { binding.btnCapture.setImageResource(android.R.drawable.ic_media_pause) }
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Đã quay video: ${videoFile.name}"
                            runOnUiThread { Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show() }
                            // TODO: Gửi videoFile.path về MainActivity/ViewModel
                        } else {
                            recording?.close()
                            recording = null
                            Log.e("CameraActivity", "Lỗi quay video: ${recordEvent.error}")
                        }
                        finish()
                    }
                }
            }
        } else {
            Toast.makeText(this, "Cần cấp quyền Ghi âm", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}