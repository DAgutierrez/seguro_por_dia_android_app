package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        setupNavigation()
    }
    
    private fun setupNavigation() {
        // Inspection Button
        findViewById<Button?>(R.id.btn_inspection)?.setOnClickListener {
            val intent = Intent(this@MainActivity, InspectionActivity::class.java)
            startActivity(intent)
        }
    }
}