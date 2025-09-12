package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlin.math.atan
import kotlin.math.PI

@OptIn(ExperimentalCamera2Interop::class)
class CameraZoomManager(
    private val context: Context,
    private val cameraProvider: ProcessCameraProvider,
    private val previewView: androidx.camera.view.PreviewView,
    private val overlayView: OverlayView,
    private val cameraExecutor: java.util.concurrent.ExecutorService,
    private val detector: Detector?,
    private val isFrontCamera: Boolean = false
) {
    
    // UI Elements
    private var zoomSlider: SeekBar? = null
    private var zoom05x: Button? = null
    private var zoom1x: Button? = null
    private var detectionCount: TextView? = null
    private var cameraIdDisplay: TextView? = null
    
    // Zoom variables
    private var zoomRatio = 1.0f
    private var minZoomRatio = 1.0f
    private var maxZoomRatio = 1.0f
    private var isDetectionEnabled = true
    private var detectionCountValue = 0

    // Camera detection variables
    private var ultraWideCameraId: String? = null
    private var mainCameraId: String? = null
    private var ultraWideCameraSelector: CameraSelector? = null
    private var mainCameraSelector: CameraSelector? = null
    private var currentCameraSelector: CameraSelector? = null

    // Gesture detectors
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector
    
    // Camera instances
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null

    companion object {
        private const val TAG = "CameraZoomManager"
    }

    interface ZoomListener {
        fun onCameraSwitched(cameraId: String)
        fun onZoomChanged(zoomRatio: Float)
        fun onDetectionCountChanged(count: Int)
        fun onCameraSwitchNeeded()
    }

    private var zoomListener: ZoomListener? = null

    fun setZoomListener(listener: ZoomListener) {
        this.zoomListener = listener
    }

    fun setUIElements(
        zoomSlider: SeekBar?,
        zoom05x: Button?,
        zoom1x: Button?,
        detectionCount: TextView?,
        cameraIdDisplay: TextView?
    ) {
        this.zoomSlider = zoomSlider
        this.detectionCount = detectionCount
        this.cameraIdDisplay = cameraIdDisplay
        this.zoom05x = zoom05x
        this.zoom1x = zoom1x
    }

    fun initialize() {
        try {
            setupInitialUI()
            setupGestureDetectors()
            detectAvailableCameras()
            setupListeners()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing CameraZoomManager: ${e.message}")
            showErrorDialog("Error inicializando zoom", e.message ?: "Error desconocido")
        }
    }

    private fun setupInitialUI() {
        try {
            detectionCount?.text = "Detections: 0"
            cameraIdDisplay?.text = "Camera ID: Unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up initial UI: ${e.message}")
        }
    }

    private fun setupGestureDetectors() {
        try {
            scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val scaleFactor = detector.scaleFactor
                    val newZoomRatio = zoomRatio * scaleFactor
                    setZoomRatio(newZoomRatio)
                    return true
                }
            })

            gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // Double tap to switch between 1x and 0.5x
                    if (zoomRatio <= 1.0f) {
                        setZoomRatio(0.5f)
                    } else {
                        setZoomRatio(1.0f)
                    }
                    return true
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up gesture detectors: ${e.message}")
        }
    }

    private fun detectAvailableCameras() {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIdList = cameraManager.cameraIdList

            for (cameraId in cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                
                // Solo mirar cámaras traseras
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

                if (focalLengths != null && focalLengths.isNotEmpty() && sensorSize != null) {
                    val focal = focalLengths[0]
                    val horizontalAngle = 2 * Math.toDegrees(atan((sensorSize.width / (2 * focal)).toDouble()))
                    val verticalAngle = 2 * Math.toDegrees(atan((sensorSize.height / (2 * focal)).toDouble()))

                    Log.d(TAG, "Camera ID: $cameraId, Focal: $focal mm, HFOV: ${horizontalAngle}°, VFOV: ${verticalAngle}°")

                    // Regla práctica: ultra wide suele tener focal < 2.5mm o FOV > 90°
                    if (focal < 2.5f ) {
                        ultraWideCameraId = cameraId
                        Log.d(TAG, "🎯 ULTRA-WIDE CAMERA DETECTED: $cameraId (focal: $focal mm, HFOV: ${horizontalAngle}°)")
                    } else {
                        mainCameraId = cameraId
                        Log.d(TAG, "📷 MAIN CAMERA DETECTED: $cameraId (focal: $focal mm, HFOV: ${horizontalAngle}°)")
                    }
                }
            }

            createCameraSelectors()
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting cameras: ${e.message}")
        }
    }

    private fun createCameraSelectors() {
        try {
            if (ultraWideCameraId != null) {
                ultraWideCameraSelector = CameraSelector.Builder()
                    .addCameraFilter { cameraInfoList: List<CameraInfo> ->
                        cameraInfoList.filter { cameraInfo ->
                            try {
                                val camera2Info = Camera2CameraInfo.from(cameraInfo)
                                camera2Info.cameraId == ultraWideCameraId
                            } catch (e: Exception) {
                                false
                            }
                        }
                    }
                    .build()
                Log.d(TAG, "Ultra-wide camera selector created for: $ultraWideCameraId")
            }

            if (mainCameraId != null) {
                mainCameraSelector = CameraSelector.Builder()
                    .addCameraFilter { cameraInfoList: List<CameraInfo> ->
                        cameraInfoList.filter { cameraInfo ->
                            try {
                                val camera2Info = Camera2CameraInfo.from(cameraInfo)
                                camera2Info.cameraId == mainCameraId
                            } catch (e: Exception) {
                                false
                            }
                        }
                    }
                    .build()
                Log.d(TAG, "Main camera selector created for: $mainCameraId")
            }

            // Set initial camera selector to main camera
            currentCameraSelector = mainCameraSelector ?: CameraSelector.DEFAULT_BACK_CAMERA
            Log.d(TAG, "Initial camera selector set to: ${if (currentCameraSelector == mainCameraSelector) "main" else "default"}")
            Log.d(TAG, "📊 CAMERA SUMMARY:")
            Log.d(TAG, "   - Ultra-wide camera ID: $ultraWideCameraId")
            Log.d(TAG, "   - Main camera ID: $mainCameraId")
            Log.d(TAG, "   - Ultra-wide selector: ${ultraWideCameraSelector != null}")
            Log.d(TAG, "   - Main selector: ${mainCameraSelector != null}")
            Log.d(TAG, "   - Current selector: ${currentCameraSelector != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera selectors: ${e.message}")
        }
    }

    private fun setupListeners() {
        try {
            // Zoom button listeners
            zoom05x?.setOnClickListener {
                Log.d(TAG, "🔘 0.5x button pressed")
                // Update internal zoom ratio first
                zoomRatio = 0.5f
                Log.d(TAG, "📊 Internal zoomRatio updated to: $zoomRatio")
                
                // Try to force switch to ultra-wide first
                val switched = forceSwitchToUltraWide()
                if (switched) {
                    Log.d(TAG, "🔄 Ultra-wide switch successful, notifying listener")
                    updateZoomButtonStates()
                    zoomListener?.onCameraSwitchNeeded()
                } else {
                    Log.d(TAG, "⚠️ Ultra-wide switch failed, using normal zoom")
                    setZoomRatio(0.5f)
                }
            }

            zoom1x?.setOnClickListener {
                Log.d(TAG, "🔘 1x button pressed")
                // Update internal zoom ratio first
                zoomRatio = 1.0f
                Log.d(TAG, "📊 Internal zoomRatio updated to: $zoomRatio")
                setZoomRatio(1.0f)
            }

            // Zoom slider listener
            zoomSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val newZoomRatio = minZoomRatio + (maxZoomRatio - minZoomRatio) * (progress / 100f)
                        setZoomRatio(newZoomRatio)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up listeners: ${e.message}")
        }
    }

    fun bindCameraUseCases(): Camera? {
        try {
            Log.d(TAG, "bindCameraUseCases called - detector: ${detector != null}, isDetectionEnabled: $isDetectionEnabled")
            
            val rotation = previewView.display.rotation
            val cameraSelector = mainCameraSelector ?: CameraSelector.DEFAULT_BACK_CAMERA

            preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .build()

            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(previewView.display.rotation)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
                if (!isDetectionEnabled) {
                    Log.d(TAG, "Detection disabled, skipping analysis")
                    imageProxy.close()
                    return@setAnalyzer
                }

                val mediaImage = imageProxy.image
                if (mediaImage != null && detector != null) {
                    try {
                        Log.d(TAG, "Processing image for detection")
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

                        Log.d(TAG, "Calling detector.detect()")
                        detector.detect(bitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in image analysis: ${e.message}")
                    }
                } else {
                    Log.d(TAG, "Skipping detection - mediaImage: ${mediaImage != null}, detector: ${detector != null}")
                }
                imageProxy.close()
            }

            cameraProvider.unbindAll()

            camera = cameraProvider.bindToLifecycle(
                context as androidx.lifecycle.LifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.setSurfaceProvider(previewView.surfaceProvider)

            // Update zoom state after camera is bound
            camera?.cameraControl?.setZoomRatio(zoomRatio)
            camera?.cameraInfo?.zoomState?.observe(context as androidx.lifecycle.LifecycleOwner) { zoomState ->
                zoomState?.let {
                    minZoomRatio = it.minZoomRatio
                    maxZoomRatio = it.maxZoomRatio
                    updateZoomButtonStates()
                    updateZoomSlider()
                }
            }

            updateCameraIdDisplay()
            return camera

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            showErrorDialog("Error configurando la cámara", exc.message ?: "Error desconocido")
            return null
        }
    }

    // Zoom methods
    fun setZoomRatio(ratio: Float) {
        try {
            Log.d(TAG, "setZoomRatio called with: $ratio")
            
            val newZoomRatio = ratio.coerceIn(minZoomRatio, maxZoomRatio)
            zoomRatio = newZoomRatio

            // Check if we need to switch cameras BEFORE setting zoom
            val needsCameraSwitch = checkAndSwitchCameraIfNeeded()
            
            if (needsCameraSwitch) {
                // Camera will be switched, zoom will be set after rebind
                Log.d(TAG, "Camera switch needed, zoom will be set after rebind")
            } else {
                // No camera switch needed, set zoom directly
                camera?.cameraControl?.setZoomRatio(newZoomRatio)
            }

            updateZoomButtonStates()
            updateZoomSlider()
            
            zoomListener?.onZoomChanged(newZoomRatio)

        } catch (e: Exception) {
            Log.e(TAG, "Error setting zoom ratio: ${e.message}")
        }
    }

    private fun checkAndSwitchCameraIfNeeded(): Boolean {
        val switchedToUltraWide = switchToUltraWideCameraIfNeeded()
        val switchedToMain = switchToMainCameraIfNeeded()
        
        Log.d(TAG, "Camera switch check - Ultra-wide: $switchedToUltraWide, Main: $switchedToMain")
        
        if (switchedToUltraWide || switchedToMain) {
            Log.d(TAG, "Camera switch needed, notifying listener")
            zoomListener?.onCameraSwitchNeeded()
            return true
        }
        
        return false
    }

    private fun switchToUltraWideCamera() {
        try {
            if (ultraWideCameraSelector == null) {
                Log.d(TAG, "Ultra-wide selector is null, cannot switch")
                return
            }

            cameraProvider.unbindAll()

            val rotation = previewView.display.rotation

            preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .build()

            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(previewView.display.rotation)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
                if (!isDetectionEnabled) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val mediaImage = imageProxy.image
                if (mediaImage != null && detector != null) {
                    try {
                        var bitmap = mediaImageToBitmap(mediaImage)
                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        val matrix = Matrix().apply {
                            postRotate(rotationDegrees.toFloat())
                            if (isFrontCamera) {
                                postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                            }
                        }
                        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        detector.detect(bitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in image analysis: ${e.message}")
                    }
                }
                imageProxy.close()
            }

            camera = cameraProvider.bindToLifecycle(
                context as androidx.lifecycle.LifecycleOwner,
                ultraWideCameraSelector!!,
                preview,
                imageAnalyzer
            )

            preview?.setSurfaceProvider(previewView.surfaceProvider)
            updateCameraIdDisplay()

            Log.d(TAG, "Successfully switched to ultra-wide camera")
            zoomListener?.onCameraSwitched(ultraWideCameraId ?: "Unknown")

        } catch (e: Exception) {
            Log.e(TAG, "Error switching to ultra-wide camera: ${e.message}")
        }
    }

    private fun switchToMainCamera() {
        try {
            if (mainCameraSelector == null) {
                Log.d(TAG, "Main camera selector is null, cannot switch")
                return
            }

            cameraProvider.unbindAll()

            val rotation = previewView.display.rotation

            preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .build()

            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(previewView.display.rotation)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
                if (!isDetectionEnabled) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val mediaImage = imageProxy.image
                if (mediaImage != null && detector != null) {
                    try {
                        var bitmap = mediaImageToBitmap(mediaImage)
                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        val matrix = Matrix().apply {
                            postRotate(rotationDegrees.toFloat())
                            if (isFrontCamera) {
                                postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                            }
                        }
                        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        detector.detect(bitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in image analysis: ${e.message}")
                    }
                }
                imageProxy.close()
            }

            camera = cameraProvider.bindToLifecycle(
                context as androidx.lifecycle.LifecycleOwner,
                mainCameraSelector!!,
                preview,
                imageAnalyzer
            )

            preview?.setSurfaceProvider(previewView.surfaceProvider)
            updateCameraIdDisplay()

            Log.d(TAG, "Successfully switched to main camera")
            zoomListener?.onCameraSwitched(mainCameraId ?: "Unknown")

        } catch (e: Exception) {
            Log.e(TAG, "Error switching to main camera: ${e.message}")
        }
    }

    private fun updateZoomButtonStates() {
        try {
            // Update 0.5x button
            val canUse05x = ultraWideCameraSelector != null
            zoom05x?.isEnabled = canUse05x
            zoom05x?.alpha = if (canUse05x) 1.0f else 0.5f

            // Update button selection states - ensure only one is selected
            val is05xSelected = zoomRatio <= 0.6f
            val is1xSelected = zoomRatio >= 0.8f && zoomRatio <= 1.2f

            // Ensure mutual exclusivity - only one button can be selected
            if (is05xSelected) {
                zoom05x?.isSelected = true
                zoom1x?.isSelected = false
            } else if (is1xSelected) {
                zoom05x?.isSelected = false
                zoom1x?.isSelected = true
            } else {
                // Neither is selected (intermediate zoom levels)
                zoom05x?.isSelected = false
                zoom1x?.isSelected = false
            }

            // Update text colors based on selection state
            zoom05x?.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (zoom05x?.isSelected == true) R.color.zoom_button_selected_text else R.color.zoom_button_normal_text
                )
            )

            zoom1x?.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (zoom1x?.isSelected == true) R.color.zoom_button_selected_text else R.color.zoom_button_normal_text
                )
            )

            Log.d(TAG, "🔘 Zoom button states updated:")
            Log.d(TAG, "   - Current zoomRatio: $zoomRatio")
            Log.d(TAG, "   - 0.5x selected: ${zoom05x?.isSelected} (should be: $is05xSelected)")
            Log.d(TAG, "   - 1x selected: ${zoom1x?.isSelected} (should be: $is1xSelected)")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating zoom button states: ${e.message}")
        }
    }

    private fun updateZoomSlider() {
        try {
            val progress = ((zoomRatio - minZoomRatio) / (maxZoomRatio - minZoomRatio) * 100).toInt()
            zoomSlider?.progress = progress.coerceIn(0, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating zoom slider: ${e.message}")
        }
    }

    private fun updateCameraIdDisplay() {
        try {
            val currentCameraId = getCurrentCameraId()
            cameraIdDisplay?.text = "Camera ID: $currentCameraId"
        } catch (e: Exception) {
            Log.e(TAG, "Error updating camera ID display: ${e.message}")
        }
    }

    private fun getCurrentCameraId(): String {
        return try {
            val camera2Info = Camera2CameraInfo.from(camera?.cameraInfo ?: return "Unknown")
            camera2Info.cameraId
        } catch (e: Exception) {
            "Unknown"
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
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun showErrorDialog(title: String, message: String) {
        try {
            AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing error dialog: ${e.message}")
        }
    }

    fun onTouchEvent(event: MotionEvent?): Boolean {
        return if (::scaleGestureDetector.isInitialized && ::gestureDetector.isInitialized && event != null) {
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
        } else {
            false
        }
    }

    fun updateDetectionCount(count: Int) {
        detectionCountValue = count
        detectionCount?.text = "Detections: $detectionCountValue"
        zoomListener?.onDetectionCountChanged(count)
    }

    fun setDetectionEnabled(enabled: Boolean) {
        isDetectionEnabled = enabled
    }

    fun getCurrentZoomRatio(): Float = zoomRatio

    fun getMinZoomRatio(): Float = minZoomRatio

    fun getMaxZoomRatio(): Float = maxZoomRatio

    fun cleanup() {
        try {
            cameraProvider.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}")
        }
    }

    fun getCurrentCameraSelector(): CameraSelector {
        return currentCameraSelector ?: CameraSelector.DEFAULT_BACK_CAMERA
    }

    fun getPreview(): Preview? {
        return preview
    }

    fun updateZoomState(minZoom: Float, maxZoom: Float) {
        minZoomRatio = minZoom
        maxZoomRatio = maxZoom
        updateZoomButtonStates()
        updateZoomSlider()
    }

    fun switchToUltraWideCameraIfNeeded(): Boolean {
        Log.d(TAG, "Checking ultra-wide switch - zoomRatio: $zoomRatio, ultraWideSelector: ${ultraWideCameraSelector != null}")
        Log.d(TAG, "Ultra-wide camera ID: $ultraWideCameraId, Main camera ID: $mainCameraId")
        
        if (zoomRatio <= 0.6f) {
            if (ultraWideCameraSelector != null) {
                Log.d(TAG, "Switching to ultra-wide camera for 0.5x zoom")
                currentCameraSelector = ultraWideCameraSelector
                return true
            } else {
                Log.d(TAG, "Ultra-wide camera not available, staying with current camera")
                return false
            }
        } else {
            Log.d(TAG, "No ultra-wide switch needed - zoomRatio: $zoomRatio > 0.6")
            return false
        }
    }

    fun switchToMainCameraIfNeeded(): Boolean {
        return if (zoomRatio >= 0.8f && mainCameraSelector != null) {
            Log.d(TAG, "Switching to main camera for 1x+ zoom")
            currentCameraSelector = mainCameraSelector
            true
        } else {
            false
        }
    }

    // Test method to force ultra-wide switch
    fun forceSwitchToUltraWide(): Boolean {
        Log.d(TAG, "🔧 FORCING switch to ultra-wide camera")
        if (ultraWideCameraSelector != null) {
            currentCameraSelector = ultraWideCameraSelector
            Log.d(TAG, "✅ Ultra-wide selector set successfully")
            return true
        } else {
            Log.d(TAG, "❌ Ultra-wide selector is null, cannot switch")
            return false
        }
    }
}
