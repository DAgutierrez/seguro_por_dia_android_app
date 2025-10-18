package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        setupNavigation()
    }
    
    private fun setupNavigation() {
        // Inspection Button (now a MaterialCardView)
        findViewById<MaterialCardView?>(R.id.btn_inspection)?.setOnClickListener {
            // Clear all SharedPreferences data before starting inspection
            clearAllInspectionData()
            
            val intent = Intent(this@MainActivity, InspectionActivity::class.java)
            startActivity(intent)
        }
    }
    
    private fun clearAllInspectionData() {
        try {
            val sharedPref = getSharedPreferences("inspection_data", android.content.Context.MODE_PRIVATE)
            val editor = sharedPref.edit()
            editor.clear()
            editor.apply()
            Log.d("MainActivity", "All inspection data cleared from SharedPreferences")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error clearing inspection data: ${e.message}")
        }
    }
}