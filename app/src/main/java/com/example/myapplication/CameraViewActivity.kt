package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import android.widget.TextView
import android.widget.ToggleButton
import android.widget.ImageButton
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import kotlin.math.atan
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalCamera2Interop::class)
class CameraViewActivity : AppCompatActivity() {
    private lateinit var viewFinder: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var detector: Detector
    private var camera: Camera? = null

    // Camera controls
    private var currentZoomRatio = 1.0f
    private var minZoomRatio = 1.0f
    private var maxZoomRatio = 1.0f
    private var isFlashOn = false
    private var isDetectionEnabled = true
    private var isFrontCamera = false

    // Multi-camera support
    private var currentCameraSelector: CameraSelector? = null
    private var availableCameras: List<CameraSelector> = emptyList()
    private var ultraWideCameraSelector: CameraSelector? = null
    private var isUsingUltraWide = false
    private var ultraWideCameraId: String? = null
    private var mainCameraId: String? = null

    // Gesture detectors
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Permission request denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            setContentView(R.layout.activity_camera_view)
            Log.d(TAG, "Layout set successfully")

            // Initialize camera executor
            cameraExecutor = Executors.newSingleThreadExecutor()
            Log.d(TAG, "Camera executor initialized")

            // Setup UI first
            setupInitialUI()
            Log.d(TAG, "UI setup completed")

            // Initialize detector
            initializeDetector()
            Log.d(TAG, "Detector initialization completed")

            // Setup gesture detectors
            setupGestureDetectors()
            Log.d(TAG, "Gesture detectors setup completed")

            // Bind listeners
            bindListeners()
            Log.d(TAG, "Listeners bound successfully")

            // Request camera permission
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "Camera permission already granted, starting camera")
                    startCamera()
                }
                else -> {
                    Log.d(TAG, "Requesting camera permission")
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
            showErrorDialog("Inicialización", "Error initializing camera view: ${e.message}", e)
            finish()
        }
    }

    private fun initializeDetector() {
        try {
            detector = Detector(
                context = this,
                modelPath = "model.tflite",
                labelPath = "labels.txt",
                detectorListener = object : Detector.DetectorListener {
                    override fun onEmptyDetect() {
                        runOnUiThread {
                            findViewById<TextView>(R.id.detectionCount)?.text = "0 objects"
                            // Limpiar overlay
                            findViewById<com.example.myapplication.OverlayView>(R.id.overlay)?.apply {
                                clear()
                                invalidate()
                            }
                        }
                    }
                    
                    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
                        runOnUiThread {
                            findViewById<TextView>(R.id.inferenceTime)?.text = "${inferenceTime}ms"
                            findViewById<TextView>(R.id.detectionCount)?.text = "${boundingBoxes.size} objects"
                            // Pintar resultados en overlay
                            findViewById<com.example.myapplication.OverlayView>(R.id.overlay)?.apply {
                                setResults(boundingBoxes)
                                invalidate()
                            }
                        }
                    }
                }
            )
            Log.d(TAG, "Detector initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing detector: ${e.message}", e)
            showErrorDialog("Detector", "Error initializing detector: ${e.message}", e)
            // Continue without detector
        }
    }

    private fun setupInitialUI() {
        try {
            viewFinder = findViewById(R.id.viewFinder)
            
            // Set initial zoom level
            findViewById<TextView>(R.id.zoomLevel)?.text = "1.0x"
            
            // Set initial flash state
            updateFlashIcon()
            
            // Set initial detection state
            findViewById<ToggleButton>(R.id.detectionToggle)?.isChecked = isDetectionEnabled
            
            // Set initial camera ID display
            updateCameraIdDisplay()
        } catch (e: Exception) {
            Log.e(TAG, "Error in setupInitialUI: ${e.message}")
            showErrorDialog("UI Setup", "Error setting up UI: ${e.message}", e)
        }
    }

    private fun setupGestureDetectors() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val newZoomRatio = currentZoomRatio * scaleFactor
                setZoomRatio(newZoomRatio.coerceIn(minZoomRatio, maxZoomRatio))
                return true
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleCamera()
                return true
            }
        })
    }

    private fun bindListeners() {
        // Flash Toggle - ImageButton with click listener
        findViewById<ImageButton>(R.id.flashToggle).setOnClickListener {
            isFlashOn = !isFlashOn
            toggleFlash()
        }

        // Camera Switch - ImageButton with click listener
        findViewById<ImageButton>(R.id.cameraSwitch).setOnClickListener {
            isFrontCamera = !isFrontCamera
            toggleCamera()
        }

        with(findViewById<ToggleButton>(R.id.detectionToggle)) {
            setOnCheckedChangeListener { _, isChecked ->
                isDetectionEnabled = isChecked
            }
        }

        // Quick Zoom Buttons
        findViewById<Button>(R.id.zoom05x).setOnClickListener {
            setZoomRatio(0.5f)
        }

        findViewById<Button>(R.id.zoom1x).setOnClickListener {
            setZoomRatio(1.0f)
        }

        // Camera List Button
        findViewById<ImageButton>(R.id.camera_list_button).setOnClickListener {
            val intent = Intent(this@CameraViewActivity, CameraListActivity::class.java)
            startActivity(intent)
        }

        // Back button
        findViewById<ImageButton>(R.id.back_button).setOnClickListener {
            finish()
        }
    }

    private fun startCamera() {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    detectAvailableCameras()
                    bindCameraUseCases(cameraProvider)
                } catch (exc: Exception) {
                    Log.e(TAG, "Use case binding failed", exc)
                    runOnUiThread {
                        showErrorDialog("Camera Binding", "Camera initialization failed: ${exc.message}", exc)
                    }
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e(TAG, "Error starting camera: ${e.message}")
            showErrorDialog("Camera Start", "Error starting camera: ${e.message}", e)
        }
    }

    private fun detectAvailableCameras() {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList

            Log.d(TAG, "Found ${cameraIds.size} physical cameras")

            for (cameraId in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)

                // Solo mirar cámaras traseras
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                // Distancias focales disponibles en mm
                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)

                // Tamaño físico del sensor
                val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

                if (focalLengths != null && focalLengths.isNotEmpty() && sensorSize != null) {
                    val focal = focalLengths[0]
                    val horizontalAngle = 2 * (atan((sensorSize.width / (2 * focal)).toDouble()) * 180.0 / Math.PI)
                    val verticalAngle = 2 * (atan((sensorSize.height / (2 * focal)).toDouble()) * 180.0 / Math.PI)

                    Log.d(
                        TAG,
                        "cameraId=$cameraId focal=$focal mm | HFOV=$horizontalAngle° | VFOV=$verticalAngle°"
                    )

                    // Regla práctica: ultra wide suele tener focal < 2.5mm o FOV > 90°
                    if (focal < 2.5f ) {
                        ultraWideCameraId = cameraId
                        Log.d(TAG, "👉 Esta parece ser la ULTRA WIDE (0.5x) - cameraId: $cameraId")
                        Log.d(TAG, "Focal length: $focal mm, HFOV: $horizontalAngle°, VFOV: $verticalAngle°")
                        Toast.makeText(this, "🔍 Ultra-wide detectada: ID $cameraId\nFocal: ${String.format("%.1f", focal)}mm, FOV: ${String.format("%.0f", horizontalAngle)}°", Toast.LENGTH_LONG).show()
                    } else {
                        // Si no es ultra-wide y es la primera cámara trasera, es la principal
                        if (mainCameraId == null) {
                            mainCameraId = cameraId
                            Log.d(TAG, "Main back camera: $cameraId")
                            Log.d(TAG, "Focal length: $focal mm, HFOV: $horizontalAngle°, VFOV: $verticalAngle°")
                            Toast.makeText(this, "📷 Cámara principal: ID $cameraId\nFocal: ${String.format("%.1f", focal)}mm, FOV: ${String.format("%.0f", horizontalAngle)}°", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            // Create camera selectors
            createCameraSelectors()

        } catch (e: Exception) {
            Log.e(TAG, "Error detecting cameras: ${e.message}")
        }
    }

    private fun createCameraSelectors() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        
        // Main camera selector
        val mainSelector = if (mainCameraId != null) {
            CameraSelector.Builder()
                .addCameraFilter { cameras ->
                    cameras.filter { cameraInfo ->
                        val camera2Info = Camera2CameraInfo.from(cameraInfo)
                        camera2Info.cameraId == mainCameraId
                    }
                }
                .build()
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Ultra-wide camera selector
        ultraWideCameraSelector = if (ultraWideCameraId != null) {
            CameraSelector.Builder()
                .addCameraFilter { cameras ->
                    cameras.filter { cameraInfo ->
                        val camera2Info = Camera2CameraInfo.from(cameraInfo)
                        camera2Info.cameraId == ultraWideCameraId
                    }
                }
                .build()
        } else {
            null
        }

        currentCameraSelector = mainSelector
        availableCameras = listOfNotNull(mainSelector, ultraWideCameraSelector)
        
        Log.d(TAG, "Created ${availableCameras.size} camera selectors")
        Log.d(TAG, "Main camera ID: $mainCameraId")
        Log.d(TAG, "Ultra-wide camera ID: $ultraWideCameraId")
        Log.d(TAG, "Main selector: $mainSelector")
        Log.d(TAG, "Ultra-wide selector: $ultraWideCameraSelector")
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        val cameraSelector = if (isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            currentCameraSelector ?: CameraSelector.DEFAULT_BACK_CAMERA
        }

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()
            .also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

        val imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, ImageAnalyzer())
            }

        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(this@CameraViewActivity, cameraSelector, preview, imageAnalyzer)

            // Setup zoom controls
            camera?.let { cam ->
                val cameraInfo = cam.cameraInfo
                val zoomState = cameraInfo.zoomState.value
                zoomState?.let {
                    minZoomRatio = it.minZoomRatio
                    maxZoomRatio = it.maxZoomRatio
                    currentZoomRatio = it.zoomRatio
                    updateZoomUI()
                }
            }

            // Update camera ID display
            updateCameraIdDisplay()

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun setZoomRatio(zoomRatio: Float) {
        Log.d(TAG, "setZoomRatio called with: $zoomRatio")
        Log.d(TAG, "ultraWideCameraSelector: $ultraWideCameraSelector")
        Log.d(TAG, "isUsingUltraWide: $isUsingUltraWide")
        Log.d(TAG, "ultraWideCameraId: $ultraWideCameraId")
        Log.d(TAG, "minZoomRatio: $minZoomRatio, maxZoomRatio: $maxZoomRatio")
        
        // Special handling for 0.5x zoom - switch to ultra-wide camera
        if (zoomRatio <= 0.6f && ultraWideCameraSelector != null && !isUsingUltraWide) {
            Log.d(TAG, "Switching to ultra-wide camera for 0.5x zoom")
            switchToUltraWideCamera()
            return
        }
        
        // For 1x zoom or higher, switch to main camera if using ultra-wide
        if (zoomRatio >= 0.9f && isUsingUltraWide) {
            Log.d(TAG, "Switching to main camera for 1x zoom")
            switchToMainCamera()
            return
        }

        // Use regular zoom control with coerceIn
        val newZoomRatio = zoomRatio.coerceIn(minZoomRatio, maxZoomRatio)
        Log.d(TAG, "Using regular zoom control: $newZoomRatio")
        camera?.cameraControl?.setZoomRatio(newZoomRatio)
        currentZoomRatio = newZoomRatio
        updateZoomUI()
    }

    private fun switchToUltraWideCamera() {
        Log.d(TAG, "switchToUltraWideCamera called")
        ultraWideCameraSelector?.let { selector ->
            Log.d(TAG, "Ultra-wide selector found, switching camera...")
            val cameraProvider = ProcessCameraProvider.getInstance(this).get()
            try {
                cameraProvider.unbindAll()
                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                    .also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, ImageAnalyzer()) }

                camera = cameraProvider.bindToLifecycle(this@CameraViewActivity, selector, preview, imageAnalyzer)
                isUsingUltraWide = true
                currentZoomRatio = 0.5f
                updateZoomUI()
                updateCameraIdDisplay()
                
                Log.d(TAG, "✅ Successfully switched to ultra-wide camera")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error switching to ultra-wide camera: ${e.message}")
            }
        } ?: run {
            Log.w(TAG, "⚠️ Ultra-wide camera selector is null, cannot switch")
        }
    }

    private fun switchToMainCamera() {
        val mainSelector = availableCameras.firstOrNull { it != ultraWideCameraSelector }
        mainSelector?.let { selector ->
            val cameraProvider = ProcessCameraProvider.getInstance(this).get()
            try {
                cameraProvider.unbindAll()
                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                    .also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, ImageAnalyzer()) }

                camera = cameraProvider.bindToLifecycle(this@CameraViewActivity, selector, preview, imageAnalyzer)
                isUsingUltraWide = false
                currentZoomRatio = 1.0f
                updateZoomUI()
                updateCameraIdDisplay()
                
                Log.d(TAG, "Switched to main camera")
            } catch (e: Exception) {
                Log.e(TAG, "Error switching to main camera: ${e.message}")
            }
        }
    }

    private fun updateZoomUI() {
        findViewById<TextView>(R.id.zoomLevel).text = String.format("%.1fx", currentZoomRatio)
        
        // Update quick zoom button states
        findViewById<Button>(R.id.zoom05x).isSelected = currentZoomRatio <= 0.6f
        findViewById<Button>(R.id.zoom1x).isSelected = currentZoomRatio >= 0.9f && currentZoomRatio <= 1.1f
    }

    private fun toggleFlash() {
        camera?.cameraControl?.enableTorch(isFlashOn)
        updateFlashIcon()
    }

    private fun updateFlashIcon() {
        val flashToggle = findViewById<ImageButton>(R.id.flashToggle)
        flashToggle.setImageResource(if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
    }

    private fun updateCameraIdDisplay() {
        val cameraIdDisplay = findViewById<TextView>(R.id.cameraIdDisplay)
        val currentCameraId = getCurrentCameraId()
        val cameraType = when {
            currentCameraId == ultraWideCameraId -> "Ultra-Wide"
            currentCameraId == mainCameraId -> "Main"
            isFrontCamera -> "Front"
            else -> "Unknown"
        }
        cameraIdDisplay.text = "Cam: $currentCameraId ($cameraType)"
    }

    private fun getCurrentCameraId(): String {
        return try {
            val cameraInfo = camera?.cameraInfo ?: return "N/A"
            val camera2Info = Camera2CameraInfo.from(cameraInfo)
            camera2Info.cameraId
        } catch (e: Exception) {
            "N/A"
        }
    }

    private fun toggleCamera() {
        isUsingUltraWide = false // Reset ultra-wide state when switching cameras
        val cameraProvider = ProcessCameraProvider.getInstance(this).get()
        bindCameraUseCases(cameraProvider)
    }

    private inner class ImageAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            if (!isDetectionEnabled) {
                imageProxy.close()
                return
            }

            val mediaImage = imageProxy.image
            if (mediaImage != null && ::detector.isInitialized) {
                try {
                    // Convert base bitmap from YUV
                    var bitmap = mediaImageToBitmap(mediaImage)

                    // Apply rotation and optional mirror based on camera info
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val matrix = Matrix().apply {
                        postRotate(rotationDegrees.toFloat())
                        if (isFrontCamera) {
                            postScale(
                                -1f,
                                1f,
                                bitmap.width / 2f,
                                bitmap.height / 2f
                            )
                        }
                    }
                    bitmap = Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                    )

                    detector.detect(bitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in image analysis: ${e.message}")
                }
            }
            imageProxy.close()
        }
    }

    private fun mediaImageToBitmap(mediaImage: android.media.Image): Bitmap {
        val yBuffer = mediaImage.planes[0].buffer
        val uBuffer = mediaImage.planes[1].buffer
        val vBuffer = mediaImage.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, mediaImage.width, mediaImage.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, mediaImage.width, mediaImage.height), 50, out)
        val imageBytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::detector.isInitialized) {
            try {
                detector.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing detector: ${e.message}")
            }
        }
    }

    private fun showErrorDialog(title: String, error: String, exception: Exception? = null) {
        val fullError = if (exception != null) {
            """
            $error
            
            Exception: ${exception.javaClass.simpleName}
            Message: ${exception.message}
            
            Stack Trace:
            ${exception.stackTraceToString()}
            """.trimIndent()
        } else {
            error
        }

        val editText = EditText(this).apply {
            setText(fullError)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isLongClickable = true
            setSelectAllOnFocus(true)
            setTextIsSelectable(true)
            setSingleLine(false)
            setLines(10)
            setMaxLines(20)
            setVerticalScrollBarEnabled(true)
            setHorizontalScrollBarEnabled(true)
        }

        val scrollView = ScrollView(this).apply {
            addView(editText)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                600
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Error: $title")
            .setView(scrollView)
            .setPositiveButton("Copiar") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Error", fullError)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Error copiado al portapapeles", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar", null)
            .setNeutralButton("Log") { _, _ ->
                Log.e(TAG, fullError, exception)
                Toast.makeText(this, "Error guardado en logs", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    companion object {
        private const val TAG = "CameraViewActivity"
    }
}
