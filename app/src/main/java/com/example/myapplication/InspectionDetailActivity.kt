package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class InspectionDetailActivity : AppCompatActivity() {
    
    private lateinit var imageView: ImageView
    private lateinit var estadoTextView: TextView
    private lateinit var comentariosTextView: TextView
    private lateinit var retakePhotoButton: MaterialButton
    private var inspectionData: InspectionData? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspection_detail)
        
        // Setup toolbar
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            finish()
        }
        
        // Initialize views
        imageView = findViewById(R.id.inspection_image)
        estadoTextView = findViewById(R.id.estado_inspeccion)
        comentariosTextView = findViewById(R.id.comentarios_inspeccion)
        retakePhotoButton = findViewById(R.id.retake_photo_button)
        
        // Get inspection data from intent
        inspectionData = intent.getSerializableExtra("inspectionData") as? InspectionData
        val isProcessing = intent.getBooleanExtra("isProcessing", false)
        val storagePath = intent.getStringExtra("storagePath")
        val uploadedPublicUrl = intent.getStringExtra("uploadedPublicUrl")
        
        if (inspectionData != null) {
            // Has inspection data
            if (isProcessing) {
                loadInspectionDataProcessing(inspectionData!!, storagePath, uploadedPublicUrl)
            } else {
                loadInspectionData(inspectionData!!)
                setupRetakeButton()
            }
        } else {
            // Empty state - no inspection data, but check if there's processing in progress
            val slot = intent.getStringExtra("slot")
            if (slot != null && hasProcessingInProgress(slot)) {
                // There's processing in progress, restore the processing state
                restoreProcessingState(slot)
            } else {
                loadEmptyState()
            }
        }
    }
    
    private fun loadEmptyState() {
        try {
            // Set title
            supportActionBar?.title = "Capturar Inspección"
            
            // Show empty state image
            imageView.setImageResource(R.drawable.ic_camera_placeholder)
            
            // Show empty state text
            estadoTextView.text = "Sin inspección"
            estadoTextView.setTextColor(0xFF757575.toInt())
            comentariosTextView.text = "Toca el botón para capturar una foto y comenzar la inspección"
            comentariosTextView.setTextColor(0xFF757575.toInt())
            
            // Show capture button instead of retake button
            retakePhotoButton.text = "📷 Capturar Foto"
            retakePhotoButton.visibility = android.view.View.VISIBLE
            retakePhotoButton.setOnClickListener {
                launchCameraFromEmptyState()
            }
            
            Log.d("InspectionDetailActivity", "Loaded empty state")
            
        } catch (e: Exception) {
            Log.e("InspectionDetailActivity", "Error loading empty state: ${e.message}")
        }
    }
    
    private fun launchCameraFromEmptyState() {
        try {
            val slot = intent.getStringExtra("slot") ?: return
            val inspectionViewId = intent.getIntExtra("inspectionViewId", -1)
            val inspectionViewDescription = intent.getStringExtra("inspectionViewDescription") ?: "Captura de inspección"
            val cameraPosition = intent.getStringExtra("cameraPosition")
            
            // Clear ALL data for this slot to start completely fresh
            clearAllDataForSlot(slot)
            
            // Notify InspectionActivity to clear slot data as well
            val resultIntent = Intent().apply {
                putExtra("action", "clear_slot_data")
                putExtra("slot", slot)
            }
            setResult(android.app.Activity.RESULT_OK, resultIntent)
            
            // Launch camera to capture photo
            val cameraIntent = Intent(this, LoadingActivity::class.java)
            cameraIntent.putExtra("captureMode", true)
            cameraIntent.putExtra("slot", slot)
            cameraIntent.putExtra("inspectionViewId", inspectionViewId)
            cameraIntent.putExtra("inspectionViewDescription", inspectionViewDescription)
            cameraIntent.putExtra("cameraPosition", cameraPosition)
            
            Log.d("InspectionDetailActivity", "Launching camera from empty state for slot=$slot")
            startActivityForResult(cameraIntent, 3000) // Use specific requestCode for empty state capture
        } catch (e: Exception) {
            Log.e("InspectionDetailActivity", "Error launching camera from empty state: ${e.message}")
        }
    }
    
    private fun loadInspectionDataProcessing(inspectionData: InspectionData, storagePath: String?, uploadedPublicUrl: String?) {
        try {
            // Set title
            supportActionBar?.title = "Procesando Inspección"
            
            // Load image
            Glide.with(this)
                .load(inspectionData.imageUrl)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .error(R.drawable.ic_camera_placeholder)
                .placeholder(R.drawable.ic_camera_placeholder)
                .into(imageView)
            
            // Show processing status
            estadoTextView.text = "Procesando..."
            estadoTextView.setTextColor(0xFF1976D2.toInt())
            comentariosTextView.text = "Ejecutando validaciones en background..."
            comentariosTextView.setTextColor(0xFF1976D2.toInt())
            
            // Hide retake button during processing
            retakePhotoButton.visibility = android.view.View.GONE
            
            // Start polling for completion
            if (storagePath != null && uploadedPublicUrl != null) {
                startPollingForCompletion(inspectionData.slot, storagePath, uploadedPublicUrl, inspectionData.inspectionViewId)
            }
            
            Log.d("InspectionDetailActivity", "Loaded processing inspection data for slot: ${inspectionData.slot}")
            
        } catch (e: Exception) {
            Log.e("InspectionDetailActivity", "Error loading processing inspection data: ${e.message}")
        }
    }
    
    private fun loadInspectionData(inspectionData: InspectionData) {
        try {
            // Set title
            supportActionBar?.title = "Detalle de Inspección"
            
            // Load image
            Glide.with(this)
                .load(inspectionData.imageUrl)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .error(R.drawable.ic_camera_placeholder)
                .placeholder(R.drawable.ic_camera_placeholder)
                .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                    override fun onLoadFailed(
                        e: com.bumptech.glide.load.engine.GlideException?,
                        model: Any?,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        Log.e("InspectionDetailActivity", "Failed to load image: ${inspectionData.imageUrl}, error: ${e?.message}")
                        return false
                    }

                    override fun onResourceReady(
                        resource: android.graphics.drawable.Drawable,
                        model: Any,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                        dataSource: com.bumptech.glide.load.DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        Log.d("InspectionDetailActivity", "Image loaded successfully: ${inspectionData.imageUrl}")
                        return false
                    }
                })
                .into(imageView)
            
            // Set estado
            estadoTextView.text = "Estado: ${inspectionData.estadoInspeccion}"
            
            // Set color based on estado
            val statusColor = when (inspectionData.estadoInspeccion.lowercase()) {
                "aprobada" -> 0xFF4CAF50.toInt() // Green
                "rechazada" -> 0xFFF44336.toInt() // Red
                else -> 0xFFFF9800.toInt() // Orange
            }
            estadoTextView.setTextColor(statusColor)
            
            // Set comentarios
            comentariosTextView.text = "Comentarios:\n${inspectionData.comentariosInspeccion}"
            
            Log.d("InspectionDetailActivity", "Loaded inspection data for slot: ${inspectionData.slot}")
            
        } catch (e: Exception) {
            Log.e("InspectionDetailActivity", "Error loading inspection data: ${e.message}")
        }
    }
    
    private fun startPollingForCompletion(slot: String, storagePath: String, uploadedPublicUrl: String, inspectionViewId: Int) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var attempts = 0
        val maxAttempts = 60 // 60 seconds max
        
        val runnable = object : Runnable {
            override fun run() {
                attempts++
                
                try {
                    val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
                    
                    // First check for progress updates
                    val progressKey = "${slot}_progress"
                    val progressMessage = sharedPref.getString(progressKey, "")
                    
                    // Debug: show all available keys
                    val allPrefKeys = sharedPref.all.keys
                    val progressKeys = allPrefKeys.filter { it.contains("_progress") }
                    Log.d("InspectionDetailActivity", "Polling attempt $attempts: reading key '$progressKey', found: '$progressMessage'")
                    Log.d("InspectionDetailActivity", "Available progress keys: $progressKeys")
                    Log.d("InspectionDetailActivity", "All SharedPreferences keys: $allPrefKeys")
                    
                    runOnUiThread {
                        if (progressMessage != null && progressMessage.isNotEmpty()) {
                            // Show specific precheck progress
                            comentariosTextView.text = progressMessage
                            Log.d("InspectionDetailActivity", "Updated progress: $progressMessage")
                        } else {
                            // Show generic progress with timer
                            val progressMsg = "Ejecutando validaciones en background... (${attempts}s)"
                            comentariosTextView.text = progressMsg
                            Log.d("InspectionDetailActivity", "No progress message found, showing generic: $progressMsg")
                        }
                    }
                    
                    // Then check if inspection data is now available
                    val slotKeys = allPrefKeys.filter { it.startsWith("${slot}_") && it.endsWith("_slot") }
                    
                    if (slotKeys.isNotEmpty()) {
                        // Get the most recent one
                        val latestKey = slotKeys.maxByOrNull { key ->
                            val timestampKey = key.replace("_slot", "_timestamp")
                            sharedPref.getLong(timestampKey, 0L)
                        }
                        
                        if (latestKey != null) {
                            val baseKey = latestKey.replace("_slot", "")
                            val estadoInspeccion = sharedPref.getString("${baseKey}_estadoInspeccion", "")
                            val comentariosInspeccion = sharedPref.getString("${baseKey}_comentariosInspeccion", "")
                            
                            if (estadoInspeccion != null && estadoInspeccion.isNotEmpty() && 
                                comentariosInspeccion != null && comentariosInspeccion.isNotEmpty() &&
                                estadoInspeccion != "Procesando...") {
                                
                                // Processing completed!
                                runOnUiThread {
                                    supportActionBar?.title = "Detalle de Inspección"
                                    estadoTextView.text = "Estado: $estadoInspeccion"
                                    comentariosTextView.text = comentariosInspeccion
                                    
                                    // Set colors based on status
                                    val statusColor = when (estadoInspeccion.lowercase()) {
                                        "aprobada" -> 0xFF4CAF50.toInt() // Green
                                        "rechazada" -> 0xFFF44336.toInt() // Red
                                        else -> 0xFFFF9800.toInt() // Orange
                                    }
                                    estadoTextView.setTextColor(statusColor)
                                    comentariosTextView.setTextColor(0xFF212121.toInt())
                                    
                                    // Show retake button
                                    retakePhotoButton.visibility = android.view.View.VISIBLE
                                    setupRetakeButton()
                                    
                                    // Clean up progress data
                                    sharedPref.edit().remove(progressKey).apply()
                                    
                                    Log.d("InspectionDetailActivity", "Processing completed for slot: $slot, estado: $estadoInspeccion")
                                }
                                return
                            }
                        }
                    }
                    
                    // Continue polling if not max attempts reached
                    if (attempts < maxAttempts) {
                        handler.postDelayed(this, 500) // Check every 500ms for more responsive updates
                    } else {
                        runOnUiThread {
                            estadoTextView.text = "Error: Tiempo agotado"
                            estadoTextView.setTextColor(0xFFF44336.toInt())
                            comentariosTextView.text = "Las validaciones tardaron demasiado tiempo. Intenta nuevamente."
                            retakePhotoButton.visibility = android.view.View.VISIBLE
                            setupRetakeButton()
                            
                            // Clean up progress data
                            sharedPref.edit().remove(progressKey).apply()
                        }
                        Log.w("InspectionDetailActivity", "Polling timeout for slot: $slot")
                    }
                    
                } catch (e: Exception) {
                    Log.e("InspectionDetailActivity", "Error in polling: ${e.message}")
                    if (attempts < maxAttempts) {
                        handler.postDelayed(this, 1000)
                    }
                }
            }
        }
        
        // Start polling immediately
        handler.post(runnable)
    }
    
    private fun startPollingForProgress(slot: String) {
        Log.d("InspectionDetailActivity", "startPollingForProgress called for slot: $slot")
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var attempts = 0
        val maxAttempts = 60 // 60 seconds max
        
        val runnable = object : Runnable {
            override fun run() {
                attempts++
                
                try {
                    val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
                    
                    // Check for progress updates
                    val progressKey = "${slot}_progress"
                    val progressMessage = sharedPref.getString(progressKey, "")
                    
                    Log.d("InspectionDetailActivity", "Polling attempt $attempts: reading key '$progressKey', found: '$progressMessage'")
                    
                    runOnUiThread {
                        if (progressMessage != null && progressMessage.isNotEmpty()) {
                            // Show specific precheck progress
                            comentariosTextView.text = progressMessage
                            Log.d("InspectionDetailActivity", "Updated progress: $progressMessage")
                        } else {
                            // Show generic progress with timer
                            val progressMsg = "Ejecutando validaciones en background... (${attempts}s)"
                            comentariosTextView.text = progressMsg
                            Log.d("InspectionDetailActivity", "No progress message found, showing generic: $progressMsg")
                        }
                    }
                    
                    // Check if inspection data is now available
                    val allKeys = sharedPref.all.keys
                    val slotKeys = allKeys.filter { it.startsWith("${slot}_") && it.endsWith("_slot") }
                    
                    if (slotKeys.isNotEmpty()) {
                        // Get the most recent one
                        val latestKey = slotKeys.maxByOrNull { key ->
                            val timestampKey = key.replace("_slot", "_timestamp")
                            sharedPref.getLong(timestampKey, 0L)
                        }
                        
                        if (latestKey != null) {
                            val baseKey = latestKey.replace("_slot", "")
                            val estadoInspeccion = sharedPref.getString("${baseKey}_estadoInspeccion", "")
                            val comentariosInspeccion = sharedPref.getString("${baseKey}_comentariosInspeccion", "")
                            
                            if (estadoInspeccion != null && estadoInspeccion.isNotEmpty() && 
                                comentariosInspeccion != null && comentariosInspeccion.isNotEmpty() &&
                                estadoInspeccion != "Procesando...") {
                                
                                // Processing completed!
                                runOnUiThread {
                                    supportActionBar?.title = "Detalle de Inspección"
                                    estadoTextView.text = "Estado: $estadoInspeccion"
                                    comentariosTextView.text = comentariosInspeccion
                                    
                                    // Set colors based on status
                                    val statusColor = when (estadoInspeccion.lowercase()) {
                                        "aprobada" -> 0xFF4CAF50.toInt() // Green
                                        "rechazada" -> 0xFFF44336.toInt() // Red
                                        else -> 0xFFFF9800.toInt() // Orange
                                    }
                                    estadoTextView.setTextColor(statusColor)
                                    comentariosTextView.setTextColor(0xFF212121.toInt())
                                    
                                    // Show retake button
                                    retakePhotoButton.visibility = android.view.View.VISIBLE
                                    setupRetakeButton()
                                    
                                    // Clean up progress data
                                    sharedPref.edit().remove(progressKey).apply()
                                    
                                    Log.d("InspectionDetailActivity", "Processing completed for slot: $slot, estado: $estadoInspeccion")
                                }
                                return
                            }
                        }
                    }
                    
                    // Continue polling if not max attempts reached
                    if (attempts < maxAttempts) {
                        handler.postDelayed(this, 500) // Check every 500ms for more responsive updates
                    } else {
                        runOnUiThread {
                            estadoTextView.text = "Error: Tiempo agotado"
                            estadoTextView.setTextColor(0xFFF44336.toInt())
                            comentariosTextView.text = "Las validaciones tardaron demasiado tiempo. Intenta nuevamente."
                            retakePhotoButton.visibility = android.view.View.VISIBLE
                            setupRetakeButton()
                            
                            // Clean up progress data
                            sharedPref.edit().remove(progressKey).apply()
                        }
                        Log.w("InspectionDetailActivity", "Polling timeout for slot: $slot")
                    }
                    
                } catch (e: Exception) {
                    Log.e("InspectionDetailActivity", "Error in polling: ${e.message}")
                    if (attempts < maxAttempts) {
                        handler.postDelayed(this, 1000)
                    }
                }
            }
        }
        
        // Start polling immediately
        handler.post(runnable)
    }

    private fun startPrechecksInBackground(slot: String, storagePath: String, uploadedPublicUrl: String, inspectionViewId: Int) {
        kotlinx.coroutines.GlobalScope.launch {
            try {
                Log.d("InspectionDetailActivity", "Starting prechecks in background for slot: $slot")
                
                val prechecks = com.example.myapplication.SupabaseClientProvider.getPrechecksForInspectionView(inspectionViewId)
                Log.d("InspectionDetailActivity", "Found ${prechecks.size} prechecks for inspectionViewId=$inspectionViewId")
                
                if (prechecks.isEmpty()) {
                    runOnUiThread {
                        estadoTextView.text = "Estado: Aprobada"
                        estadoTextView.setTextColor(0xFF4CAF50.toInt())
                        comentariosTextView.text = "Sin validaciones requeridas"
                        comentariosTextView.setTextColor(0xFF212121.toInt())
                        retakePhotoButton.visibility = android.view.View.VISIBLE
                        setupRetakeButton()
                    }
                    return@launch
                }

                // Sort prechecks by order column
                val sortedPrechecks = prechecks.sortedBy { it.order }
                Log.d("InspectionDetailActivity", "Sorted prechecks by order: $sortedPrechecks")
                
                var inspectionDataHandled = false
                
                for (i in sortedPrechecks.indices) {
                    val p = sortedPrechecks[i]
                    val isLastPrecheck = (i == sortedPrechecks.size - 1)
                    
                    runOnUiThread {
                        val stepMsg = "Validando paso ${i + 1} de ${sortedPrechecks.size} (Order: ${p.order})"
                        val detailMsg = "Validando ${p.name}"
                        
                        comentariosTextView.text = detailMsg
                        
                        // Save progress to SharedPreferences for persistence
                        saveProgressToSharedPrefs(slot, detailMsg)
                        
                        Log.d("InspectionDetailActivity", "Updated progress for slot $slot: $stepMsg, detail: $detailMsg")
                    }
                    
                    val bodyJson = org.json.JSONObject().apply {
                        put("imageUrl", storagePath)
                        put("responseValue", p.responseValue)
                    }.toString()
                    
                    val responseText = com.example.myapplication.SupabaseClientProvider.postJson(p.url, bodyJson)
                    
                    val success = try {
                        val responseJson = org.json.JSONObject(responseText)
                        responseJson.getBoolean("success")
                    } catch (e: Exception) {
                        Log.e("InspectionDetailActivity", "Error parsing response JSON: ${e.message}")
                        false
                    }
                    
                    // Check if this is the last precheck and has inspection data
                    var inspectionData: org.json.JSONObject? = null
                    if (isLastPrecheck && success) {
                        try {
                            val responseJson = org.json.JSONObject(responseText)
                            if (responseJson.has("estado_inspeccion") && responseJson.has("comentarios_inspeccion")) {
                                inspectionData = responseJson
                                Log.d("InspectionDetailActivity", "Inspection data found: ${inspectionData.toString()}")
                            }
                        } catch (e: Exception) {
                            Log.e("InspectionDetailActivity", "Error parsing inspection data: ${e.message}")
                        }
                    }
                    
                    if (!success && !isLastPrecheck) {
                        runOnUiThread {
                            // Save failed precheck result as inspection data (don't delete image)
                            val failedInspectionData = InspectionData(
                                imageUrl = uploadedPublicUrl,
                                estadoInspeccion = "Rechazada",
                                comentariosInspeccion = p.errorMessage,
                                inspectionViewId = inspectionViewId,
                                timestamp = System.currentTimeMillis(),
                                slot = slot
                            )
                            saveInspectionDataToSharedPrefs(failedInspectionData)
                            
                            estadoTextView.text = "Estado: Rechazada"
                            estadoTextView.setTextColor(0xFFF44336.toInt())
                            comentariosTextView.text = p.errorMessage
                            comentariosTextView.setTextColor(0xFFF44336.toInt())
                            retakePhotoButton.visibility = android.view.View.VISIBLE
                            setupRetakeButton()
                            
                            Log.d("InspectionDetailActivity", "Precheck failed for slot: $slot, saving as rejected inspection data")
                        }
                        return@launch
                    }
                    
                    // Handle inspection data if present (last precheck with data)
                    if (isLastPrecheck && inspectionData != null) {
                        inspectionDataHandled = true
                        runOnUiThread {
                            try {
                                val estadoInspeccion = inspectionData.getString("estado_inspeccion")
                                val comentariosInspeccion = inspectionData.getString("comentarios_inspeccion")
                                
                                // Save inspection data locally
                                val inspectionDataObj = InspectionData(
                                    imageUrl = uploadedPublicUrl,
                                    estadoInspeccion = estadoInspeccion,
                                    comentariosInspeccion = comentariosInspeccion,
                                    inspectionViewId = inspectionViewId,
                                    timestamp = System.currentTimeMillis(),
                                    slot = slot
                                )
                                saveInspectionDataToSharedPrefs(inspectionDataObj)
                                
                                // Update UI with inspection status
                                supportActionBar?.title = "Detalle de Inspección"
                                estadoTextView.text = "Estado: $estadoInspeccion"
                                comentariosTextView.text = comentariosInspeccion
                                
                                // Set colors based on status
                                val statusColor = when (estadoInspeccion.lowercase()) {
                                    "aprobada" -> 0xFF4CAF50.toInt() // Green
                                    "rechazada" -> 0xFFF44336.toInt() // Red
                                    else -> 0xFFFF9800.toInt() // Orange
                                }
                                estadoTextView.setTextColor(statusColor)
                                comentariosTextView.setTextColor(0xFF212121.toInt())
                                
                                // Show retake button
                                retakePhotoButton.visibility = android.view.View.VISIBLE
                                setupRetakeButton()
                                
                                // Clear progress since inspection is completed
                                clearProgressFromSharedPrefs(slot)
                                clearProcessingStateFromSharedPrefs(slot)
                                
                                Log.d("InspectionDetailActivity", "Inspection data handled for slot: $slot, estado: $estadoInspeccion")
                            } catch (e: Exception) {
                                Log.e("InspectionDetailActivity", "Error handling inspection data: ${e.message}")
                            }
                        }
                        return@launch
                    }
                }
                
                // Only execute normal success flow if no inspection data was handled
                if (!inspectionDataHandled) {
                    runOnUiThread {
                        estadoTextView.text = "Estado: Aprobada"
                        estadoTextView.setTextColor(0xFF4CAF50.toInt())
                        comentariosTextView.text = "Validado exitosamente"
                        comentariosTextView.setTextColor(0xFF212121.toInt())
                        retakePhotoButton.visibility = android.view.View.VISIBLE
                        setupRetakeButton()
                        
                        // Clear progress since inspection is completed
                        clearProgressFromSharedPrefs(slot)
                        clearProcessingStateFromSharedPrefs(slot)
                        
                        Log.d("InspectionDetailActivity", "No inspection data handled → normal success flow for slot=$slot")
                    }
                }
                
            } catch (e: Exception) {
                Log.e("InspectionDetailActivity", "Precheck error: ${e.message}", e)
                runOnUiThread {
                    estadoTextView.text = "Error en validación"
                    estadoTextView.setTextColor(0xFFF44336.toInt())
                    comentariosTextView.text = "Error en validación: ${e.message}"
                    comentariosTextView.setTextColor(0xFFF44336.toInt())
                    retakePhotoButton.visibility = android.view.View.VISIBLE
                    setupRetakeButton()
                    
                    // Clear progress since there was an error
                    clearProgressFromSharedPrefs(slot)
                    clearProcessingStateFromSharedPrefs(slot)
                }
            }
        }
    }
    
    private fun saveInspectionDataToSharedPrefs(inspectionData: InspectionData) {
        try {
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            val editor = sharedPref.edit()
            val timestamp = System.currentTimeMillis()
            val baseKey = "${inspectionData.slot}_$timestamp"
            
            editor.putString("${baseKey}_slot", inspectionData.slot)
            editor.putString("${baseKey}_imageUrl", inspectionData.imageUrl)
            editor.putString("${baseKey}_estadoInspeccion", inspectionData.estadoInspeccion)
            editor.putString("${baseKey}_comentariosInspeccion", inspectionData.comentariosInspeccion)
            editor.putInt("${baseKey}_inspectionViewId", inspectionData.inspectionViewId)
            editor.putLong("${baseKey}_timestamp", timestamp)
            
            editor.apply()
            Log.d("InspectionDetailActivity", "Saved inspection data for slot: ${inspectionData.slot}")
        } catch (e: Exception) {
            Log.e("InspectionDetailActivity", "Error saving inspection data: ${e.message}")
        }
    }

    

    private fun setupRetakeButton() {
        retakePhotoButton.setOnClickListener {
            try {
                // Get slot information from multiple sources
                val slot = inspectionData?.slot ?: intent.getStringExtra("slot")
                val inspectionViewId = inspectionData?.inspectionViewId ?: intent.getIntExtra("inspectionViewId", -1)
                
                if (slot != null && inspectionViewId > 0) {
                    Log.d("InspectionDetailActivity", "Setting up retake button for slot: $slot, inspectionViewId: $inspectionViewId")
                    
                    // Clear ALL data for this slot to start completely fresh
                    clearAllDataForSlot(slot)
                    
                    // Notify InspectionActivity to clear slot data as well
                    val resultIntent = Intent().apply {
                        putExtra("action", "clear_slot_data")
                        putExtra("slot", slot)
                    }
                    setResult(android.app.Activity.RESULT_OK, resultIntent)
                    
                    // Launch camera to retake photo
                    val cameraIntent = Intent(this, LoadingActivity::class.java)
                    cameraIntent.putExtra("captureMode", true)
                    cameraIntent.putExtra("slot", slot)
                    cameraIntent.putExtra("inspectionViewId", inspectionViewId)
                    cameraIntent.putExtra("inspectionViewDescription", "Re-captura de inspección")
                    cameraIntent.putExtra("cameraPosition", null as String?) // Will be fetched from InspectionView
                    
                    Log.d("InspectionDetailActivity", "Launching retake capture for slot=$slot")
                    startActivityForResult(cameraIntent, 3000) // Use specific requestCode for retake from detail view
                } else {
                    Log.e("InspectionDetailActivity", "Cannot setup retake button: slot=$slot, inspectionViewId=$inspectionViewId")
                    android.widget.Toast.makeText(this, "Error: No se puede obtener información del slot", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("InspectionDetailActivity", "Error in setupRetakeButton: ${e.message}")
                android.widget.Toast.makeText(this, "Error al configurar botón de retake: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == 3000 || requestCode == 3001) {
            Log.d("InspectionDetailActivity", "Received result from camera: resultCode=$resultCode")
            Log.d("InspectionDetailActivity", "Data extras: ${data?.extras}")
            
            if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                val uploadedUrl = data.getStringExtra("uploadedUrl")
                val storagePath = data.getStringExtra("storagePath")
                val slot = data.getStringExtra("slot")
                val inspectionViewId = data.getIntExtra("inspectionViewId", -1)
                
                if (uploadedUrl != null && storagePath != null && slot != null) {
                    // Switch to processing state
                    switchToProcessingState(uploadedUrl, storagePath, slot, inspectionViewId)
                    
                    // Start prechecks in background by calling InspectionActivity
                    val resultIntent = Intent().apply {
                        putExtra("uploadedUrl", uploadedUrl)
                        putExtra("slot", slot)
                        putExtra("storagePath", storagePath)
                        putExtra("inspectionViewId", inspectionViewId)
                        putExtra("startPrechecks", true) // Flag to start prechecks
                    }
                    setResult(android.app.Activity.RESULT_OK, resultIntent)
                    
                    Log.d("InspectionDetailActivity", "Switched to processing state, will start prechecks")
                    
                    // Start prechecks directly in background with progress updates
                    startPrechecksInBackground(slot, storagePath, uploadedUrl, inspectionViewId)
                } else {
                    Log.e("InspectionDetailActivity", "Missing data in camera result")
                    finish()
                }
            } else {
                Log.d("InspectionDetailActivity", "Camera result not OK or no data, finishing")
                finish()
            }
        }
    }
    
    private fun switchToProcessingState(uploadedUrl: String, storagePath: String, slot: String, inspectionViewId: Int) {
        try {
            // Save processing state to SharedPreferences
            saveProcessingStateToSharedPrefs(slot, uploadedUrl, inspectionViewId)
            
            // Update title
            supportActionBar?.title = "Procesando Inspección"
            
            // Load the captured image
            Glide.with(this)
                .load(uploadedUrl)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .error(R.drawable.ic_camera_placeholder)
                .placeholder(R.drawable.ic_camera_placeholder)
                .into(imageView)
            
            // Show processing status
            estadoTextView.text = "Procesando..."
            estadoTextView.setTextColor(0xFF1976D2.toInt())
            
            // Try to load saved progress first
            val savedProgress = loadProgressFromSharedPrefs(slot)
            if (savedProgress != null && savedProgress.isNotEmpty()) {
                comentariosTextView.text = savedProgress
                Log.d("InspectionDetailActivity", "Loaded saved progress for slot $slot: $savedProgress")
            } else {
                comentariosTextView.text = "Ejecutando validaciones en background..."
            }
            comentariosTextView.setTextColor(0xFF1976D2.toInt())
            
            // Hide capture button during processing
            retakePhotoButton.visibility = android.view.View.GONE
            
            // Prechecks will be started directly from onActivityResult
            
            Log.d("InspectionDetailActivity", "Switched to processing state for slot: $slot")
            
        } catch (e: Exception) {
            Log.e("InspectionDetailActivity", "Error switching to processing state: ${e.message}")
        }
    }
    
    private fun deleteInspectionDataForSlot(slot: String) {
        try {
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            val allKeys = sharedPref.all.keys
            val slotKeys = allKeys.filter { it.startsWith("${slot}_") }
            
            val editor = sharedPref.edit()
            slotKeys.forEach { key ->
                editor.remove(key)
            }
            editor.apply()
            
            Log.d("InspectionDetailActivity", "Deleted inspection data for slot: $slot")
        } catch (e: Exception) {
            Log.e("InspectionDetailActivity", "Error deleting inspection data: ${e.message}")
        }
    }
    
    private fun saveProgressToSharedPrefs(slot: String, progressMessage: String) {
        try {
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            val editor = sharedPref.edit()
            val key = "${slot}_progress"
            
            Log.d("InspectionDetailActivity", "Saving progress - slot: '$slot', key: '$key', message: '$progressMessage'")
            
            editor.putString(key, progressMessage)
            editor.putLong("${key}_timestamp", System.currentTimeMillis())
            val success = editor.commit() // Use commit() for immediate writing
            
            Log.d("InspectionDetailActivity", "Progress saved for slot $slot: $progressMessage, success: $success")
        } catch (e: Exception) {
            Log.e("InspectionDetailActivity", "Error saving progress: ${e.message}")
        }
    }
    
    private fun loadProgressFromSharedPrefs(slot: String): String? {
        try {
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            val key = "${slot}_progress"
            val progressMessage = sharedPref.getString(key, "")
            
            Log.d("InspectionDetailActivity", "Loading progress for slot '$slot', key: '$key', found: '$progressMessage'")
            
            return if (progressMessage != null && progressMessage.isNotEmpty()) {
                progressMessage
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("InspectionDetailActivity", "Error loading progress: ${e.message}")
            return null
        }
    }
    
    private fun clearProgressFromSharedPrefs(slot: String) {
        try {
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            val editor = sharedPref.edit()
            val key = "${slot}_progress"
            
            Log.d("InspectionDetailActivity", "Clearing progress for slot '$slot', key: '$key'")
            
            editor.remove(key)
            editor.remove("${key}_timestamp")
            editor.apply()
            
            Log.d("InspectionDetailActivity", "Progress cleared for slot: $slot")
        } catch (e: Exception) {
            Log.e("InspectionDetailActivity", "Error clearing progress: ${e.message}")
        }
    }
    
    private fun clearProcessingStateFromSharedPrefs(slot: String) {
        try {
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            val editor = sharedPref.edit()
            val key = "${slot}_processing"
            
            Log.d("InspectionDetailActivity", "Clearing processing state for slot '$slot', key: '$key'")
            
            editor.remove(key)
            editor.remove("${key}_inspectionViewId")
            editor.remove("${key}_timestamp")
            editor.apply()
            
            Log.d("InspectionDetailActivity", "Processing state cleared for slot: $slot")
        } catch (e: Exception) {
            Log.e("InspectionDetailActivity", "Error clearing processing state: ${e.message}")
        }
    }
    
    private fun clearAllDataForSlot(slot: String) {
        try {
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            val editor = sharedPref.edit()
            val allKeys = sharedPref.all.keys
            
            Log.d("InspectionDetailActivity", "Clearing ALL data for slot: '$slot'")
            Log.d("InspectionDetailActivity", "All keys before cleaning: $allKeys")
            
            // Find all keys that belong to this slot
            val slotKeys = allKeys.filter { it.startsWith("${slot}_") }
            
            Log.d("InspectionDetailActivity", "Keys to remove for slot '$slot': $slotKeys")
            
            // Remove all keys related to this slot
            slotKeys.forEach { key ->
                editor.remove(key)
                Log.d("InspectionDetailActivity", "Removing key: $key")
            }
            
            val success = editor.commit() // Use commit() for immediate writing
            
            Log.d("InspectionDetailActivity", "All data cleared for slot '$slot', success: $success")
            
            // Verify cleanup
            val remainingKeys = sharedPref.all.keys
            val remainingSlotKeys = remainingKeys.filter { it.startsWith("${slot}_") }
            
            if (remainingSlotKeys.isEmpty()) {
                Log.d("InspectionDetailActivity", "✅ Cleanup successful: No keys remaining for slot '$slot'")
            } else {
                Log.w("InspectionDetailActivity", "⚠️ Cleanup incomplete: Remaining keys for slot '$slot': $remainingSlotKeys")
            }
            
        } catch (e: Exception) {
            Log.e("InspectionDetailActivity", "Error clearing all data for slot '$slot': ${e.message}")
        }
    }
    
    private fun saveProcessingStateToSharedPrefs(slot: String, uploadedUrl: String, inspectionViewId: Int) {
        try {
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            val editor = sharedPref.edit()
            val key = "${slot}_processing"
            
            Log.d("InspectionDetailActivity", "Saving processing state for slot '$slot', key: '$key'")
            
            editor.putString(key, uploadedUrl)
            editor.putInt("${key}_inspectionViewId", inspectionViewId)
            editor.putLong("${key}_timestamp", System.currentTimeMillis())
            editor.apply()
            
            Log.d("InspectionDetailActivity", "Processing state saved for slot: $slot")
        } catch (e: Exception) {
            Log.e("InspectionDetailActivity", "Error saving processing state: ${e.message}")
        }
    }
    
    private fun hasProcessingInProgress(slot: String): Boolean {
        try {
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            
            // Check if there's a processing state saved
            val processingKey = "${slot}_processing"
            val processingUrl = sharedPref.getString(processingKey, "")
            val processingTimestamp = sharedPref.getLong("${processingKey}_timestamp", 0L)
            
            // Check if there's saved progress
            val progressKey = "${slot}_progress"
            val progressMessage = sharedPref.getString(progressKey, "")
            val progressTimestamp = sharedPref.getLong("${progressKey}_timestamp", 0L)
            
            val currentTime = System.currentTimeMillis()
            val processingTimeDiff = currentTime - processingTimestamp
            val progressTimeDiff = currentTime - progressTimestamp
            
            val hasProcessing = processingUrl != null && processingUrl.isNotEmpty()
            val hasProgress = progressMessage != null && progressMessage.isNotEmpty()
            val isRecent = processingTimeDiff < 5 * 60 * 1000 || progressTimeDiff < 5 * 60 * 1000 // 5 minutes
            
            Log.d("InspectionDetailActivity", "Checking processing status for slot '$slot': hasProcessing=$hasProcessing, hasProgress=$hasProgress, isRecent=$isRecent")
            Log.d("InspectionDetailActivity", "Processing timeDiff=${processingTimeDiff}ms, Progress timeDiff=${progressTimeDiff}ms")
            
            return (hasProcessing || hasProgress) && isRecent
        } catch (e: Exception) {
            Log.e("InspectionDetailActivity", "Error checking processing status: ${e.message}")
            return false
        }
    }
    
    private fun restoreProcessingState(slot: String) {
        try {
            Log.d("InspectionDetailActivity", "Restoring processing state for slot: $slot")
            
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            
            // First try to get from processing state
            val processingKey = "${slot}_processing"
            val uploadedUrl = sharedPref.getString(processingKey, "")
            val inspectionViewId = sharedPref.getInt("${processingKey}_inspectionViewId", 0)
            
            if (uploadedUrl != null && uploadedUrl.isNotEmpty()) {
                // Load the image
                Glide.with(this)
                    .load(uploadedUrl)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .error(R.drawable.ic_camera_placeholder)
                    .placeholder(R.drawable.ic_camera_placeholder)
                    .into(imageView)
                
                // Set processing state
                supportActionBar?.title = "Procesando Inspección"
                estadoTextView.text = "Procesando..."
                estadoTextView.setTextColor(0xFF1976D2.toInt())
                
                // Load saved progress
                val savedProgress = loadProgressFromSharedPrefs(slot)
                if (savedProgress != null && savedProgress.isNotEmpty()) {
                    comentariosTextView.text = savedProgress
                    Log.d("InspectionDetailActivity", "Restored saved progress: $savedProgress")
                } else {
                    comentariosTextView.text = "Ejecutando validaciones en background..."
                }
                comentariosTextView.setTextColor(0xFF1976D2.toInt())
                
                // Hide retake button during processing
                retakePhotoButton.visibility = android.view.View.GONE
                
                // Start polling for progress updates since we're in processing state
                Log.d("InspectionDetailActivity", "Starting polling for restored processing state for slot: $slot")
                startPollingForProgress(slot)
                
                Log.d("InspectionDetailActivity", "Processing state restored for slot: $slot")
                return
            }
            
            // If no processing state, try to get from inspection data
            val allKeys = sharedPref.all.keys
            val slotKeys = allKeys.filter { it.startsWith("${slot}_") && it.endsWith("_slot") }
            
            if (slotKeys.isNotEmpty()) {
                // Get the most recent one
                val latestKey = slotKeys.maxByOrNull { key ->
                    val timestampKey = key.replace("_slot", "_timestamp")
                    sharedPref.getLong(timestampKey, 0L)
                }
                
                if (latestKey != null) {
                    val baseKey = latestKey.replace("_slot", "")
                    val fallbackUrl = sharedPref.getString("${baseKey}_imageUrl", "")
                    
                    if (fallbackUrl != null && fallbackUrl.isNotEmpty()) {
                        // Load the image
                        Glide.with(this)
                            .load(fallbackUrl)
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .skipMemoryCache(true)
                            .error(R.drawable.ic_camera_placeholder)
                            .placeholder(R.drawable.ic_camera_placeholder)
                            .into(imageView)
                        
                        // Set processing state
                        supportActionBar?.title = "Procesando Inspección"
                        estadoTextView.text = "Procesando..."
                        estadoTextView.setTextColor(0xFF1976D2.toInt())
                        
                        // Load saved progress
                        val savedProgress = loadProgressFromSharedPrefs(slot)
                        if (savedProgress != null && savedProgress.isNotEmpty()) {
                            comentariosTextView.text = savedProgress
                            Log.d("InspectionDetailActivity", "Restored saved progress from fallback: $savedProgress")
                        } else {
                            comentariosTextView.text = "Ejecutando validaciones en background..."
                        }
                        comentariosTextView.setTextColor(0xFF1976D2.toInt())
                        
                        // Hide retake button during processing
                        retakePhotoButton.visibility = android.view.View.GONE
                        
                        // Start polling for progress updates since we're in processing state
                        startPollingForProgress(slot)
                        
                        Log.d("InspectionDetailActivity", "Processing state restored from fallback for slot: $slot")
                        return
                    }
                }
            }
            
            // If we couldn't restore, fall back to empty state
            Log.w("InspectionDetailActivity", "Could not restore processing state, falling back to empty state")
            loadEmptyState()
            
        } catch (e: Exception) {
            Log.e("InspectionDetailActivity", "Error restoring processing state: ${e.message}")
            loadEmptyState()
        }
    }
}
