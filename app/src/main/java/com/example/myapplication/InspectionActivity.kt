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

class InspectionActivity : AppCompatActivity() {
    private val slots = listOf(
        "lateral_izquierdo",
        "lateral_derecho",
        "diagonal_frontal_derecho",
        "diagonal_frontal_izquierdo",
        "diagonal_trasero_derecho",
        "diagonal_trasero_izquierdo"
    )

    private val requestCodeBySlot = slots.mapIndexed { idx, s -> s to (1000 + idx) }.toMap()
    private val previews = hashMapOf<String, ImageView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspection)

        slots.forEach { slot ->
            val containerId = resources.getIdentifier("slot_${slot}", "id", packageName)
            val container = findViewById<LinearLayout>(containerId)
            val iv = container.findViewById<ImageView>(R.id.slot_image)
            val tv = container.findViewById<TextView>(R.id.slot_title)
            tv.text = slot.replace('_', ' ').replaceFirstChar { it.titlecase() }
            previews[slot] = iv

            container.setOnClickListener {
                val intent = Intent(this, CameraViewActivity::class.java)
                intent.putExtra("captureMode", true)
                intent.putExtra("slot", slot)
                startActivityForResult(intent, requestCodeBySlot.getValue(slot))
            }
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


