package com.example.myapplication

import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

class InspectionDetailActivity : AppCompatActivity() {
    
    private lateinit var imageView: ImageView
    private lateinit var estadoTextView: TextView
    private lateinit var comentariosTextView: TextView
    
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
        
        // Get inspection data from intent
        val inspectionData = intent.getSerializableExtra("inspectionData") as? InspectionData
        if (inspectionData != null) {
            loadInspectionData(inspectionData)
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
}
