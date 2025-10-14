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
        if (inspectionData != null) {
            loadInspectionData(inspectionData!!)
            setupRetakeButton()
        } else {
            Log.e("InspectionDetailActivity", "No inspection data provided")
            finish()
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
                    startActivityForResult(intent, 1001)
                } catch (e: Exception) {
                    Log.e("InspectionDetailActivity", "Error in setupRetakeButton: ${e.message}")
                }
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == 1001) {
            Log.d("InspectionDetailActivity", "Received result from camera: resultCode=$resultCode")
            Log.d("InspectionDetailActivity", "Data extras: ${data?.extras}")
            
            // Pass the result back to InspectionActivity and finish this activity
            if (resultCode == android.app.Activity.RESULT_OK) {
                Log.d("InspectionDetailActivity", "Setting result OK and passing data back to InspectionActivity")
                setResult(android.app.Activity.RESULT_OK, data)
            } else {
                Log.d("InspectionDetailActivity", "Setting result code $resultCode and passing data back to InspectionActivity")
                setResult(resultCode, data)
            }
            Log.d("InspectionDetailActivity", "Finishing InspectionDetailActivity")
            finish()
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
