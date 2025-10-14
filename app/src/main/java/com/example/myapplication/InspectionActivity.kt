package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.DataSource
import android.graphics.drawable.Drawable

// Simple data class for inspection data
data class InspectionData(
    val imageUrl: String,
    val estadoInspeccion: String,
    val comentariosInspeccion: String,
    val inspectionViewId: Int,
    val timestamp: Long,
    val slot: String
) : java.io.Serializable

class InspectionActivity : AppCompatActivity(), CoroutineScope by CoroutineScope(Dispatchers.Main) {
    private var inspectionViews = listOf<InspectionView>()
    private val requestCodeBySlot = mutableMapOf<String, Int>()
    private val previews = hashMapOf<String, ImageView>()
    private val pendingPreviewLoads = mutableListOf<Pair<String, String>>() // slot to baseUrl
    private val latestPreviewUrlBySlot = hashMapOf<String, String>() // persisted across rotation
    
    // UI elements for precheck progress
    private val slotProgressOverlays = hashMapOf<String, android.view.View>()
    private val slotStatusTexts = hashMapOf<String, TextView>()
    private val slotStatusNormalTexts = hashMapOf<String, TextView>()
    private val slotCameraIcons = hashMapOf<String, android.view.View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspection)

        // Setup toolbar
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        // Restore persisted preview URLs if any
        savedInstanceState?.let { state ->
            val keys = state.getStringArrayList("preview_keys")
            val urls = state.getStringArrayList("preview_urls")
            if (keys != null && urls != null && keys.size == urls.size) {
                latestPreviewUrlBySlot.clear()
                for (i in keys.indices) {
                    latestPreviewUrlBySlot[keys[i]] = urls[i]
                }
                Log.d("InspectionActivity", "Restored ${latestPreviewUrlBySlot.size} preview URLs from state")
            }
        }

        loadInspectionViews()
        loadExistingInspectionData()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (latestPreviewUrlBySlot.isNotEmpty()) {
            outState.putStringArrayList("preview_keys", ArrayList(latestPreviewUrlBySlot.keys))
            outState.putStringArrayList("preview_urls", ArrayList(latestPreviewUrlBySlot.values))
            Log.d("InspectionActivity", "Saved ${latestPreviewUrlBySlot.size} preview URLs to state")
        }
    }

    private fun loadInspectionViews() {
        launch {
            try {
                inspectionViews = withContext(Dispatchers.IO) {
                    SupabaseClientProvider.getInspectionViews()
                }
                
                Log.d("InspectionActivity", "Loaded ${inspectionViews.size} inspection views")
                
                // Crear los slots dinámicamente
                createDynamicSlots()
                
            } catch (e: Exception) {
                Log.e("InspectionActivity", "Error loading inspection views: ${e.message}")
                // Fallback a slots estáticos en caso de error
                createFallbackSlots()
            }
        }
    }

    private fun createDynamicSlots() {
        val container = findViewById<LinearLayout>(R.id.slots_container)
        container.removeAllViews()
        previews.clear()
        
        inspectionViews.forEachIndexed { index, inspectionView ->
            val slotId = "inspection_view_${inspectionView.id}"
            requestCodeBySlot[slotId] = 1000 + index
            
            // Crear el layout del slot dinámicamente
            val slotLayout = layoutInflater.inflate(R.layout.item_inspection_slot, container, false)
            slotLayout.id = resources.getIdentifier(slotId, "id", packageName)
            
            val imageView = slotLayout.findViewById<ImageView>(R.id.slot_image)
            val titleView = slotLayout.findViewById<TextView>(R.id.slot_title)
            val progressOverlay = slotLayout.findViewById<android.view.View>(R.id.slot_progress_overlay)
            val statusText = slotLayout.findViewById<TextView>(R.id.slot_status)
            val statusNormalText = slotLayout.findViewById<TextView>(R.id.slot_status_normal)
            val cameraIcon = slotLayout.findViewById<android.view.View>(R.id.slot_camera_icon)
            
            titleView.text = inspectionView.description
            previews[slotId] = imageView
            slotProgressOverlays[slotId] = progressOverlay
            slotStatusTexts[slotId] = statusText
            slotStatusNormalTexts[slotId] = statusNormalText
            slotCameraIcons[slotId] = cameraIcon
            
            slotLayout.setOnClickListener {
                // Check if there's inspection data for this slot
                val inspectionData = getInspectionDataForSlot(slotId)
                if (inspectionData != null) {
                    // Navigate to detail view
                    val intent = Intent(this, InspectionDetailActivity::class.java)
                    intent.putExtra("inspectionData", inspectionData)
                    startActivityForResult(intent, 2000 + requestCodeBySlot.getValue(slotId))
                    Log.d("InspectionActivity", "Navigating to detail view for slot: $slotId")
                } else {
                    // Launch camera capture
                    val intent = Intent(this, LoadingActivity::class.java)
                    intent.putExtra("captureMode", true)
                    intent.putExtra("slot", slotId)
                    intent.putExtra("inspectionViewId", inspectionView.id)
                    intent.putExtra("inspectionViewDescription", inspectionView.description)
                    intent.putExtra("cameraPosition", inspectionView.camera_position)
                    Log.d("InspectionActivity", "Launching capture for slot=$slotId (id=${inspectionView.id}, camera_position=${inspectionView.camera_position})")
                    startActivityForResult(intent, requestCodeBySlot.getValue(slotId))
                }
            }
            
            container.addView(slotLayout)
        }

        // Apply any persisted previews
        if (latestPreviewUrlBySlot.isNotEmpty()) {
            Log.d("InspectionActivity", "Re-applying ${latestPreviewUrlBySlot.size} persisted previews after dynamic slots created")
            latestPreviewUrlBySlot.forEach { (slot, baseUrl) ->
                previews[slot]?.let { iv ->
                    loadImageWithRetry(iv, baseUrl)
                }
            }
        }

        // Apply any pending preview loads now that previews map is ready
        if (pendingPreviewLoads.isNotEmpty()) {
            Log.d("InspectionActivity", "Applying ${pendingPreviewLoads.size} pending preview loads after dynamic slots created")
            val iterator = pendingPreviewLoads.iterator()
            while (iterator.hasNext()) {
                val (slot, baseUrl) = iterator.next()
                previews[slot]?.let { iv ->
                    loadImageWithRetry(iv, baseUrl)
                    iterator.remove()
                }
            }
        }
    }

    private fun createFallbackSlots() {
        val fallbackSlots = listOf(
            "lateral_izquierdo",
            "lateral_derecho", 
            "diagonal_frontal_derecho",
            "diagonal_frontal_izquierdo",
            "diagonal_trasero_derecho",
            "diagonal_trasero_izquierdo"
        )
        
        val container = findViewById<LinearLayout>(R.id.slots_container)
        container.removeAllViews()
        previews.clear()
        
        fallbackSlots.forEachIndexed { index, slot ->
            requestCodeBySlot[slot] = 1000 + index
            
            val slotLayout = layoutInflater.inflate(R.layout.item_inspection_slot, container, false)
            slotLayout.id = resources.getIdentifier("slot_${slot}", "id", packageName)
            
            val imageView = slotLayout.findViewById<ImageView>(R.id.slot_image)
            val titleView = slotLayout.findViewById<TextView>(R.id.slot_title)
            val progressOverlay = slotLayout.findViewById<android.view.View>(R.id.slot_progress_overlay)
            val statusText = slotLayout.findViewById<TextView>(R.id.slot_status)
            val statusNormalText = slotLayout.findViewById<TextView>(R.id.slot_status_normal)
            val cameraIcon = slotLayout.findViewById<android.view.View>(R.id.slot_camera_icon)
            
            titleView.text = slot.replace('_', ' ').replaceFirstChar { it.titlecase() }
            previews[slot] = imageView
            slotProgressOverlays[slot] = progressOverlay
            slotStatusTexts[slot] = statusText
            slotStatusNormalTexts[slot] = statusNormalText
            slotCameraIcons[slot] = cameraIcon
            
            slotLayout.setOnClickListener {
                val intent = Intent(this, LoadingActivity::class.java)
                intent.putExtra("captureMode", true)
                intent.putExtra("slot", slot)
                Log.d("InspectionActivity", "Launching capture (fallback) for slot=$slot")
                startActivityForResult(intent, requestCodeBySlot.getValue(slot))
            }
            
            container.addView(slotLayout)
        }

        // Apply any persisted previews
        if (latestPreviewUrlBySlot.isNotEmpty()) {
            Log.d("InspectionActivity", "Re-applying ${latestPreviewUrlBySlot.size} persisted previews after fallback slots created")
            latestPreviewUrlBySlot.forEach { (slot, baseUrl) ->
                previews[slot]?.let { iv ->
                    loadImageWithRetry(iv, baseUrl)
                }
            }
        }

        // Apply any pending preview loads now that previews map is ready
        if (pendingPreviewLoads.isNotEmpty()) {
            Log.d("InspectionActivity", "Applying ${pendingPreviewLoads.size} pending preview loads after fallback slots created")
            val iterator = pendingPreviewLoads.iterator()
            while (iterator.hasNext()) {
                val (slot, baseUrl) = iterator.next()
                previews[slot]?.let { iv ->
                    loadImageWithRetry(iv, baseUrl)
                    iterator.remove()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d("InspectionActivity", "onActivityResult: requestCode=$requestCode resultCode=$resultCode dataExtras=${data?.extras}")
        
        try {
            // Handle result from InspectionDetailActivity (retake photo)
            if (requestCode >= 2000) {
                Log.d("InspectionActivity", "Received result from InspectionDetailActivity, requestCode=$requestCode")
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Log.d("InspectionActivity", "InspectionDetailActivity returned with data, processing...")
                    // Continue with normal processing below
                } else {
                    Log.d("InspectionActivity", "InspectionDetailActivity returned without data or cancelled")
                    return
                }
            }
            if (resultCode == Activity.RESULT_OK && data != null) {
                val baseUrl = data.getStringExtra("uploadedUrl") ?: run {
                    Log.w("InspectionActivity", "Missing uploadedUrl in result")
                    return
                }
                val slot = data.getStringExtra("slot") ?: run {
                    Log.w("InspectionActivity", "Missing slot in result")
                    return
                }
                val storagePath = data.getStringExtra("storagePath") ?: ""
                val inspectionViewId = data.getIntExtra("inspectionViewId", -1)
                val hasInspectionData = data.getBooleanExtra("hasInspectionData", false)
                val estadoInspeccion = data.getStringExtra("estadoInspeccion")
                val comentariosInspeccion = data.getStringExtra("comentariosInspeccion")
                
                // Validate URL format
                if (baseUrl.isBlank() || !baseUrl.startsWith("http")) {
                    Log.w("InspectionActivity", "Invalid URL format: $baseUrl")
                    return
                }
                
                // persist latest preview URL for this slot
                latestPreviewUrlBySlot[slot] = baseUrl

                val target = previews[slot]
                if (target == null) {
                    Log.w("InspectionActivity", "Preview not ready yet for slot='$slot'. Queueing pending load. Currently available keys=${previews.keys}")
                    pendingPreviewLoads.add(slot to baseUrl)
                    return
                }
                
                Log.d("InspectionActivity", "Loading preview and starting precheck for slot='$slot'")
                Log.d("InspectionActivity", "Debug values: inspectionViewId=$inspectionViewId, storagePath='$storagePath', hasInspectionData=$hasInspectionData")
                
                // Load image immediately
                loadImageWithRetry(target, baseUrl)
                
                // Handle inspection data if present
                if (hasInspectionData && estadoInspeccion != null && comentariosInspeccion != null) {
                    val inspectionData = InspectionData(
                        imageUrl = baseUrl,
                        estadoInspeccion = estadoInspeccion,
                        comentariosInspeccion = comentariosInspeccion,
                        inspectionViewId = inspectionViewId,
                        timestamp = System.currentTimeMillis(),
                        slot = slot
                    )
                    saveInspectionData(inspectionData)
                    updateSlotStatusWithInspection(slot, estadoInspeccion)
                    Log.d("InspectionActivity", "Inspection data saved for slot: $slot, estado: $estadoInspeccion")
                } else if (inspectionViewId > 0 && storagePath.isNotEmpty()) {
                    // Start precheck in background
                    Log.d("InspectionActivity", "Starting precheck in background for slot: $slot")
                    startPrecheckInBackground(slot, storagePath, baseUrl, inspectionViewId)
                } else {
                    Log.w("InspectionActivity", "Precheck not started: inspectionViewId=$inspectionViewId, storagePath='$storagePath', hasInspectionData=$hasInspectionData")
                }
            } else if (resultCode != Activity.RESULT_CANCELED) {
                Log.w("InspectionActivity", "Unexpected result code: $resultCode")
            }
        } catch (e: Exception) {
            Log.e("InspectionActivity", "Error in onActivityResult: ${e.message}")
        }
    }

    private fun buildCacheBustedUrl(baseUrl: String): String {
        val ts = System.currentTimeMillis()
        return if ('?' in baseUrl) "$baseUrl&_ts=$ts" else "$baseUrl?_ts=$ts"
    }

    private fun loadImageWithRetry(targetView: ImageView, baseUrl: String, attempt: Int = 1, maxAttempts: Int = 4) {
        try {
            val url = buildCacheBustedUrl(baseUrl)
            Log.d("InspectionActivity", "Loading image (attempt $attempt/$maxAttempts): $url into=$targetView")
            Glide.with(this)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .dontAnimate()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        Log.w("InspectionActivity", "Glide onLoadFailed attempt=$attempt err=${e?.localizedMessage} model=$model target=$target first=$isFirstResource")
                        if (attempt < maxAttempts) {
                            launch {
                                try {
                                    delay(350L * attempt)
                                    loadImageWithRetry(targetView, baseUrl, attempt + 1, maxAttempts)
                                } catch (e: Exception) {
                                    Log.e("InspectionActivity", "Error in retry: ${e.message}")
                                }
                            }
                        }
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        Log.d("InspectionActivity", "Glide onResourceReady attempt=$attempt source=$dataSource first=$isFirstResource target=$target")
                        return false
                    }
                })
                .into(targetView)
        } catch (e: Exception) {
            Log.e("InspectionActivity", "Error in loadImageWithRetry: ${e.message}")
        }
    }
    
    private fun startPrecheckInBackground(slot: String, storagePath: String, uploadedPublicUrl: String, inspectionViewId: Int) {
        Log.d("InspectionActivity", "Starting precheck in background for slot=$slot")
        
        // Show progress overlay and hide camera icon
        slotProgressOverlays[slot]?.visibility = android.view.View.VISIBLE
        slotCameraIcons[slot]?.visibility = android.view.View.GONE
        slotStatusTexts[slot]?.text = "Validando..."
        slotStatusTexts[slot]?.setTextColor(0xFF1976D2.toInt())
        
        launch(Dispatchers.IO) {
            try {
                val prechecks = SupabaseClientProvider.getPrechecksForInspectionView(inspectionViewId)
                Log.d("InspectionActivity", "Found ${prechecks.size} prechecks for inspectionViewId=$inspectionViewId")
                
                if (prechecks.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        slotProgressOverlays[slot]?.visibility = android.view.View.GONE
                        slotCameraIcons[slot]?.visibility = android.view.View.VISIBLE
                        slotStatusNormalTexts[slot]?.text = "Validado ✓"
                        slotStatusNormalTexts[slot]?.setTextColor(0xFF4CAF50.toInt())
                        Log.d("InspectionActivity", "No prechecks found, hiding overlay for slot: $slot")
                    }
                    return@launch
                }

                // Sort prechecks by order column
                val sortedPrechecks = prechecks.sortedBy { it.order }
                Log.d("InspectionActivity", "Sorted prechecks by order: $sortedPrechecks")
                
                var inspectionDataHandled = false
                
                for (i in sortedPrechecks.indices) {
                    val p = sortedPrechecks[i]
                    val isLastPrecheck = (i == sortedPrechecks.size - 1)
                    
                    withContext(Dispatchers.Main) {
                        slotStatusTexts[slot]?.text = "Validando paso ${i + 1} de ${sortedPrechecks.size} (Order: ${p.order})"
                        Log.d("InspectionActivity", "Updated status for slot $slot: paso ${i + 1} de ${sortedPrechecks.size}, order: ${p.order}")
                    }
                    
                    val bodyJson = org.json.JSONObject().apply {
                        put("imageUrl", storagePath)
                        put("responseValue", p.responseValue)
                    }.toString()
                    
                    val responseText = SupabaseClientProvider.postJson(p.url, bodyJson)
                    
                    val success = try {
                        val responseJson = org.json.JSONObject(responseText)
                        responseJson.getBoolean("success")
                    } catch (e: Exception) {
                        Log.e("InspectionActivity", "Error parsing response JSON: ${e.message}")
                        false
                    }
                    
                    // Check if this is the last precheck and has inspection data
                    var inspectionData: org.json.JSONObject? = null
                    if (isLastPrecheck && success) {
                        try {
                            val responseJson = org.json.JSONObject(responseText)
                            if (responseJson.has("estado_inspeccion") && responseJson.has("comentarios_inspeccion")) {
                                inspectionData = responseJson
                                Log.d("InspectionActivity", "Inspection data found: ${inspectionData.toString()}")
                            }
                        } catch (e: Exception) {
                            Log.e("InspectionActivity", "Error parsing inspection data: ${e.message}")
                        }
                    }
                    
                    if (!success && !isLastPrecheck) {
                        withContext(Dispatchers.Main) {
                            slotProgressOverlays[slot]?.visibility = android.view.View.GONE
                            slotCameraIcons[slot]?.visibility = android.view.View.VISIBLE
                            
                            // Show error dialog with the error message from the precheck
                            android.app.AlertDialog.Builder(this@InspectionActivity)
                                .setTitle("Validación Fallida")
                                .setMessage("${p.errorMessage}\n\nLa imagen será eliminada.")
                                .setPositiveButton("Entendido") { dialog, _ ->
                                    dialog.dismiss()
                                }
                                .setCancelable(false)
                                .show()
                            
                            // Delete image and reset preview
                            launch(Dispatchers.IO) {
                                try {
                                    SupabaseClientProvider.deleteImage(storagePath)
                                    withContext(Dispatchers.Main) {
                                        // Reset image to placeholder
                                        previews[slot]?.setImageResource(R.drawable.ic_camera_placeholder)
                                        // Reset status text to normal state
                                        slotStatusNormalTexts[slot]?.text = "Toca para capturar"
                                        slotStatusNormalTexts[slot]?.setTextColor(0xFF757575.toInt())
                                        // Remove from latest preview URLs so it can be captured again
                                        latestPreviewUrlBySlot.remove(slot)
                                        Log.d("InspectionActivity", "Image deleted and preview reset for slot: $slot")
                                    }
                                } catch (e: Exception) {
                                    Log.e("InspectionActivity", "Error deleting image: ${e.message}")
                                }
                            }
                        }
                        return@launch
                    }
                    
                    // Handle inspection data if present (last precheck with data)
                    if (isLastPrecheck && inspectionData != null) {
                        inspectionDataHandled = true
                        withContext(Dispatchers.Main) {
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
                                saveInspectionData(inspectionDataObj)
                                
                                // Update UI with inspection status
                                updateSlotStatusWithInspection(slot, estadoInspeccion)
                                
                                slotProgressOverlays[slot]?.visibility = android.view.View.GONE
                                slotCameraIcons[slot]?.visibility = android.view.View.VISIBLE
                                
                                android.widget.Toast.makeText(
                                    this@InspectionActivity,
                                    "Inspección completada: $estadoInspeccion",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                
                                Log.d("InspectionActivity", "Inspection data handled for slot: $slot, estado: $estadoInspeccion")
                            } catch (e: Exception) {
                                Log.e("InspectionActivity", "Error handling inspection data: ${e.message}")
                            }
                        }
                        return@launch
                    }
                }
                
                // Only execute normal success flow if no inspection data was handled
                if (!inspectionDataHandled) {
                    withContext(Dispatchers.Main) {
                        slotProgressOverlays[slot]?.visibility = android.view.View.GONE
                        slotCameraIcons[slot]?.visibility = android.view.View.VISIBLE
                        slotStatusNormalTexts[slot]?.text = "Validado ✓"
                        slotStatusNormalTexts[slot]?.setTextColor(0xFF4CAF50.toInt())
                        
                        android.widget.Toast.makeText(
                            this@InspectionActivity,
                            "Imagen validada exitosamente",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                
            } catch (e: Exception) {
                Log.e("InspectionActivity", "Precheck error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    slotProgressOverlays[slot]?.visibility = android.view.View.GONE
                    slotCameraIcons[slot]?.visibility = android.view.View.VISIBLE
                    slotStatusTexts[slot]?.text = "Error en validación"
                    slotStatusTexts[slot]?.setTextColor(0xFFF44336.toInt())
                    
                    android.widget.Toast.makeText(
                        this@InspectionActivity,
                        "Error en validación: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun saveInspectionData(inspectionData: InspectionData) {
        try {
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            val editor = sharedPref.edit()
            val key = "${inspectionData.slot}_${inspectionData.timestamp}"
            editor.putString("${key}_imageUrl", inspectionData.imageUrl)
            editor.putString("${key}_estadoInspeccion", inspectionData.estadoInspeccion)
            editor.putString("${key}_comentariosInspeccion", inspectionData.comentariosInspeccion)
            editor.putString("${key}_inspectionViewId", inspectionData.inspectionViewId.toString())
            editor.putLong("${key}_timestamp", inspectionData.timestamp)
            editor.putString("${key}_slot", inspectionData.slot)
            editor.apply()
            Log.d("InspectionActivity", "Inspection data saved locally with key: $key")
        } catch (e: Exception) {
            Log.e("InspectionActivity", "Error saving inspection data: ${e.message}")
        }
    }

    private fun getInspectionDataForSlot(slot: String): InspectionData? {
        try {
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            val allKeys = sharedPref.all.keys
            val slotKeys = allKeys.filter { it.startsWith("${slot}_") && it.endsWith("_slot") }
            
            if (slotKeys.isEmpty()) return null
            
            // Get the most recent one
            val latestKey = slotKeys.maxByOrNull { key ->
                val timestampKey = key.replace("_slot", "_timestamp")
                sharedPref.getLong(timestampKey, 0L)
            } ?: return null
            
            val baseKey = latestKey.replace("_slot", "")
            
            return InspectionData(
                imageUrl = sharedPref.getString("${baseKey}_imageUrl", "") ?: "",
                estadoInspeccion = sharedPref.getString("${baseKey}_estadoInspeccion", "") ?: "",
                comentariosInspeccion = sharedPref.getString("${baseKey}_comentariosInspeccion", "") ?: "",
                inspectionViewId = sharedPref.getString("${baseKey}_inspectionViewId", "0")?.toIntOrNull() ?: 0,
                timestamp = sharedPref.getLong("${baseKey}_timestamp", 0L),
                slot = sharedPref.getString("${baseKey}_slot", "") ?: ""
            )
        } catch (e: Exception) {
            Log.e("InspectionActivity", "Error getting inspection data: ${e.message}")
            return null
        }
    }

    private fun updateSlotStatusWithInspection(slot: String, estadoInspeccion: String) {
        slotStatusNormalTexts[slot]?.let { statusText ->
            val statusColor = when (estadoInspeccion.lowercase()) {
                "aprobada" -> 0xFF4CAF50.toInt() // Green
                "rechazada" -> 0xFFF44336.toInt() // Red
                else -> 0xFFFF9800.toInt() // Orange
            }
            statusText.text = "Estado: $estadoInspeccion"
            statusText.setTextColor(statusColor)
            slotProgressOverlays[slot]?.visibility = android.view.View.GONE
        }
    }

    private fun loadExistingInspectionData() {
        try {
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            val allKeys = sharedPref.all.keys
            val slotKeys = allKeys.filter { it.endsWith("_slot") }
            
            Log.d("InspectionActivity", "Found ${slotKeys.size} existing inspection data entries")
            
            slotKeys.forEach { key ->
                val baseKey = key.replace("_slot", "")
                val slot = sharedPref.getString(key, "")
                if (slot != null && slot.isNotEmpty()) {
                    val imageUrl = sharedPref.getString("${baseKey}_imageUrl", "")
                    val estadoInspeccion = sharedPref.getString("${baseKey}_estadoInspeccion", "")
                    
                    if (imageUrl != null && estadoInspeccion != null && imageUrl.isNotEmpty() && estadoInspeccion.isNotEmpty()) {
                        // Update UI for this slot
                        updateSlotStatusWithInspection(slot, estadoInspeccion)
                        
                        // Load image if not already loaded
                        previews[slot]?.let { imageView ->
                            if (latestPreviewUrlBySlot[slot] != imageUrl) {
                                latestPreviewUrlBySlot[slot] = imageUrl
                                loadImageWithRetry(imageView, imageUrl)
                            }
                        }
                        
                        Log.d("InspectionActivity", "Loaded existing inspection data for slot: $slot, estado: $estadoInspeccion")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("InspectionActivity", "Error loading existing inspection data: ${e.message}")
        }
    }

    // Debug method to test overlay visibility
    private fun debugShowOverlay(slot: String) {
        slotProgressOverlays[slot]?.let { overlay ->
            overlay.visibility = android.view.View.VISIBLE
            overlay.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.RED)
            Log.d("InspectionActivity", "DEBUG: Red overlay shown for slot: $slot")
        }
        slotStatusTexts[slot]?.text = "DEBUG: Testing overlay"
        slotStatusTexts[slot]?.setTextColor(android.graphics.Color.WHITE)
    }
}


