package com.example.myapplication

import android.content.Intent
import android.os.Bundle
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
            val intent = Intent(this@MainActivity, InspectionActivity::class.java)
            startActivity(intent)
        }
    }
}