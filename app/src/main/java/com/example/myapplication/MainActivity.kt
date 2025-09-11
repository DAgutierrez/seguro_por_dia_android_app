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
        // Camera View Button
        findViewById<Button>(R.id.btn_camera_view).setOnClickListener {
            val intent = Intent(this@MainActivity, CameraViewActivity::class.java)
            startActivity(intent)
        }

        findViewById<Button>(R.id.btn_camera_view_old).setOnClickListener {
            val intent = Intent(this@MainActivity, CameraViewActivityOld::class.java)
            startActivity(intent)
        }
        
        // Camera List Button
        findViewById<Button>(R.id.btn_camera_list).setOnClickListener {
            val intent = Intent(this@MainActivity, CameraListActivity::class.java)
            startActivity(intent)
        }
    }
}