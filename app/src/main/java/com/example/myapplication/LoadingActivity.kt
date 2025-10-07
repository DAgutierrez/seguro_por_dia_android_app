package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class LoadingActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var launchRunnable: Runnable? = null
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_loading)

        val captureMode = intent.getBooleanExtra("captureMode", false)
        val slot = intent.getStringExtra("slot")
        val inspectionViewId = intent.getIntExtra("inspectionViewId", -1)
        val inspectionViewDescription = intent.getStringExtra("inspectionViewDescription")

        launchRunnable = Runnable {
            if (isFinishing || launched) return@Runnable
            launched = true
            val cameraIntent = Intent(this, CameraViewActivity::class.java)
            cameraIntent.putExtra("captureMode", captureMode)
            cameraIntent.putExtra("slot", slot)
            cameraIntent.putExtra("inspectionViewId", inspectionViewId)
            cameraIntent.putExtra("inspectionViewDescription", inspectionViewDescription)
            startActivityForResult(cameraIntent, 9999)
        }
        handler.postDelayed(launchRunnable!!, 800)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9999) {
            // prevent any pending re-launches
            launchRunnable?.let { handler.removeCallbacks(it) }
            setResult(resultCode, data)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        launchRunnable?.let { handler.removeCallbacks(it) }
    }
}
