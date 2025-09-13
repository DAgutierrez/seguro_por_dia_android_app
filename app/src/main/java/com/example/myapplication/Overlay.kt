package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import java.util.LinkedList
import kotlin.math.max

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results = listOf<BoundingBox>()
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()
    private var guideFramePaint = Paint()
    private var instructionPaint = Paint()

    private var bounds = Rect()
    private var positioningHelper = VehiclePositioningHelper()
    private var currentInstruction = ""

    init {
        initPaints()
    }

    fun clear() {
        results = listOf()
        currentInstruction = ""
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        guideFramePaint.reset()
        instructionPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 50f

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f

        boxPaint.color = ContextCompat.getColor(context!!, R.color.bounding_box_color)
        boxPaint.strokeWidth = 8F
        boxPaint.style = Paint.Style.STROKE
        
        // Guide frame paint
        guideFramePaint.color = Color.YELLOW
        guideFramePaint.strokeWidth = 4f
        guideFramePaint.style = Paint.Style.STROKE
        
        // Instruction paint
        instructionPaint.color = Color.RED
        instructionPaint.style = Paint.Style.FILL
        instructionPaint.textSize = 60f
        instructionPaint.isFakeBoldText = true
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Draw guide frame
        val guideFrame = positioningHelper.getGuideFrame(width, height)
        canvas.drawRect(guideFrame, guideFramePaint)

        // Analyze vehicle position and get instruction
        var bestVehicle: BoundingBox? = null
        var bestPositioning: VehiclePositioningHelper.PositioningResult? = null

        results.forEach { vehicle ->
            val positioning = positioningHelper.analyzeVehiclePosition(vehicle, width, height)
            if (bestVehicle == null || positioning.vehicleSize > bestPositioning?.vehicleSize ?: 0f) {
                bestVehicle = vehicle
                bestPositioning = positioning
            }
        }

        // Update current instruction
        currentInstruction = bestPositioning?.instruction ?: ""

        // Draw vehicle bounding boxes
        results.forEach { vehicle ->
            val left = vehicle.x1 * width
            val top = vehicle.y1 * height
            val right = vehicle.x2 * width
            val bottom = vehicle.y2 * height

            canvas.drawRect(left, top, right, bottom, boxPaint)
            val drawableText = vehicle.clsName

            textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            val textWidth = bounds.width()
            val textHeight = bounds.height()
            canvas.drawRect(
                left,
                top,
                left + textWidth + BOUNDING_RECT_TEXT_PADDING,
                top + textHeight + BOUNDING_RECT_TEXT_PADDING,
                textBackgroundPaint
            )
            canvas.drawText(drawableText, left, top + bounds.height(), textPaint)
        }

        // Draw instruction at the bottom of the screen
        if (currentInstruction.isNotEmpty()) {
            instructionPaint.getTextBounds(currentInstruction, 0, currentInstruction.length, bounds)
            val instructionX = (width - bounds.width()) / 2f
            val instructionY = height - 50f
            
            // Draw instruction background
            val instructionBgLeft = instructionX - 20f
            val instructionBgTop = instructionY - bounds.height() - 20f
            val instructionBgRight = instructionX + bounds.width() + 20f
            val instructionBgBottom = instructionY + 20f
            
            val instructionBgPaint = Paint().apply {
                color = Color.BLACK
                alpha = 180
                style = Paint.Style.FILL
            }
            
            canvas.drawRect(instructionBgLeft, instructionBgTop, instructionBgRight, instructionBgBottom, instructionBgPaint)
            canvas.drawText(currentInstruction, instructionX, instructionY, instructionPaint)
        }
    }

    fun setResults(boundingBoxes: List<BoundingBox>) {
        results = boundingBoxes
        invalidate()
    }

    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}