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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.DataSource
import android.graphics.drawable.Drawable
import kotlinx.coroutines.delay

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
    
    // Polling for progress updates
    private var progressPollingHandler: android.os.Handler? = null
    private var progressPollingRunnable: Runnable? = null

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
        
        // Check for any processing in progress when returning from detail view
        checkForProcessingInProgress()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (latestPreviewUrlBySlot.isNotEmpty()) {
            outState.putStringArrayList("preview_keys", ArrayList(latestPreviewUrlBySlot.keys))
            outState.putStringArrayList("preview_urls", ArrayList(latestPreviewUrlBySlot.values))
            Log.d("InspectionActivity", "Saved ${latestPreviewUrlBySlot.size} preview URLs to state")
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("InspectionActivity", "=== ONRESUME CALLED ===")
        // Start continuous polling (minimal logic: only <slot>_processing)
        startPollingForProgressUpdates()
    }

    override fun onPause() {
        super.onPause()
        Log.d("InspectionActivity", "=== ONPAUSE CALLED ===")
        // Stop continuous polling when leaving this activity
        stopPollingForProgressUpdates()
    }

    private fun updateProcessingFlagsForAllSlots() {
        try {
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            inspectionViews.forEach { inspectionView ->
                val slot = "inspection_view_${inspectionView.id}"
                val isProcessing = try {
                    sharedPref.getBoolean("${slot}_processing", false)
                } catch (e: ClassCastException) {
                    Log.w("InspectionActivity", "Key '${slot}_processing' is not a boolean, removing...")
                    val editor = sharedPref.edit()
                    editor.remove("${slot}_processing")
                    editor.apply()
                    false
                }
                val statusText = slotStatusTexts[slot]
                val progressOverlay = slotProgressOverlays[slot]
                val cameraIcon = slotCameraIcons[slot]
                if (isProcessing) {
                    statusText?.let { t ->
                        t.text = "Procesando..."
                        t.visibility = android.view.View.VISIBLE
                    }
                    progressOverlay?.visibility = android.view.View.VISIBLE
                    cameraIcon?.visibility = android.view.View.GONE
                } else {
                    // Hide processing UI when not processing
                    progressOverlay?.visibility = android.view.View.GONE
                }
            }
            Log.d("InspectionActivity", "Processing flags synced for all slots")
        } catch (e: Exception) {
            Log.e("InspectionActivity", "Error syncing processing flags: ${e.message}", e)
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
            
            // Check if this is a clear slot data action or update slot status
            if (resultCode == Activity.RESULT_OK && data != null) {
                val action = data.getStringExtra("action")
                if (action == "clear_slot_data") {
                    val slot = data.getStringExtra("slot")
                    if (slot != null) {
                        Log.d("InspectionActivity", "Clearing slot data for: $slot")
                        clearSlotData(slot)
                        return
                    }
                } else if (action == "update_slot_status") {
                    val slot = data.getStringExtra("slot")
                    val estadoInspeccion = data.getStringExtra("estadoInspeccion")
                    if (slot != null && estadoInspeccion != null) {
                        Log.d("InspectionActivity", "Updating slot status for: $slot -> $estadoInspeccion")
                        updateSlotStatusWithInspection(slot, estadoInspeccion)
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
        Log.d("InspectionActivity", "=== UPDATING SLOT STATUS WITH INSPECTION ===")
        Log.d("InspectionActivity", "Slot: '$slot', Estado: '$estadoInspeccion'")
        
        val statusText = slotStatusNormalTexts[slot]
        Log.d("InspectionActivity", "StatusText for '$slot': ${statusText != null}")
        
        statusText?.let { textView ->
            val statusColor = when (estadoInspeccion.lowercase()) {
                "aprobada" -> 0xFF4CAF50.toInt() // Green
                "rechazada" -> 0xFFF44336.toInt() // Red
                else -> 0xFFFF9800.toInt() // Orange
            }

            // Update status text with emoji and color
            val statusEmoji = when (estadoInspeccion.lowercase()) {
                "aprobada" -> "✅"
                "rechazada" -> "❌"
                else -> "⚠️"
            }
            
            val finalText = "$statusEmoji $estadoInspeccion"
            Log.d("InspectionActivity", "Setting status text to: '$finalText' with color: $statusColor")
            
            textView.text = finalText
            textView.setTextColor(statusColor)

            // Hide progress overlay and show camera icon
            val progressOverlay = slotProgressOverlays[slot]
            val cameraIcon = slotCameraIcons[slot]
            
            Log.d("InspectionActivity", "Progress overlay for '$slot': ${progressOverlay != null}")
            Log.d("InspectionActivity", "Camera icon for '$slot': ${cameraIcon != null}")
            
            progressOverlay?.visibility = android.view.View.GONE
            cameraIcon?.visibility = android.view.View.VISIBLE

            // Update card background color based on status
            //updateSlotCardBackground(slot, estadoInspeccion)

            Log.d("InspectionActivity", "Updated slot status for '$slot': $estadoInspeccion")
        } ?: Log.w("InspectionActivity", "StatusText not found for slot '$slot'")
        
        Log.d("InspectionActivity", "=== FINISHED UPDATING SLOT STATUS ===")
    }
    
    private fun updateSlotCardBackground(slot: String, estadoInspeccion: String) {
        try {
            // Find the slot layout by ID
            val slotLayoutId = resources.getIdentifier(slot, "id", packageName)
            val slotLayout = findViewById<android.view.View>(slotLayoutId)
            
            if (slotLayout != null && slotLayout is com.google.android.material.card.MaterialCardView) {
                val backgroundColor = when (estadoInspeccion.lowercase()) {
                    "aprobada" -> 0xFFE8F5E8.toInt() // Light green
                    "rechazada" -> 0xFFFFEBEE.toInt() // Light red
                    else -> 0xFFFFF3E0.toInt() // Light orange
                }
                
                slotLayout.setCardBackgroundColor(backgroundColor)
                
                // Add a subtle border
                val borderColor = when (estadoInspeccion.lowercase()) {
                    "aprobada" -> 0xFF4CAF50.toInt() // Green
                    "rechazada" -> 0xFFF44336.toInt() // Red
                    else -> 0xFFFF9800.toInt() // Orange
                }
                
                slotLayout.strokeColor = borderColor
                slotLayout.strokeWidth = 2
                
                Log.d("InspectionActivity", "Updated card background for slot '$slot' with color: $backgroundColor")
            }
        } catch (e: Exception) {
            Log.e("InspectionActivity", "Error updating card background for slot '$slot': ${e.message}")
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

    private fun checkForProcessingInProgress() {
        try {
            Log.d("InspectionActivity", "=== CHECKING FOR PROCESSING IN PROGRESS ===")
            
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            val allKeys = sharedPref.all.keys
            
            Log.d("InspectionActivity", "All SharedPreferences keys in checkForProcessingInProgress: $allKeys")
            
            // Clean up old format data first
            cleanOldFormatProcessingData(sharedPref, allKeys)
            
            // Look for processing state keys (exactly "_processing", not containing other suffixes)
            val processingSlots = allKeys.filter { 
                it.endsWith("_processing") && !it.contains("_processing_")
            }
            
            Log.d("InspectionActivity", "Found ${processingSlots.size} slots with processing in progress: $processingSlots")
            
            processingSlots.forEach { processingKey ->
                val slot = processingKey.removeSuffix("_processing")
                
                // Safely get boolean value, handle potential type mismatch
                val isProcessing = try {
                    sharedPref.getBoolean(processingKey, false)
                } catch (e: ClassCastException) {
                    Log.w("InspectionActivity", "Key '$processingKey' is not a boolean, cleaning up...")
                    // Clean up this corrupted key
                    val editor = sharedPref.edit()
                    editor.remove(processingKey)
                    editor.apply()
                    Log.d("InspectionActivity", "Cleaned up corrupted key: $processingKey")
                    false
                }
                
                Log.d("InspectionActivity", "Processing key '$processingKey' -> slot '$slot', isProcessing: $isProcessing")
                
                if (isProcessing) {
                    Log.d("InspectionActivity", "Slot '$slot' has processing in progress, syncing state...")
                    syncSlotStateFromSharedPrefs(slot)
                }
            }
            
            Log.d("InspectionActivity", "=== FINISHED CHECKING FOR PROCESSING IN PROGRESS ===")
            
        } catch (e: Exception) {
            Log.e("InspectionActivity", "Error checking for processing in progress: ${e.message}", e)
        }
    }

    private fun cleanOldFormatProcessingData(sharedPref: android.content.SharedPreferences, allKeys: Set<String>) {
        try {
            Log.d("InspectionActivity", "=== CLEANING OLD FORMAT PROCESSING DATA ===")
            
            val editor = sharedPref.edit()
            var cleanedCount = 0
            
            // Find keys that end with "_processing" but are not boolean
            val processingKeys = allKeys.filter { 
                it.endsWith("_processing") && !it.contains("_processing_")
            }
            
            processingKeys.forEach { key ->
                try {
                    // Try to read as boolean, if it fails, it's old format
                    sharedPref.getBoolean(key, false)
                    Log.d("InspectionActivity", "Key '$key' is already in correct boolean format")
                } catch (e: ClassCastException) {
                    Log.w("InspectionActivity", "Found old format key '$key', cleaning up...")
                    editor.remove(key)
                    cleanedCount++
                }
            }
            
            if (cleanedCount > 0) {
                editor.apply()
                Log.d("InspectionActivity", "Cleaned up $cleanedCount old format keys")
            } else {
                Log.d("InspectionActivity", "No old format keys found")
            }
            
            Log.d("InspectionActivity", "=== FINISHED CLEANING OLD FORMAT DATA ===")
            
        } catch (e: Exception) {
            Log.e("InspectionActivity", "Error cleaning old format data: ${e.message}", e)
        }
    }

    private fun syncSlotStateFromSharedPrefs(slot: String) {
        try {
            Log.d("InspectionActivity", "=== SYNCING SLOT STATE FROM SHAREDPREFS FOR: $slot ===")
            
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            
            // Check if there's a progress message
            val progressMessage = sharedPref.getString("${slot}_progress", "")
            Log.d("InspectionActivity", "Progress message for '$slot': '$progressMessage'")
            
            if (!progressMessage.isNullOrEmpty()) {
                Log.d("InspectionActivity", "Found progress message for '$slot': $progressMessage")
                
                // Check UI elements
                val statusText = slotStatusTexts[slot]
                val progressOverlay = slotProgressOverlays[slot]
                val cameraIcon = slotCameraIcons[slot]
                
                Log.d("InspectionActivity", "UI elements for '$slot': statusText=${statusText != null}, progressOverlay=${progressOverlay != null}, cameraIcon=${cameraIcon != null}")
                
                // Update progress overlay
                statusText?.let { textView ->
                    Log.d("InspectionActivity", "Updating status text for '$slot' to: $progressMessage")
                    textView.text = progressMessage
                    textView.visibility = android.view.View.VISIBLE
                } ?: Log.w("InspectionActivity", "StatusText not found for slot '$slot'")
                
                // Show progress overlay
                progressOverlay?.let { overlay ->
                    Log.d("InspectionActivity", "Showing progress overlay for '$slot'")
                    overlay.visibility = android.view.View.VISIBLE
                } ?: Log.w("InspectionActivity", "ProgressOverlay not found for slot '$slot'")
                
                cameraIcon?.let { icon ->
                    Log.d("InspectionActivity", "Hiding camera icon for '$slot'")
                    icon.visibility = android.view.View.GONE
                } ?: Log.w("InspectionActivity", "CameraIcon not found for slot '$slot'")
            } else {
                Log.d("InspectionActivity", "No progress message found for '$slot'")
            }
            
            // Check if there's final inspection data
            val estadoInspeccion = sharedPref.getString("${slot}_estadoInspeccion", "")
            val imageUrl = sharedPref.getString("${slot}_imageUrl", "")
            
            Log.d("InspectionActivity", "Final data for '$slot': estadoInspeccion='$estadoInspeccion', imageUrl='$imageUrl'")
            
            if (!estadoInspeccion.isNullOrEmpty()) {
                Log.d("InspectionActivity", "Found final inspection data for '$slot': $estadoInspeccion")
                
                // Update slot status
                updateSlotStatusWithInspection(slot, estadoInspeccion)
                
                // Load image if available
                if (!imageUrl.isNullOrEmpty()) {
                    Log.d("InspectionActivity", "Loading image for '$slot': $imageUrl")
                    latestPreviewUrlBySlot[slot] = imageUrl
                    previews[slot]?.let { imageView ->
                        loadImageWithRetry(imageView, imageUrl)
                    } ?: Log.w("InspectionActivity", "Preview ImageView not found for slot '$slot'")
                }
            }
            
            Log.d("InspectionActivity", "=== SLOT STATE SYNCED FOR: $slot ===")
            
        } catch (e: Exception) {
            Log.e("InspectionActivity", "Error syncing slot state for '$slot': ${e.message}", e)
        }
    }

    private fun startPollingForProgressUpdates() {
        try {
            Log.d("InspectionActivity", "=== STARTING PROGRESS POLLING ===")
            
            // Stop any existing polling first
            stopPollingForProgressUpdates()
            
            progressPollingHandler = android.os.Handler(android.os.Looper.getMainLooper())
            progressPollingRunnable = object : Runnable {
                override fun run() {
                    pollForProgressUpdates()
                    progressPollingHandler?.postDelayed(this, 1000) // Poll every second
                }
            }
            
            progressPollingRunnable?.let { runnable ->
                progressPollingHandler?.post(runnable)
                Log.d("InspectionActivity", "Progress polling started successfully")
            } ?: Log.e("InspectionActivity", "Failed to create polling runnable")
            
        } catch (e: Exception) {
            Log.e("InspectionActivity", "Error starting progress polling: ${e.message}", e)
        }
    }

    private fun stopPollingForProgressUpdates() {
        try {
            Log.d("InspectionActivity", "=== STOPPING PROGRESS POLLING ===")
            
            progressPollingRunnable?.let { runnable ->
                progressPollingHandler?.removeCallbacks(runnable)
                Log.d("InspectionActivity", "Removed polling callbacks")
            } ?: Log.d("InspectionActivity", "No polling runnable to remove")
            
            progressPollingHandler = null
            progressPollingRunnable = null
            
            Log.d("InspectionActivity", "Progress polling stopped")
            
        } catch (e: Exception) {
            Log.e("InspectionActivity", "Error stopping progress polling: ${e.message}", e)
        }
    }

    private fun pollForProgressUpdates() {
        try {
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            val allKeys = sharedPref.all.keys
            
            Log.d("InspectionActivityPolling", "=== POLLING CYCLE START ===")
            Log.d("InspectionActivityPolling", "All SharedPreferences keys: $allKeys")
            
            // Look for processing state keys (exactly "_processing", not containing other suffixes)
            val processingSlots = allKeys.filter { 
                it.endsWith("_processing") && !it.contains("_processing_")
            }
            Log.d("InspectionActivityPolling", "Found ${processingSlots.size} processing slots: $processingSlots")
            val activeProcessingSlots = mutableSetOf<String>()
            
            processingSlots.forEach { processingKey ->
                val slot = processingKey.removeSuffix("_processing")
                
                // Safely get boolean value, handle potential type mismatch
                val isProcessing = try {
                    sharedPref.getBoolean(processingKey, false)
                } catch (e: ClassCastException) {
                    Log.w("InspectionActivityPolling", "Key '$processingKey' is not a boolean, cleaning up...")
                    // Clean up this corrupted key immediately
                    val editor = sharedPref.edit()
                    editor.remove(processingKey)
                    editor.apply()
                    Log.d("InspectionActivityPolling", "Cleaned up corrupted key in polling: $processingKey")
                    false
                }
                
                Log.d("InspectionActivityPolling", "Processing slot '$slot': isProcessing=$isProcessing")
                
                if (isProcessing) {
                    activeProcessingSlots.add(slot)
                    // Check for progress updates
                    val progressMessage = sharedPref.getString("${slot}_progress", "")
                    Log.d("InspectionActivityPolling", "Progress message for '$slot': '$progressMessage'")
                    
                    if (!progressMessage.isNullOrEmpty()) {
                        Log.d("InspectionActivityPolling", "Polling: Found progress for '$slot': $progressMessage")
                        
                        // Check if UI elements exist
                        val statusText = slotStatusTexts[slot]
                        val progressOverlay = slotProgressOverlays[slot]
                        val cameraIcon = slotCameraIcons[slot]
                        
                        Log.d("InspectionActivityPolling", "UI elements for '$slot': statusText=${statusText != null}, progressOverlay=${progressOverlay != null}, cameraIcon=${cameraIcon != null}")
                        
                        // Update progress overlay
                        statusText?.let { textView ->
                            Log.d("InspectionActivityPolling", "Updating status text for '$slot' to: $progressMessage")
                            textView.text = progressMessage
                            textView.visibility = android.view.View.VISIBLE
                        } ?: Log.w("InspectionActivityPolling", "StatusText not found for slot '$slot'")
                        
                        // Show progress overlay
                        progressOverlay?.let { overlay ->
                            Log.d("InspectionActivityPolling", "Showing progress overlay for '$slot'")
                            overlay.visibility = android.view.View.VISIBLE
                        } ?: Log.w("InspectionActivityPolling", "ProgressOverlay not found for slot '$slot'")
                        
                        cameraIcon?.let { icon ->
                            Log.d("InspectionActivityPolling", "Hiding camera icon for '$slot'")
                            icon.visibility = android.view.View.GONE
                        } ?: Log.w("InspectionActivityPolling", "CameraIcon not found for slot '$slot'")
                    } else {
                        Log.d("InspectionActivityPolling", "No progress message found for '$slot'")
                    }
                    
                   
                } else {
                    Log.d("InspectionActivityPolling", "Slot '$slot' is not processing")
                }
            }
            
            // Check for final inspection data for all slots (outside processingSlots loop)
            val statusSlots = allKeys.filter { 
                it.endsWith("_estadoInspeccion")
            }
            Log.d("InspectionActivityPolling", "Found ${statusSlots.size} slots with final inspection data: $statusSlots")

            statusSlots.forEach { statusKey ->
                val slot = statusKey.removeSuffix("_estadoInspeccion")

                // Check for final inspection data
                val estadoInspeccion = sharedPref.getString("${slot}_estadoInspeccion", "")
                val imageUrl = sharedPref.getString("${slot}_imageUrl", "")

                Log.d("InspectionActivityPolling", "Final data for '$slot': estadoInspeccion='$estadoInspeccion', imageUrl='$imageUrl'")

                if (!estadoInspeccion.isNullOrEmpty()) {
                    Log.d("InspectionActivityPolling", "Polling: Found final inspection data for '$slot': $estadoInspeccion")

                    // Update slot status
                    updateSlotStatusWithInspection(slot, estadoInspeccion)

                    // Load image if available
                    // if (!imageUrl.isNullOrEmpty()) {
                    //     Log.d("InspectionActivityPolling", "Loading image for '$slot': $imageUrl")
                    //     latestPreviewUrlBySlot[slot] = imageUrl
                    //     previews[slot]?.let { imageView ->
                    //         loadImageWithRetry(imageView, imageUrl)
                    //     } ?: Log.w("InspectionActivityPolling", "Preview ImageView not found for slot '$slot'")
                    // }

                    // Remove processing flag if it exists
                    if (sharedPref.contains("${slot}_processing")) {
                        val editor = sharedPref.edit()
                        editor.remove("${slot}_processing")
                        editor.apply()
                        Log.d("InspectionActivityPolling", "Removed processing flag for completed slot '$slot'")
                    }
                }
            }

             
            // Clear progress UI for any slot NOT actively processing
            inspectionViews.forEach { inspectionView ->
                val slot = "inspection_view_${inspectionView.id}"
                if (!activeProcessingSlots.contains(slot)) {
                    val statusText = slotStatusTexts[slot]
                    val progressOverlay = slotProgressOverlays[slot]
                    val cameraIcon = slotCameraIcons[slot]
                    statusText?.visibility = android.view.View.GONE
                    progressOverlay?.visibility = android.view.View.GONE
                    cameraIcon?.visibility = android.view.View.VISIBLE
                    Log.d("InspectionActivityPolling", "Cleared progress UI for non-processing slot: $slot")
                }
            }
            
            Log.d("InspectionActivityPolling", "=== POLLING CYCLE END ===")
            
        } catch (e: Exception) {
            Log.e("InspectionActivity", "Error polling for progress updates: ${e.message}", e)
        }
    }
}


