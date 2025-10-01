package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class LoadingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_loading)

        val captureMode = intent.getBooleanExtra("captureMode", false)
        val slot = intent.getStringExtra("slot")

        // Delay to show loading screen
        Handler(Looper.getMainLooper()).postDelayed({
            val cameraIntent = Intent(this, CameraViewActivity::class.java)
            cameraIntent.putExtra("captureMode", captureMode)
            cameraIntent.putExtra("slot", slot)
            startActivityForResult(cameraIntent, 9999)
        }, 800) // shorter loading
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9999) {
            setResult(resultCode, data)
            finish()
        }
    }
}
