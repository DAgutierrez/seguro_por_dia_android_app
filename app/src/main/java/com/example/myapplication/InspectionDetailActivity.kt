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
            // Empty state - no inspection data
            loadEmptyState()
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
            inspectionData?.let { data ->
                try {
                    // Delete existing inspection data for this slot
                    deleteInspectionDataForSlot(data.slot)
                    
                    // Launch camera to retake photo
                    val intent = Intent(this, LoadingActivity::class.java)
                    intent.putExtra("captureMode", true)
                    intent.putExtra("slot", data.slot)
                    intent.putExtra("inspectionViewId", data.inspectionViewId)
                    intent.putExtra("inspectionViewDescription", "Re-captura de inspección")
                    intent.putExtra("cameraPosition", null as String?) // Will be fetched from InspectionView
                    
                    Log.d("InspectionDetailActivity", "Launching retake capture for slot=${data.slot}")
                    startActivityForResult(intent, 3000) // Use specific requestCode for retake from detail view
                } catch (e: Exception) {
                    Log.e("InspectionDetailActivity", "Error in setupRetakeButton: ${e.message}")
                }
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
            comentariosTextView.text = "Ejecutando validaciones en background..."
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
}
