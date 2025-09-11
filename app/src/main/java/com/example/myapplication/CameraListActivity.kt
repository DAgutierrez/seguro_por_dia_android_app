package com.example.myapplication

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.atan


class CameraListActivity : AppCompatActivity() {
    
    private lateinit var listView: ListView
    private lateinit var cameraInfoList: MutableList<String>
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_list)
        
        listView = findViewById(R.id.camera_list_view)
        cameraInfoList = mutableListOf()
        
        // Back button
        findViewById<ImageButton>(R.id.back_button).setOnClickListener {
            finish()
        }
        
        detectAndDisplayCameras()
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, cameraInfoList)
        listView.adapter = adapter
    }
    
    private fun detectAndDisplayCameras() {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList
            
            cameraInfoList.add("📱 Dispositivo: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            cameraInfoList.add("📷 Total de cámaras físicas: ${cameraIds.size}")
            cameraInfoList.add("")
            
            for (cameraId in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                
                // Solo cámaras traseras
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
                
                val cameraInfo = analyzeCamera(cameraId, characteristics)
                cameraInfoList.add(cameraInfo)
            }
            
            if (cameraInfoList.size <= 3) {
                cameraInfoList.add("❌ No se encontraron cámaras traseras")
            }
            
        } catch (e: Exception) {
            Log.e("CameraList", "Error detecting cameras: ${e.message}")
            cameraInfoList.add("❌ Error al detectar cámaras: ${e.message}")
        }
    }
    
    private fun analyzeCamera(cameraId: String, characteristics: CameraCharacteristics): String {
        val info = StringBuilder()
        
        // Información básica
        info.append("📷 Cámara ID: $cameraId\n")
        
        // Distancias focales
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        if (focalLengths != null && focalLengths.isNotEmpty()) {
            val focal = focalLengths[0]
            info.append("🔍 Distancia focal: ${focal}mm\n")
            
            // Calcular FOV si tenemos sensor size
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (sensorSize != null) {
                val horizontalAngle = 2 * (atan((sensorSize.width / (2 * focal)).toDouble()) * 180.0 / Math.PI)
                val verticalAngle = 2 * (atan((sensorSize.height / (2 * focal)).toDouble()) * 180.0 / Math.PI)
                
                info.append("📐 FOV Horizontal: ${String.format("%.1f", horizontalAngle)}°\n")
                info.append("📐 FOV Vertical: ${String.format("%.1f", verticalAngle)}°\n")
                
                // Determinar tipo de cámara
                when {
                    focal < 2.5f || horizontalAngle > 90 -> {
                        info.append("🔍 TIPO: ULTRA WIDE (0.5x)\n")
                    }
                    focal < 4.0f -> {
                        info.append("🔍 TIPO: WIDE (1x)\n")
                    }
                    else -> {
                        info.append("🔍 TIPO: TELEPHOTO (2x+)\n")
                    }
                }
            }
        }
        
        // Rango de zoom
        val zoomRange = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
        if (zoomRange != null) {
            info.append("🔍 Zoom digital máximo: ${String.format("%.1f", zoomRange)}x\n")
        }
        
        // Resolución máxima
        val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (streamConfigMap != null) {
            val outputSizes = streamConfigMap.getOutputSizes(android.graphics.ImageFormat.JPEG)
            if (outputSizes != null && outputSizes.isNotEmpty()) {
                val maxSize = outputSizes.maxByOrNull { it.width * it.height }
                if (maxSize != null) {
                    info.append("📐 Resolución máxima: ${maxSize.width}x${maxSize.height}\n")
                }
            }
        }
        
        // Capacidades especiales
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        if (capabilities != null) {
            val specialFeatures = mutableListOf<String>()
            if (capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
                specialFeatures.add("Manual")
            }
            if (capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                specialFeatures.add("RAW")
            }
            if (capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE)) {
                specialFeatures.add("Burst")
            }
            
            if (specialFeatures.isNotEmpty()) {
                info.append("⚡ Características: ${specialFeatures.joinToString(", ")}\n")
            }
        }
        
        // Información del sensor
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (sensorSize != null) {
            info.append("📏 Tamaño sensor: ${String.format("%.1f", sensorSize.width)}x${String.format("%.1f", sensorSize.height)}mm\n")
        }
        
        info.append("─".repeat(50))
        
        return info.toString()
    }
}
