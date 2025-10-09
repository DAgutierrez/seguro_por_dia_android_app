package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.TextView
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
    private var imageAnalyzerRef: ImageAnalysis? = null
    private var lastRotatedBitmap: android.graphics.Bitmap? = null
    private var captureMode: Boolean = false
    private var captureSlot: String? = null
    private var inspectionViewId: Int = -1
    private var inspectionViewDescription: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        captureMode = intent.getBooleanExtra("captureMode", false)
        captureSlot = intent.getStringExtra("slot")
        inspectionViewId = intent.getIntExtra("inspectionViewId", -1)
        inspectionViewDescription = intent.getStringExtra("inspectionViewDescription")

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

            // GPU is enabled by default
            Log.d(TAG, "GPU acceleration enabled by default")

            if (captureMode) {
                captureButton.setOnClickListener {
                    try {
                        val bmp = lastRotatedBitmap
                        if (bmp == null) {
                            android.widget.Toast.makeText(this@CameraViewActivity, "Preparando cámara... intenta de nuevo", android.widget.Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        binding.captureButton.isEnabled = false
                        android.widget.Toast.makeText(this@CameraViewActivity, "Capturando...", android.widget.Toast.LENGTH_SHORT).show()
                        val stream = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                        val bytes = stream.toByteArray()
                        Executors.newSingleThreadExecutor().execute {
                            try {
                                val uploadedPublicUrl = SupabaseClientProvider.uploadPng(bytes, pathPrefix = captureSlot ?: "")
                                val storagePath = SupabaseClientProvider.storagePathFromPublicUrl(uploadedPublicUrl)

                                // Run prechecks before returning - execute async on UI
                                Log.d(TAG, "About to run prechecks with storagePath: $storagePath")
                                runOnUiThread {
                                    runPrechecksAsync(storagePath, uploadedPublicUrl)
                                }
                            } catch (t: Throwable) {
                                Log.e(TAG, "Upload/precheck failed: ${t.message}", t)
                                runOnUiThread {
                                    binding.captureButton.isEnabled = true
                                    android.widget.Toast.makeText(this@CameraViewActivity, "Error: ${t.message}", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Capture failed: ${t.message}")
                        binding.captureButton.isEnabled = true
                        android.widget.Toast.makeText(this@CameraViewActivity, "Error al capturar", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun runPrechecksAsync(storagePath: String, uploadedPublicUrl: String) {
        Log.d(TAG, "runPrechecksAsync called - inspectionViewId: $inspectionViewId")
        if (inspectionViewId <= 0) {
            Log.d(TAG, "No inspectionViewId, finishing with success")
            finishWithSuccess(uploadedPublicUrl)
            return
        }
        
        // Show overlay immediately
        Log.d(TAG, "Showing precheck progress overlay")
        val overlayRef = showPrecheckProgress()
        Log.d(TAG, "Overlay created: $overlayRef")
        
        // Run prechecks in background
        Executors.newSingleThreadExecutor().execute {
            try {
                val prechecks = SupabaseClientProvider.getPrechecksForInspectionView(inspectionViewId)
                android.util.Log.d("SupabasePrecheck", "precheck: $prechecks")
                
                if (prechecks.isEmpty()) {
                    runOnUiThread {
                        dismissPrecheckProgress(overlayRef)
                        finishWithSuccess(uploadedPublicUrl)
                    }
                    return@execute
                }

                for (i in prechecks.indices) {
                    val p = prechecks[i]
                    
                    runOnUiThread {
                        updatePrecheckProgress(
                            overlayRef,
                            statusText = "Validando",
                            detailText = "Paso ${i + 1} de ${prechecks.size}"
                        )
                    }
                    
                    val bodyJson = org.json.JSONObject().apply {
                        put("imageUrl", storagePath)
                        put("responseValue", p.responseValue)
                    }.toString()
                    android.util.Log.d("SupabasePrecheck", "body: $bodyJson")
                    val responseText = SupabaseClientProvider.postJson(p.url, bodyJson)
                    android.util.Log.d("SupabasePrecheck", "response: $responseText")
                    
                    // Parse response safely
                    val success = try {
                        val responseJson = org.json.JSONObject(responseText)
                        responseJson.getBoolean("success")
                    } catch (e: Exception) {
                        android.util.Log.e("SupabasePrecheck", "Error parsing response JSON: ${e.message}")
                        false
                    }
                    android.util.Log.d("SupabasePrecheck", "success: $success")
                    runOnUiThread {
                        updatePrecheckProgress(
                            overlayRef,
                            statusText = if (success) "Validado" else "Error",
                            detailText = if (success) p.successMessage else p.errorMessage
                        )
                    }
                    
                    if (!success) {
                        runOnUiThread {
                            dismissPrecheckProgress(overlayRef)
                            //showInfoDialog(p.errorMessage)
                            // SupabaseClientProvider.deleteImage(storagePath)
                            binding.captureButton.isEnabled = true
                            android.widget.Toast.makeText(this@CameraViewActivity, "Foto rechazada: ${p.errorMessage}", android.widget.Toast.LENGTH_LONG).show()
                        }
                        return@execute
                    }
                }
                
                runOnUiThread {
                    try {
                        dismissPrecheckProgress(overlayRef)
                        finishWithSuccess(uploadedPublicUrl)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in final success flow: ${e.message}")
                        // Fallback: try to finish anyway
                        try {
                            finishWithSuccess(uploadedPublicUrl)
                        } catch (e2: Exception) {
                            Log.e(TAG, "Error in fallback finish: ${e2.message}")
                            finish()
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Precheck error: ${e.message}")
                runOnUiThread {
                    dismissPrecheckProgress(overlayRef)
                    //SupabaseClientProvider.deleteImage(storagePath)
                    binding.captureButton.isEnabled = true
                    showInfoDialog("Error en validación: ${e.message}")
                    android.widget.Toast.makeText(this@CameraViewActivity, "Error en validación, foto eliminada", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun finishWithSuccess(uploadedPublicUrl: String) {
        try {
            val result = android.content.Intent().apply {
                putExtra("uploadedUrl", uploadedPublicUrl)
                putExtra("slot", captureSlot)
            }
            setResult(android.app.Activity.RESULT_OK, result)
            android.widget.Toast.makeText(this, "Foto validada y subida", android.widget.Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error in finishWithSuccess: ${e.message}")
            // Fallback: just finish without extras
            try {
                finish()
            } catch (e2: Exception) {
                Log.e(TAG, "Error in fallback finish: ${e2.message}")
            }
        }
    }

    private fun extractValueFromJson(jsonText: String, attribute: String): String? {
        return try {
            val obj = org.json.JSONObject(jsonText)
            // support nested attributes like a.b.c
            val parts = attribute.split('.')
            var current: Any = obj
            for (part in parts) {
                if (current is org.json.JSONObject) {
                    current =
                        if ((current as org.json.JSONObject).has(part)) (current as org.json.JSONObject).get(
                            part
                        ) else return null
                } else return null
            }
            when (current) {
                is String -> current
                is Number -> current.toString()
                is Boolean -> current.toString()
                else -> current.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
            null
        }
    }

    @android.annotation.SuppressLint("InflateParams")
    private fun showPrecheckProgress(): android.widget.FrameLayout {
        Log.d(TAG, "showPrecheckProgress starting")
        val rootView = findViewById<android.view.ViewGroup>(android.R.id.content)
        Log.d(TAG, "Root view found: $rootView")
        
        // Remove any existing overlay first
        val existingOverlay = findViewById<android.widget.FrameLayout>(R.id.precheck_overlay_root)
        if (existingOverlay != null) {
            Log.d(TAG, "Removing existing overlay")
            rootView.removeView(existingOverlay)
        }
        
        val overlay = LayoutInflater.from(this).inflate(
            R.layout.precheck_progress_overlay,
            rootView,
            false
        ) as android.widget.FrameLayout
        overlay.id = R.id.precheck_overlay_root
        rootView.addView(overlay)
        
        // Ensure visibility
        overlay.visibility = android.view.View.VISIBLE
        overlay.bringToFront()
        
        Log.d(TAG, "Overlay created and added, visible: ${overlay.visibility}, parent: ${overlay.parent}")
        return overlay
    }

    private fun updatePrecheckProgress(
        overlayRef: android.widget.FrameLayout?,
        statusText: String? = null,
        detailText: String? = null
    ) {
        runOnUiThread {
            Log.d(TAG, "updatePrecheckProgress: overlay=$overlayRef, status='$statusText', detail='$detailText'")
            val statusView = overlayRef?.findViewById<TextView>(R.id.precheck_status)
            val detailView = overlayRef?.findViewById<TextView>(R.id.precheck_detail)
            Log.d(TAG, "Views found: status=$statusView, detail=$detailView")
            
            statusView?.text = statusText ?: ""
            detailView?.text = detailText ?: ""
            
            // Force redraw
            overlayRef?.invalidate()
        }
    }

    private fun dismissPrecheckProgress(overlayRef: android.widget.FrameLayout?) {
        runOnUiThread {
            try {
                Log.d(TAG, "dismissPrecheckProgress: removing overlay=$overlayRef")
                val rootView = findViewById<android.view.ViewGroup>(android.R.id.content)
                overlayRef?.let { 
                    if (overlayRef.parent != null) {
                        rootView.removeView(overlayRef)
                        Log.d(TAG, "Overlay removed successfully")
                    } else {
                        Log.d(TAG, "Overlay already removed or has no parent")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing precheck progress: ${e.message}")
            }
        }
    }

    private fun showInfoDialog(message: String) {
        try {
            AlertDialog.Builder(this)
                .setTitle("Validación de Foto")
                .setMessage(message)
                .setPositiveButton("Entendido") { dialog, _ -> 
                    dialog.dismiss()
                    // Usuario permanece en la cámara, no se hace nada adicional
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing dialog: ${e.message}")
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
            val safeRotation = binding.viewFinder.display?.rotation
                ?: windowManager.defaultDisplay.rotation
            val cameraSelector = zoomManager.getCurrentCameraSelector()

            val preview = androidx.camera.core.Preview.Builder()
                .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                .setTargetRotation(safeRotation)
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(safeRotation)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    val localDetector = detector
                    if (localDetector == null) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val bitmapBuffer = android.graphics.Bitmap.createBitmap(
                        imageProxy.width,
                        imageProxy.height,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    imageProxy.planes[0].buffer?.let { buffer ->
                        bitmapBuffer.copyPixelsFromBuffer(buffer)
                    }

                    runOnUiThread {
                        binding.overlay.setRotationParams(
                            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                            isFrontCamera = isFrontCamera
                        )
                        binding.overlay.setImageDimensions(
                            sourceWidth = imageProxy.width,
                            sourceHeight = imageProxy.height
                        )
                    }

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
                    lastRotatedBitmap = rotatedBitmap

                    // Pass rotated bitmap dimensions to overlay for accurate letterbox inverse mapping
                    runOnUiThread {
                        try {
                            binding.overlay.setImageDimensions(
                                sourceWidth = rotatedBitmap.width,
                                sourceHeight = rotatedBitmap.height
                            )
                            // Log.d(TAG, "Overlay image dims set: ${rotatedBitmap.width}x${rotatedBitmap.height}")
                        } catch (_: Throwable) {
                        }
                    }

                    localDetector.detect(rotatedBitmap)
                } catch (t: Throwable) {
                    Log.e(TAG, "Analyzer error: ${t.message}", t)
                } finally {
                    try {
                        imageProxy.close()
                    } catch (_: Throwable) {
                    }
                }
            }

            imageAnalyzerRef = imageAnalyzer

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
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (it[Manifest.permission.CAMERA] == true) {
            startCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            imageAnalyzerRef?.clearAnalyzer()
        } catch (_: Throwable) {
        }
        detector?.close()
        cameraExecutor.shutdown()
    }

    override fun onPause() {
        super.onPause()
        try {
            // Solo limpiar el analyzer, no desvincular la cámara completamente
            imageAnalyzerRef?.clearAnalyzer()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onPause: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            // Reinicializar detector si es necesario
            if (detector == null) {
                initializeDetector()
            }

            // Solo reiniciar cámara si no hay cámara activa
            if (camera == null && allPermissionsGranted()) {
                startCamera()
            } else if (!allPermissionsGranted()) {
                requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume: ${e.message}")
            showErrorDialog("Error reanudando cámara", e.message ?: "Error desconocido")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            Log.d(TAG, "Configuration changed, orientation: ${newConfig.orientation}")

            // Reiniciar la cámara para adaptarse al nuevo layout
            if (allPermissionsGranted()) {
                // Desvincular la cámara actual
                cameraProvider?.unbindAll()
                camera = null

                // Reiniciar la cámara con la nueva configuración
                startCamera()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling configuration change: ${e.message}")
            showErrorDialog("Error adaptando a nueva orientación", e.message ?: "Error desconocido")
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
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
        //Log.d(TAG, "Detection count changed to: $count")
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
