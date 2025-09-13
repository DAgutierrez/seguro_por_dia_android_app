package com.example.myapplication

import android.graphics.RectF
import kotlin.math.abs

class VehiclePositioningHelper {
    
    companion object {
        private const val FRAME_PADDING_PERCENT = 0.05f // 5% padding (aproximadamente 20px en pantalla normal)
        private const val MIN_VEHICLE_SIZE = 0.15f // Tamaño mínimo del vehículo (15% de la pantalla)
        private const val MAX_VEHICLE_SIZE = 0.85f // Tamaño máximo del vehículo (85% de la pantalla)
        private const val CENTER_THRESHOLD = 0.1f // Umbral para considerar que está centrado
    }
    
    data class PositioningResult(
        val instruction: String,
        val isVehicleInFrame: Boolean,
        val vehicleSize: Float,
        val isCentered: Boolean
    )
    
    /**
     * Analiza la posición del vehículo y retorna la instrucción correspondiente
     */
    fun analyzeVehiclePosition(vehicle: BoundingBox, screenWidth: Int, screenHeight: Int): PositioningResult {
        // Calcular el frame guía con padding
        val frameLeft = FRAME_PADDING_PERCENT
        val frameTop = FRAME_PADDING_PERCENT
        val frameRight = 1f - FRAME_PADDING_PERCENT
        val frameBottom = 1f - FRAME_PADDING_PERCENT
        
        val guideFrame = RectF(frameLeft, frameTop, frameRight, frameBottom)
        
        // Calcular el tamaño del vehículo
        val vehicleWidth = vehicle.w
        val vehicleHeight = vehicle.h
        val vehicleSize = maxOf(vehicleWidth, vehicleHeight)
        
        // Verificar si el vehículo está dentro del frame
        val isVehicleInFrame = isVehicleInGuideFrame(vehicle, guideFrame)
        
        // Calcular el centro del vehículo
        val vehicleCenterX = vehicle.cx
        val vehicleCenterY = vehicle.cy
        
        // Verificar si está centrado
        val isCentered = isVehicleCentered(vehicleCenterX, vehicleCenterY)
        
        // Determinar la instrucción
        val instruction = when {
            !isVehicleInFrame -> getPositionInstruction(vehicle, guideFrame)
            vehicleSize < MIN_VEHICLE_SIZE -> "Acércate"
            vehicleSize > MAX_VEHICLE_SIZE -> "Aléjate"
            !isCentered -> getCenteringInstruction(vehicleCenterX, vehicleCenterY)
            else -> "Perfecto"
        }
        
        return PositioningResult(
            instruction = instruction,
            isVehicleInFrame = isVehicleInFrame,
            vehicleSize = vehicleSize,
            isCentered = isCentered
        )
    }
    
    private fun isVehicleInGuideFrame(vehicle: BoundingBox, guideFrame: RectF): Boolean {
        return vehicle.x1 >= guideFrame.left && 
               vehicle.x2 <= guideFrame.right && 
               vehicle.y1 >= guideFrame.top && 
               vehicle.y2 <= guideFrame.bottom
    }
    
    private fun isVehicleCentered(centerX: Float, centerY: Float): Boolean {
        val centerThresholdX = CENTER_THRESHOLD
        val centerThresholdY = CENTER_THRESHOLD
        
        return abs(centerX - 0.5f) <= centerThresholdX && 
               abs(centerY - 0.5f) <= centerThresholdY
    }
    
    private fun getPositionInstruction(vehicle: BoundingBox, guideFrame: RectF): String {
        val instructions = mutableListOf<String>()
        
        // Verificar posición horizontal
        if (vehicle.x1 < guideFrame.left) {
            instructions.add("Mueve a la derecha")
        } else if (vehicle.x2 > guideFrame.right) {
            instructions.add("Mueve a la izquierda")
        }
        
        // Verificar posición vertical
        if (vehicle.y1 < guideFrame.top) {
            instructions.add("Mueve abajo")
        } else if (vehicle.y2 > guideFrame.bottom) {
            instructions.add("Mueve arriba")
        }
        
        return if (instructions.isEmpty()) {
            "Centra el vehículo"
        } else {
            instructions.joinToString(", ")
        }
    }
    
    private fun getCenteringInstruction(centerX: Float, centerY: Float): String {
        val instructions = mutableListOf<String>()
        
        // Verificar centrado horizontal
        if (centerX < 0.5f - CENTER_THRESHOLD) {
            instructions.add("Mueve a la derecha")
        } else if (centerX > 0.5f + CENTER_THRESHOLD) {
            instructions.add("Mueve a la izquierda")
        }
        
        // Verificar centrado vertical
        if (centerY < 0.5f - CENTER_THRESHOLD) {
            instructions.add("Mueve abajo")
        } else if (centerY > 0.5f + CENTER_THRESHOLD) {
            instructions.add("Mueve arriba")
        }
        
        return instructions.joinToString(", ")
    }
    
    /**
     * Retorna el frame guía para dibujar en el overlay
     */
    fun getGuideFrame(screenWidth: Int, screenHeight: Int): RectF {
        val paddingX = screenWidth * FRAME_PADDING_PERCENT
        val paddingY = screenHeight * FRAME_PADDING_PERCENT
        
        return RectF(
            paddingX,
            paddingY,
            screenWidth - paddingX,
            screenHeight - paddingY
        )
    }
}
