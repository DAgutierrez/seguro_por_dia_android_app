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

class InspectionActivity : AppCompatActivity(), CoroutineScope by CoroutineScope(Dispatchers.Main) {
    private var inspectionViews = listOf<InspectionView>()
    private val requestCodeBySlot = mutableMapOf<String, Int>()
    private val previews = hashMapOf<String, ImageView>()
    private val pendingPreviewLoads = mutableListOf<Pair<String, String>>() // slot to baseUrl
    private val latestPreviewUrlBySlot = hashMapOf<String, String>() // persisted across rotation
    
    // UI elements for precheck progress
    private val slotProgressOverlays = hashMapOf<String, android.view.View>()
    private val slotStatusTexts = hashMapOf<String, TextView>()

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
            
            titleView.text = inspectionView.description
            previews[slotId] = imageView
            slotProgressOverlays[slotId] = progressOverlay
            slotStatusTexts[slotId] = statusText
            
            slotLayout.setOnClickListener {
                val intent = Intent(this, LoadingActivity::class.java)
                intent.putExtra("captureMode", true)
                intent.putExtra("slot", slotId)
                intent.putExtra("inspectionViewId", inspectionView.id)
                intent.putExtra("inspectionViewDescription", inspectionView.description)
                intent.putExtra("cameraPosition", inspectionView.camera_position)
                Log.d("InspectionActivity", "Launching capture for slot=$slotId (id=${inspectionView.id}, camera_position=${inspectionView.camera_position})")
                startActivityForResult(intent, requestCodeBySlot.getValue(slotId))
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
            
            titleView.text = slot.replace('_', ' ').replaceFirstChar { it.titlecase() }
            previews[slot] = imageView
            slotProgressOverlays[slot] = progressOverlay
            slotStatusTexts[slot] = statusText
            
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
                
                // Load image immediately
                loadImageWithRetry(target, baseUrl)
                
                // Start precheck in background
                if (inspectionViewId > 0 && storagePath.isNotEmpty()) {
                    startPrecheckInBackground(slot, storagePath, baseUrl, inspectionViewId)
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
        
        // Show progress overlay
        slotProgressOverlays[slot]?.visibility = android.view.View.VISIBLE
        slotStatusTexts[slot]?.text = "Validando..."
        slotStatusTexts[slot]?.setTextColor(0xFF1976D2.toInt())
        
        launch(Dispatchers.IO) {
            try {
                val prechecks = SupabaseClientProvider.getPrechecksForInspectionView(inspectionViewId)
                Log.d("InspectionActivity", "Found ${prechecks.size} prechecks for inspectionViewId=$inspectionViewId")
                
                if (prechecks.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        slotProgressOverlays[slot]?.visibility = android.view.View.GONE
                        slotStatusTexts[slot]?.text = "Validado ✓"
                        slotStatusTexts[slot]?.setTextColor(0xFF4CAF50.toInt())
                    }
                    return@launch
                }
                
                for (i in prechecks.indices) {
                    val p = prechecks[i]
                    
                    withContext(Dispatchers.Main) {
                        slotStatusTexts[slot]?.text = "Validando paso ${i + 1} de ${prechecks.size}"
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
                    
                    if (!success) {
                        withContext(Dispatchers.Main) {
                            slotProgressOverlays[slot]?.visibility = android.view.View.GONE
                            slotStatusTexts[slot]?.text = "Error: ${p.errorMessage}"
                            slotStatusTexts[slot]?.setTextColor(0xFFF44336.toInt())
                            
                            // Show error dialog
                            android.app.AlertDialog.Builder(this@InspectionActivity)
                                .setTitle("Validación Fallida")
                                .setMessage("${p.errorMessage}\n\nLa imagen será eliminada.")
                                .setPositiveButton("Entendido") { dialog, _ ->
                                    dialog.dismiss()
                                }
                                .setCancelable(false)
                                .show()
                            
                            // Delete image
                            launch(Dispatchers.IO) {
                                try {
                                    SupabaseClientProvider.deleteImage(storagePath)
                                    withContext(Dispatchers.Main) {
                                        previews[slot]?.setImageResource(R.drawable.ic_camera_placeholder)
                                        slotStatusTexts[slot]?.text = "Toca para capturar"
                                        slotStatusTexts[slot]?.setTextColor(0xFF757575.toInt())
                                        latestPreviewUrlBySlot.remove(slot)
                                    }
                                } catch (e: Exception) {
                                    Log.e("InspectionActivity", "Error deleting image: ${e.message}")
                                }
                            }
                        }
                        return@launch
                    }
                }
                
                // All prechecks passed
                withContext(Dispatchers.Main) {
                    slotProgressOverlays[slot]?.visibility = android.view.View.GONE
                    slotStatusTexts[slot]?.text = "Validado ✓"
                    slotStatusTexts[slot]?.setTextColor(0xFF4CAF50.toInt())
                    
                    android.widget.Toast.makeText(
                        this@InspectionActivity,
                        "Imagen validada exitosamente",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                
            } catch (e: Exception) {
                Log.e("InspectionActivity", "Precheck error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    slotProgressOverlays[slot]?.visibility = android.view.View.GONE
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
}


