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
                // Always go to detail view - with or without inspection data
                val inspectionData = getInspectionDataForSlot(slotId)
                val intent = Intent(this, InspectionDetailActivity::class.java)
                
                if (inspectionData != null) {
                    // Has inspection data - show details
                    intent.putExtra("inspectionData", inspectionData)
                    Log.d("InspectionActivity", "Navigating to detail view with data for slot: $slotId")
                } else {
                    // No inspection data - show empty state
                    intent.putExtra("slot", slotId)
                    intent.putExtra("inspectionViewId", inspectionView.id)
                    intent.putExtra("inspectionViewDescription", inspectionView.description)
                    intent.putExtra("cameraPosition", inspectionView.camera_position)
                    Log.d("InspectionActivity", "Navigating to detail view with empty state for slot: $slotId")
                }
                
                startActivityForResult(intent, 2000 + requestCodeBySlot.getValue(slotId))
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
                // Always go to detail view - with or without inspection data
                val inspectionData = getInspectionDataForSlot(slot)
                val intent = Intent(this, InspectionDetailActivity::class.java)
                
                if (inspectionData != null) {
                    // Has inspection data - show details
                    intent.putExtra("inspectionData", inspectionData)
                    Log.d("InspectionActivity", "Navigating to detail view with data (fallback) for slot: $slot")
                } else {
                    // No inspection data - show empty state
                    intent.putExtra("slot", slot)
                    intent.putExtra("inspectionViewId", -1) // Fallback doesn't have inspectionViewId
                    intent.putExtra("inspectionViewDescription", slot.replace('_', ' ').replaceFirstChar { it.titlecase() })
                    intent.putExtra("cameraPosition", null as String?)
                    Log.d("InspectionActivity", "Navigating to detail view with empty state (fallback) for slot: $slot")
                }
                
                startActivityForResult(intent, 2000 + requestCodeBySlot.getValue(slot))
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
            Log.d("InspectionActivity", "onActivityResult: requestCode=$requestCode, resultCode=$resultCode, data=${data != null}")
            Log.d("InspectionActivity", "requestCodeBySlot map: $requestCodeBySlot")
            
            // Check if this is a clear slot data action
            if (resultCode == Activity.RESULT_OK && data != null) {
                val action = data.getStringExtra("action")
                if (action == "clear_slot_data") {
                    val slot = data.getStringExtra("slot")
                    if (slot != null) {
                        Log.d("InspectionActivity", "Clearing slot data for: $slot")
                        clearSlotData(slot)
                        return
                    }
                }
            }
            
            // All camera captures now go to detail view
            Log.d("InspectionActivity", "Received camera result, requestCode=$requestCode")
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
                
                Log.d("InspectionActivity", "Received result - slot='$slot', baseUrl='$baseUrl', storagePath='$storagePath', inspectionViewId=$inspectionViewId")
                
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
                } else {
                    Log.d("InspectionActivity", "Loading preview for slot='$slot'")
                    // Load image immediately
                    loadImageWithRetry(target, baseUrl)
                }
                
                Log.d("InspectionActivity", "Processing photo capture for slot='$slot'")
                Log.d("InspectionActivity", "Debug values: inspectionViewId=$inspectionViewId, storagePath='$storagePath'")
                
                // Check if we need to start prechecks (from detail view)
                val startPrechecks = data.getBooleanExtra("startPrechecks", false)
                if (startPrechecks && inspectionViewId > 0 && storagePath.isNotEmpty()) {
                    Log.d("InspectionActivity", "Starting precheck in background for slot: $slot (from detail view)")
                   // startPrecheckInBackground(slot, storagePath, baseUrl, inspectionViewId)
                } else if (!startPrechecks) {
                    Log.d("InspectionActivity", "No prechecks needed for slot: $slot (detail view will handle)")
                } else {
                    Log.e("InspectionActivity", "Cannot start prechecks: inspectionViewId=$inspectionViewId, storagePath='$storagePath'")
                    Log.e("InspectionActivity", "This indicates a serious error in the camera flow")
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
        Log.d("InspectionActivity", "Starting precheck in background for slot='$slot'")
        Log.d("InspectionActivity", "Parameters: storagePath='$storagePath', uploadedPublicUrl='$uploadedPublicUrl', inspectionViewId=$inspectionViewId")
        Log.d("InspectionActivity", "UI refs available? overlay=${slotProgressOverlays.containsKey(slot)} statusText=${slotStatusTexts.containsKey(slot)} normalText=${slotStatusNormalTexts.containsKey(slot)} cameraIcon=${slotCameraIcons.containsKey(slot)}")
        
        // Show progress overlay and hide camera icon
        slotProgressOverlays[slot]?.visibility = android.view.View.VISIBLE
        slotCameraIcons[slot]?.visibility = android.view.View.GONE
        slotStatusTexts[slot]?.text = "Validando..."
        slotStatusTexts[slot]?.setTextColor(0xFF1976D2.toInt())
        // Fallback: also reflect progress in the normal status area in case overlay is not visible yet
        slotStatusNormalTexts[slot]?.text = "Validando..."
        slotStatusNormalTexts[slot]?.setTextColor(0xFF1976D2.toInt())
        Log.d("InspectionActivity", "Progress UI updated for slot=$slot: overlayVisible=${slotProgressOverlays[slot]?.visibility == android.view.View.VISIBLE}")

        // If UI refs aren't ready yet (first time, inflation delay), wait briefly and then show overlay
        if (!slotProgressOverlays.containsKey(slot) || !slotStatusTexts.containsKey(slot) || !slotCameraIcons.containsKey(slot)) {
            launch {
                repeat(12) { // ~1.2s total
                    delay(100)
                    if (slotProgressOverlays.containsKey(slot) && slotStatusTexts.containsKey(slot) && slotCameraIcons.containsKey(slot)) {
                        withContext(Dispatchers.Main) {
                            slotProgressOverlays[slot]?.visibility = android.view.View.VISIBLE
                            slotCameraIcons[slot]?.visibility = android.view.View.GONE
                            slotStatusTexts[slot]?.text = "Validando..."
                            slotStatusTexts[slot]?.setTextColor(0xFF1976D2.toInt())
                            Log.d("InspectionActivity", "Delayed UI setup applied for slot=$slot (overlay now visible)")
                        }
                        return@launch
                    }
                }
                Log.w("InspectionActivity", "UI refs still not ready after wait for slot=$slot; continuing without overlay")
            }
        }
        
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
                        Log.d("InspectionActivity", "Prechecks empty → marking as valid for slot=$slot")
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
                        val stepMsg = "Validando paso ${i + 1} de ${sortedPrechecks.size} (Order: ${p.order})"
                        val detailMsg = "Validando ${p.name}"
                        
                        slotStatusTexts[slot]?.text = stepMsg
                        // Fallback: mirror the step message in normal area so progress is visible even if overlay is delayed
                        slotStatusNormalTexts[slot]?.text = stepMsg
                        slotStatusNormalTexts[slot]?.setTextColor(0xFF1976D2.toInt())
                        
                        // Update detail view progress via SharedPreferences
                        updateDetailViewProgress(slot, detailMsg)
                        
                        Log.d("InspectionActivity", "Updated status for slot $slot: $stepMsg, detail: $detailMsg")
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
                            
                            // Save failed precheck result as inspection data (don't delete image)
                            val failedInspectionData = InspectionData(
                                imageUrl = uploadedPublicUrl,
                                estadoInspeccion = "Rechazada",
                                comentariosInspeccion = p.errorMessage,
                                inspectionViewId = inspectionViewId,
                                timestamp = System.currentTimeMillis(),
                                slot = slot
                            )
                            saveInspectionData(failedInspectionData)
                            
                            Log.d("InspectionActivity", "Precheck failed for slot: $slot, saving as rejected inspection data")
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
                        Log.d("InspectionActivity", "No inspection data handled → normal success flow for slot=$slot")
                        
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
    
    private fun updateDetailViewProgress(slot: String, progressMessage: String) {
        try {
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            val editor = sharedPref.edit()
            val key = "${slot}_progress"
            
            Log.d("InspectionActivity", "Saving progress - slot: '$slot', key: '$key', message: '$progressMessage'")
            
            editor.putString(key, progressMessage)
            editor.putLong("${key}_timestamp", System.currentTimeMillis())
            val success = editor.commit() // Use commit() instead of apply() for immediate writing
            
            Log.d("InspectionActivity", "Updated detail view progress for slot $slot: $progressMessage, success: $success")
            
            // Verify it was saved
            val saved = sharedPref.getString(key, "")
            Log.d("InspectionActivity", "Verified saved progress for key '$key': '$saved'")
            
            // Also log all keys to debug
            val allKeys = sharedPref.all.keys
            val progressKeys = allKeys.filter { it.contains("_progress") }
            Log.d("InspectionActivity", "All progress keys in SharedPreferences: $progressKeys")
        } catch (e: Exception) {
            Log.e("InspectionActivity", "Error updating detail view progress: ${e.message}")
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
    
    // Clear all data for a specific slot (called from InspectionDetailActivity)
    fun clearSlotData(slot: String) {
        try {
            Log.d("InspectionActivity", "Clearing slot data for: $slot")
            
            // Clear preview URL
            latestPreviewUrlBySlot.remove(slot)
            
            // Clear preview image
            previews[slot]?.setImageDrawable(null)
            
            // Clear progress overlays
            slotProgressOverlays[slot]?.visibility = android.view.View.GONE
            slotCameraIcons[slot]?.visibility = android.view.View.VISIBLE
            
            // Clear status texts
            slotStatusTexts[slot]?.text = ""
            slotStatusNormalTexts[slot]?.text = ""
            
            // Clear pending preview loads
            pendingPreviewLoads.removeAll { it.first == slot }
            
            Log.d("InspectionActivity", "Slot data cleared for: $slot")
        } catch (e: Exception) {
            Log.e("InspectionActivity", "Error clearing slot data for '$slot': ${e.message}")
        }
    }
}


