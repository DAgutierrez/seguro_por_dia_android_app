package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.Constants.LABELS_PATH
import com.example.myapplication.Constants.MODEL_PATH
import com.example.myapplication.databinding.ActivityCameraViewBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class CameraViewActivity : AppCompatActivity(), Detector.DetectorListener, CameraZoomManager.ZoomListener {
    private lateinit var binding: ActivityCameraViewBinding
    private val isFrontCamera = false

    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var zoomManager: CameraZoomManager
    private var isFlashOn = false
    private var camera: androidx.camera.core.Camera? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        try {
            initializeDetector()
            setupInitialUI()

            if (allPermissionsGranted()) {
                startCamera()
            } else {
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}")
            showErrorDialog("Error inicializando la aplicación", e.message ?: "Error desconocido")
        }
    }

    private fun initializeDetector() {
        try {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this@CameraViewActivity)
            // Enable GPU acceleration by default
            detector?.restart(isGpu = true)
            Log.d(TAG, "Detector initialized successfully with GPU acceleration enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing detector: ${e.message}")
            showErrorDialog("Error inicializando detector", e.message ?: "Error desconocido")
        }
    }

    private fun setupInitialUI() {
        try {

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up initial UI: ${e.message}")
        }
    }

    private fun setupListeners() {
        binding.apply {
            // Back button
            backButton.setOnClickListener {
                finish()
            }

            // Flash Toggle
            flashToggle.setOnClickListener {
                isFlashOn = !isFlashOn
                toggleFlash()
            }

            // Camera List Button
            cameraListButton.setOnClickListener {
                val intent = android.content.Intent(this@CameraViewActivity, CameraListActivity::class.java)
                startActivity(intent)
            }

            // GPU is enabled by default
            Log.d(TAG, "GPU acceleration enabled by default")
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                setupZoomManager()
                // bindCameraUseCases() will be called from setupZoomManager after initialization
            } catch (e: Exception) {
                Log.e(TAG, "Error starting camera: ${e.message}")
                showErrorDialog("Error iniciando cámara", e.message ?: "Error desconocido")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupZoomManager() {
        try {
            cameraProvider?.let { provider ->
                if (detector == null) {
                    Log.e(TAG, "Detector is null, cannot setup zoom manager")
                    return
                }

                Log.d(TAG, "Setting up zoom manager with detector: ${detector != null}")
                
                zoomManager = CameraZoomManager(
                    context = this,
                    cameraProvider = provider,
                    previewView = binding.viewFinder,
                    overlayView = binding.overlay,
                    cameraExecutor = cameraExecutor,
                    detector = detector,
                    isFrontCamera = isFrontCamera
                )

                // Set UI elements
                zoomManager.setUIElements(
                    zoomSlider = binding.zoomSlider,
                    zoom05x = binding.zoom05x,
                    zoom1x = binding.zoom1x
                )

                // Set zoom listener
                zoomManager.setZoomListener(this)

                // Initialize zoom manager
                zoomManager.initialize()

                // Setup other listeners
                setupListeners()
                
                // Now bind camera use cases after everything is ready
                bindCameraUseCases()
                
                Log.d(TAG, "Zoom manager setup completed with detector")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up zoom manager: ${e.message}")
            showErrorDialog("Error configurando zoom manager", e.message ?: "Error desconocido")
        }
    }

    private fun bindCameraUseCases() {
        try {
            Log.d(TAG, "bindCameraUseCases called - detector: ${detector != null}")
            
            // Don't use zoom manager's bindCameraUseCases, handle everything ourselves
            val rotation = binding.viewFinder.display.rotation
            val cameraSelector = zoomManager.getCurrentCameraSelector()

            val preview = androidx.camera.core.Preview.Builder()
                .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(binding.viewFinder.display.rotation)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                if (detector == null) {
                    Log.d(TAG, "Detector is null, skipping analysis")
                    imageProxy.close()
                    return@setAnalyzer
                }

                val bitmapBuffer = android.graphics.Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
                imageProxy.close()

                val matrix = android.graphics.Matrix().apply {
                    postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                    if (isFrontCamera) {
                        postScale(
                            -1f,
                            1f,
                            imageProxy.width.toFloat(),
                            imageProxy.height.toFloat()
                        )
                    }
                }

                val rotatedBitmap = android.graphics.Bitmap.createBitmap(
                    bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                    matrix, true
                )

                Log.d(TAG, "Calling detector.detect() directly")
                detector?.detect(rotatedBitmap)
            }

            // Bind camera with our custom analyzer
            cameraProvider?.unbindAll()
            camera = cameraProvider?.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            if (camera == null) {
                Log.e(TAG, "Failed to bind camera use cases")
                showErrorDialog("Error", "No se pudo configurar la cámara")
                return
            }

            // Set up zoom state observation
            val currentZoomRatio = zoomManager.getCurrentZoomRatio()
            Log.d(TAG, "Setting zoom ratio to: $currentZoomRatio")
            val currentCamera = camera
            currentCamera?.cameraControl?.setZoomRatio(currentZoomRatio)
            currentCamera?.cameraInfo?.zoomState?.observe(this) { zoomState ->
                zoomState?.let {
                    zoomManager.updateZoomState(it.minZoomRatio, it.maxZoomRatio)
                }
            }

            preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            
            Log.d(TAG, "Camera bound with custom image analyzer")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error binding camera use cases: ${e.message}")
            showErrorDialog("Error configurando la cámara", e.message ?: "Error desconocido")
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()){
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf (
            Manifest.permission.CAMERA
        ).toTypedArray()
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.clear()
            zoomManager.updateDetectionCount(0)
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            zoomManager.updateDetectionCount(boundingBoxes.size)
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }
        }
    }

    // CameraZoomManager.ZoomListener implementation
    override fun onCameraSwitched(cameraId: String) {
        Log.d(TAG, "Camera switched to: $cameraId")
    }

    override fun onZoomChanged(zoomRatio: Float) {
        Log.d(TAG, "Zoom changed to: $zoomRatio")
    }

    override fun onCameraSwitchNeeded() {
        Log.d(TAG, "Camera switch needed, rebinding with new selector")
        // Rebind camera with new selector but keep our custom image analyzer
        bindCameraUseCases()
    }

    override fun onDetectionCountChanged(count: Int) {
        Log.d(TAG, "Detection count changed to: $count")
    }

    private fun toggleFlash() {
        try {
            val currentCamera = camera
            currentCamera?.cameraControl?.enableTorch(isFlashOn)
            updateFlashIcon()
            Log.d(TAG, "Flash toggled: $isFlashOn")
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling flash: ${e.message}")
        }
    }

    private fun updateFlashIcon() {
        try {
            binding.flashToggle.setImageResource(
                if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error updating flash icon: ${e.message}")
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return if (::zoomManager.isInitialized) {
            zoomManager.onTouchEvent(event)
        } else {
            super.onTouchEvent(event)
        }
    }

    private fun showErrorDialog(title: String, message: String) {
        try {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing error dialog: ${e.message}")
        }
    }
}
