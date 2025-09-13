package com.example.myapplication

import android.graphics.RectF
import kotlin.math.abs

class VehiclePositioningHelper {
    
    companion object {
        private const val FRAME_PADDING_PERCENT = 0.05f // 5% padding del frame guía
        private const val INNER_FRAME_PADDING_PERCENT = 0.1f // 10% padding del frame interno (5% del guía + 5% extra)
        private const val CENTER_THRESHOLD = 0.05f // Umbral para considerar que está centrado (5%)
    }
    
    data class PositioningResult(
        val instruction: String,
        val isVehicleInFrame: Boolean,
        val vehicleSize: Float,
        val isCentered: Boolean
    )
    
    /**
     * Analiza la posición del vehículo y retorna la instrucción correspondiente
     * Implementa la nueva lógica basada en dos frames
     */
    fun analyzeVehiclePosition(vehicle: BoundingBox, screenWidth: Int, screenHeight: Int): PositioningResult {
        // Calcular el frame guía (exterior)
        val guideFrameLeft = FRAME_PADDING_PERCENT
        val guideFrameTop = FRAME_PADDING_PERCENT
        val guideFrameRight = 1f - FRAME_PADDING_PERCENT
        val guideFrameBottom = 1f - FRAME_PADDING_PERCENT
        val guideFrame = RectF(guideFrameLeft, guideFrameTop, guideFrameRight, guideFrameBottom)
        
        // Calcular el frame interno (interior)
        val innerFrameLeft = INNER_FRAME_PADDING_PERCENT
        val innerFrameTop = INNER_FRAME_PADDING_PERCENT
        val innerFrameRight = 1f - INNER_FRAME_PADDING_PERCENT
        val innerFrameBottom = 1f - INNER_FRAME_PADDING_PERCENT
        val innerFrame = RectF(innerFrameLeft, innerFrameTop, innerFrameRight, innerFrameBottom)
        
        // Calcular centros de los frames
        val guideFrameCenterX = (guideFrameLeft + guideFrameRight) / 2f
        val guideFrameCenterY = (guideFrameTop + guideFrameBottom) / 2f
        val innerFrameCenterX = (innerFrameLeft + innerFrameRight) / 2f
        val innerFrameCenterY = (innerFrameTop + innerFrameBottom) / 2f
        
        // Calcular centro del vehículo
        val vehicleCenterX = vehicle.cx
        val vehicleCenterY = vehicle.cy
        
        // 1. Verificar Estado Perfecto
        val isInGuideFrame = isVehicleInFrame(vehicle, guideFrame)
        val isInInnerFrame = isVehicleInFrame(vehicle, innerFrame)
        val isHorizontallyCentered = abs(vehicleCenterX - innerFrameCenterX) <= CENTER_THRESHOLD
        val isVerticallyCentered = abs(vehicleCenterY - innerFrameCenterY) <= CENTER_THRESHOLD
        
        if (isInGuideFrame && isInInnerFrame && isHorizontallyCentered && isVerticallyCentered) {
            return PositioningResult(
                instruction = "Posición correcta",
                isVehicleInFrame = true,
                vehicleSize = maxOf(vehicle.w, vehicle.h),
                isCentered = true
            )
        }
        
        // 2. Verificar Distancia Adelante/Atrás
        if (isInInnerFrame) {
            return PositioningResult(
                instruction = "Acércate",
                isVehicleInFrame = true,
                vehicleSize = maxOf(vehicle.w, vehicle.h),
                isCentered = false
            )
        }
        
        if (!isInGuideFrame) {
            return PositioningResult(
                instruction = "Aléjate",
                isVehicleInFrame = false,
                vehicleSize = maxOf(vehicle.w, vehicle.h),
                isCentered = false
            )
        }
        
        // 3. Verificar Alineación Horizontal
        val horizontalInstruction = checkHorizontalAlignment(vehicleCenterX, innerFrameCenterX)
        
        // 4. Verificar Alineación Vertical
        val verticalInstruction = checkVerticalAlignment(vehicleCenterY, innerFrameCenterY)
        
        // Combinar instrucciones si hay múltiples
        val instructions = listOfNotNull(horizontalInstruction, verticalInstruction)
        val finalInstruction = if (instructions.isEmpty()) {
            "Posición correcta"
        } else {
            instructions.joinToString(", ")
        }
        
        return PositioningResult(
            instruction = finalInstruction,
            isVehicleInFrame = true,
            vehicleSize = maxOf(vehicle.w, vehicle.h),
            isCentered = instructions.isEmpty()
        )
    }
    
    /**
     * Verifica si el vehículo está completamente dentro de un frame
     */
    private fun isVehicleInFrame(vehicle: BoundingBox, frame: RectF): Boolean {
        return vehicle.x1 >= frame.left && 
               vehicle.x2 <= frame.right && 
               vehicle.y1 >= frame.top && 
               vehicle.y2 <= frame.bottom
    }
    
    /**
     * Regla 3: Verifica alineación horizontal basada en el centro del inner frame
     */
    private fun checkHorizontalAlignment(vehicleCenterX: Float, innerFrameCenterX: Float): String? {
        val horizontalDifference = vehicleCenterX - innerFrameCenterX
        
        return when {
            horizontalDifference > CENTER_THRESHOLD -> "Muévete a la izquierda" // Más a la derecha que el centro
            horizontalDifference < -CENTER_THRESHOLD -> "Muévete a la derecha" // Más a la izquierda que el centro
            else -> null // Está centrado horizontalmente
        }
    }
    
    /**
     * Regla 4: Verifica alineación vertical basada en el centro del inner frame
     */
    private fun checkVerticalAlignment(vehicleCenterY: Float, innerFrameCenterY: Float): String? {
        val verticalDifference = vehicleCenterY - innerFrameCenterY
        
        return when {
            verticalDifference > CENTER_THRESHOLD -> "Muévete más abajo" // Más arriba que el centro
            verticalDifference < -CENTER_THRESHOLD -> "Muévete más arriba" // Más abajo que el centro
            else -> null // Está centrado verticalmente
        }
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
