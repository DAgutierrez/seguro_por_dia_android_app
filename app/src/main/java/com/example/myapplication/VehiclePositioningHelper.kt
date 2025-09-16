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
     * Implementa la nueva lógica mejorada con detección de lados específicos
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
        val innerFrameCenterX = (innerFrameLeft + innerFrameRight) / 2f
        val innerFrameCenterY = (innerFrameTop + innerFrameBottom) / 2f
        
        // Calcular centro del vehículo
        val vehicleCenterX = vehicle.cx
        val vehicleCenterY = vehicle.cy
        
        // Analizar qué lados del vehículo salen del frame guía
        val sidesOutOfGuideFrame = analyzeSidesOutOfFrame(vehicle, guideFrame)
        
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
        
        // Verificar si sale del frame guía con al menos dos lados
        if (sidesOutOfGuideFrame.count() >= 2) {
            return PositioningResult(
                instruction = "Aléjate",
                isVehicleInFrame = false,
                vehicleSize = maxOf(vehicle.w, vehicle.h),
                isCentered = false
            )
        }
        
        // 3. Verificar Alineación Horizontal (incluye casos de salida por un lado)
        val horizontalInstruction = checkHorizontalAlignmentWithSides(
            vehicleCenterX, innerFrameCenterX, sidesOutOfGuideFrame
        )
        
        // 4. Verificar Alineación Vertical (incluye casos de salida por un lado)
        val verticalInstruction = checkVerticalAlignmentWithSides(
            vehicleCenterY, innerFrameCenterY, sidesOutOfGuideFrame
        )
        
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
     * Analiza qué lados del vehículo salen del frame
     */
    private fun analyzeSidesOutOfFrame(vehicle: BoundingBox, frame: RectF): Set<Side> {
        val sidesOut = mutableSetOf<Side>()
        
        if (vehicle.x1 < frame.left) sidesOut.add(Side.LEFT)
        if (vehicle.x2 > frame.right) sidesOut.add(Side.RIGHT)
        if (vehicle.y1 < frame.top) sidesOut.add(Side.TOP)
        if (vehicle.y2 > frame.bottom) sidesOut.add(Side.BOTTOM)
        
        return sidesOut
    }
    
    /**
     * Regla 3: Verifica alineación horizontal incluyendo casos de salida por lados específicos
     */
    private fun checkHorizontalAlignmentWithSides(
        vehicleCenterX: Float, 
        innerFrameCenterX: Float, 
        sidesOutOfGuideFrame: Set<Side>
    ): String? {
        // Caso especial: sale solo por el lado derecho
        if (sidesOutOfGuideFrame == setOf(Side.RIGHT)) {
            return "Muévete a la izquierda"
        }
        
        // Caso especial: sale solo por el lado izquierdo
        if (sidesOutOfGuideFrame == setOf(Side.LEFT)) {
            return "Muévete a la derecha"
        }
        
        // Verificación normal de centrado horizontal
        val horizontalDifference = vehicleCenterX - innerFrameCenterX
        
        return when {
            horizontalDifference > CENTER_THRESHOLD -> "Muévete a la izquierda" // Más a la derecha que el centro
            horizontalDifference < -CENTER_THRESHOLD -> "Muévete a la derecha" // Más a la izquierda que el centro
            else -> null // Está centrado horizontalmente
        }
    }
    
    /**
     * Regla 4: Verifica alineación vertical incluyendo casos de salida por lados específicos
     */
    private fun checkVerticalAlignmentWithSides(
        vehicleCenterY: Float, 
        innerFrameCenterY: Float, 
        sidesOutOfGuideFrame: Set<Side>
    ): String? {
        // Caso especial: sale solo por el lado superior
        if (sidesOutOfGuideFrame == setOf(Side.TOP)) {
            return "Muévete más abajo"
        }
        
        // Caso especial: sale solo por el lado inferior
        if (sidesOutOfGuideFrame == setOf(Side.BOTTOM)) {
            return "Muévete más arriba"
        }
        
        // Verificación normal de centrado vertical
        val verticalDifference = vehicleCenterY - innerFrameCenterY
        
        return when {
            verticalDifference > CENTER_THRESHOLD -> "Muévete más abajo" // Más arriba que el centro
            verticalDifference < -CENTER_THRESHOLD -> "Muévete más arriba" // Más abajo que el centro
            else -> null // Está centrado verticalmente
        }
    }
    
    /**
     * Enum para identificar los lados del frame
     */
    private enum class Side {
        LEFT, RIGHT, TOP, BOTTOM
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
