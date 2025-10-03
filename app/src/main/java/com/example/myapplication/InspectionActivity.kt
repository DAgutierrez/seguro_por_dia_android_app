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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InspectionActivity : AppCompatActivity(), CoroutineScope by CoroutineScope(Dispatchers.Main) {
    private var inspectionViews = listOf<InspectionView>()
    private val requestCodeBySlot = mutableMapOf<String, Int>()
    private val previews = hashMapOf<String, ImageView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspection)

        loadInspectionViews()
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
        
        inspectionViews.forEachIndexed { index, inspectionView ->
            val slotId = "inspection_view_${inspectionView.id}"
            requestCodeBySlot[slotId] = 1000 + index
            
            // Crear el layout del slot dinámicamente
            val slotLayout = layoutInflater.inflate(R.layout.item_inspection_slot, container, false)
            slotLayout.id = resources.getIdentifier(slotId, "id", packageName)
            
            val imageView = slotLayout.findViewById<ImageView>(R.id.slot_image)
            val titleView = slotLayout.findViewById<TextView>(R.id.slot_title)
            
            titleView.text = inspectionView.description
            previews[slotId] = imageView
            
            slotLayout.setOnClickListener {
                val intent = Intent(this, LoadingActivity::class.java)
                intent.putExtra("captureMode", true)
                intent.putExtra("slot", slotId)
                intent.putExtra("inspectionViewId", inspectionView.id)
                intent.putExtra("inspectionViewDescription", inspectionView.description)
                startActivityForResult(intent, requestCodeBySlot.getValue(slotId))
            }
            
            container.addView(slotLayout)
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
        
        fallbackSlots.forEachIndexed { index, slot ->
            requestCodeBySlot[slot] = 1000 + index
            
            val slotLayout = layoutInflater.inflate(R.layout.item_inspection_slot, container, false)
            slotLayout.id = resources.getIdentifier("slot_${slot}", "id", packageName)
            
            val imageView = slotLayout.findViewById<ImageView>(R.id.slot_image)
            val titleView = slotLayout.findViewById<TextView>(R.id.slot_title)
            
            titleView.text = slot.replace('_', ' ').replaceFirstChar { it.titlecase() }
            previews[slot] = imageView
            
            slotLayout.setOnClickListener {
                val intent = Intent(this, LoadingActivity::class.java)
                intent.putExtra("captureMode", true)
                intent.putExtra("slot", slot)
                startActivityForResult(intent, requestCodeBySlot.getValue(slot))
            }
            
            container.addView(slotLayout)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK && data != null) {
            val url = data.getStringExtra("uploadedUrl") ?: return
            val slot = data.getStringExtra("slot") ?: return
            previews[slot]?.let { iv ->
                Glide.with(this).load(url).into(iv)
            }
        }
    }
}


