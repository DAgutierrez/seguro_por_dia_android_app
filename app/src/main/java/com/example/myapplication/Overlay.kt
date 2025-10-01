package com.example.myapplication

import android.content.ContentValues.TAG
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.content.res.TypedArray
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import java.util.LinkedList
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results = listOf<BoundingBox>()
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()
    private var guideFramePaint = Paint()
    private var innerFramePaint = Paint()
    private var instructionPaint = Paint()

    private var bounds = Rect()
    private var positioningHelper = VehiclePositioningHelper()
    private var currentInstruction = ""
    private var rotationDegrees: Int = 0
    private var isFrontCamera: Boolean = false
    
    // Image dimensions for letterbox calculation
    private var tensorWidth: Int = 640
    private var tensorHeight: Int = 640
    private var sourceImageWidth: Int = 0
    private var sourceImageHeight: Int = 0
    
    // Positioning strategy
    var positioningStrategy: VehiclePositioningHelper.Strategy = VehiclePositioningHelper.Strategy.HYBRID

    // Toggle to show/hide guide frames while keeping positioning logic active
    var showGuideFrames: Boolean = false

    init {
        initPaints()
        // Read custom attrs
        if (attrs != null && context != null) {
            val ta: TypedArray = context!!.obtainStyledAttributes(attrs, R.styleable.OverlayView)
            try {
                showGuideFrames = ta.getBoolean(R.styleable.OverlayView_showGuideFrames, false)
            } finally {
                ta.recycle()
            }
        }
    }

    fun clear() {
        results = listOf()
        currentInstruction = ""
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        guideFramePaint.reset()
        innerFramePaint.reset()
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
        
        // Guide frame paint (frame exterior)
        guideFramePaint.color = Color.YELLOW
        guideFramePaint.strokeWidth = 4f
        guideFramePaint.style = Paint.Style.STROKE
        
        // Inner frame paint (frame interior 5% más adentro)
        innerFramePaint.color = Color.GREEN
        innerFramePaint.strokeWidth = 3f
        innerFramePaint.style = Paint.Style.STROKE
        
        // Instruction paint
        instructionPaint.color = Color.RED
        instructionPaint.style = Paint.Style.FILL
        instructionPaint.textSize = 60f
        instructionPaint.isFakeBoldText = true
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        Log.d(TAG, "rotationDegrees: $rotationDegrees")
            

        // Compute frames (always calculate for logic), draw only if enabled
        val guideFrame = positioningHelper.getGuideFrame(width, height, sourceImageWidth, sourceImageHeight)
        val innerFramePadding = 0.1f // 10% del guide frame
        val guideFrameWidth = guideFrame.right - guideFrame.left
        val guideFrameHeight = guideFrame.bottom - guideFrame.top
        val innerFrameLeft = guideFrame.left + (guideFrameWidth * innerFramePadding)
        val innerFrameTop = guideFrame.top + (guideFrameHeight * innerFramePadding)
        val innerFrameRight = guideFrame.right - (guideFrameWidth * innerFramePadding)
        val innerFrameBottom = guideFrame.bottom - (guideFrameHeight * innerFramePadding)

        val innerFrame = RectF(innerFrameLeft, innerFrameTop, innerFrameRight, innerFrameBottom)

        if (showGuideFrames) {
            canvas.drawRect(guideFrame, guideFramePaint)
            canvas.drawRect(innerFrame, innerFramePaint)
        }


        // Analyze vehicle position and get instruction (skip if NONE)
        var bestVehicle: BoundingBox? = null
        var bestPositioning: VehiclePositioningHelper.PositioningResult? = null

        if (positioningStrategy != VehiclePositioningHelper.Strategy.NONE) {
            // Evaluate positioning using letterbox strategy - convert vehicle coordinates to pixels
            results.forEach { vehicle ->
                // Convert normalized vehicle coordinates to pixel coordinates for positioning analysis
                val vehicleInPixels = convertNormalizedToPixels(vehicle, width, height)
                
                val positioning = positioningHelper.analyzeVehiclePosition(
                    vehicleInPixels, width, height, positioningStrategy, sourceImageWidth, sourceImageHeight
                )
                if (bestVehicle == null || positioning.vehicleSize > bestPositioning?.vehicleSize ?: 0f) {
                    bestVehicle = vehicleInPixels
                    bestPositioning = positioning
                }
            }
        }

        // Update current instruction
        currentInstruction = if (positioningStrategy == VehiclePositioningHelper.Strategy.NONE) "" else bestPositioning?.instruction ?: ""

        Log.d(TAG, "width:" + canvas.width.toFloat())
        Log.d(TAG, "height:" +canvas.height.toFloat())

        // Draw vehicle bounding boxes using letterbox strategy
        results.forEach { vehicle ->
            val rect = projectLetterboxToCanvas(vehicle, canvas.width, canvas.height)
            canvas.drawRect(rect, boxPaint)
            val drawableText = vehicle.clsName

            textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            val textWidth = bounds.width()
            val textHeight = bounds.height()
            canvas.drawRect(
                rect.left,
                rect.top,
                rect.left + textWidth + BOUNDING_RECT_TEXT_PADDING,
                rect.top + textHeight + BOUNDING_RECT_TEXT_PADDING,
                textBackgroundPaint
            )
            canvas.drawText(drawableText, rect.left, rect.top + bounds.height(), textPaint)
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

    fun setRotationParams(rotationDegrees: Int, isFrontCamera: Boolean) {
        this.rotationDegrees = ((rotationDegrees % 360) + 360) % 360
        this.isFrontCamera = isFrontCamera
    }

    fun setImageDimensions(sourceWidth: Int, sourceHeight: Int) {
        this.sourceImageWidth = sourceWidth
        this.sourceImageHeight = sourceHeight
    }

    /**
     * Proyecta un BoundingBox del espacio del modelo (letterbox) al espacio normalizado del overlay
     */
    private fun projectLetterboxToOverlay(vehicle: BoundingBox, overlayWidth: Int, overlayHeight: Int): BoundingBox {
        // Aproximación simple: usar las coordenadas directamente ya que están normalizadas (0-1)
        return vehicle
    }

    /**
     * Convierte coordenadas normalizadas (0-1) a coordenadas en píxeles para análisis de posicionamiento
     */
    private fun convertNormalizedToPixels(vehicle: BoundingBox, overlayWidth: Int, overlayHeight: Int): BoundingBox {
        val inputWidth = tensorWidth.toFloat()    // 640
        val inputHeight = tensorHeight.toFloat()  // 640
        val origWidth = sourceImageWidth.toFloat()   // ancho real de la cámara
        val origHeight = sourceImageHeight.toFloat() // alto real de la cámara

        // 1. Letterbox scale
        val scale = min(inputWidth / origWidth, inputHeight / origHeight)

        // 2. Padding aplicado durante letterbox
        val padX = (inputWidth - origWidth * scale) / 2f
        val padY = (inputHeight - origHeight * scale) / 2f

        // 3. Remover padding y convertir a coordenadas de imagen original
        val x1 = ((vehicle.x1 * inputWidth) - padX) / scale
        val y1 = ((vehicle.y1 * inputHeight) - padY) / scale
        val x2 = ((vehicle.x2 * inputWidth) - padX) / scale
        val y2 = ((vehicle.y2 * inputHeight) - padY) / scale

        // 4. Mapear a overlay (usando la misma proyección que el canvas)
        val displayScale = min(overlayWidth / origWidth, overlayHeight / origHeight)
        val displayW = origWidth * displayScale
        val displayH = origHeight * displayScale
        val displayOffsetX = (overlayWidth - displayW) / 2f
        val displayOffsetY = (overlayHeight - displayH) / 2f

        val pixelX1 = x1 * displayScale + displayOffsetX
        val pixelY1 = y1 * displayScale + displayOffsetY
        val pixelX2 = x2 * displayScale + displayOffsetX
        val pixelY2 = y2 * displayScale + displayOffsetY

        // Convertir a coordenadas normalizadas (0-1) respecto al overlay
        val normX1 = pixelX1 / overlayWidth
        val normY1 = pixelY1 / overlayHeight
        val normX2 = pixelX2 / overlayWidth
        val normY2 = pixelY2 / overlayHeight

        // Calcular parámetros adicionales del BoundingBox
        val cx = (normX1 + normX2) / 2f
        val cy = (normY1 + normY2) / 2f
        val w = normX2 - normX1
        val h = normY2 - normY1
        
        return BoundingBox(
            x1 = normX1,
            y1 = normY1,
            x2 = normX2,
            y2 = normY2,
            cx = cx,
            cy = cy,
            w = w,
            h = h,
            cnf = vehicle.cnf,
            cls = vehicle.cls,
            clsName = vehicle.clsName
        )
    }

    /**
     * Proyecta un BoundingBox del espacio del modelo (letterbox) al espacio del canvas
     */
    private fun projectLetterboxToCanvas(vehicle: BoundingBox, canvasWidth: Int, canvasHeight: Int): RectF {
        val inputWidth = tensorWidth.toFloat()    // 640
        val inputHeight = tensorHeight.toFloat()  // 640
        val origWidth = sourceImageWidth.toFloat()   // ancho real de la cámara
        val origHeight = sourceImageHeight.toFloat() // alto real de la cámara
    
        // 1. Letterbox scale
        val scale = min(inputWidth / origWidth, inputHeight / origHeight)
    
        // 2. Padding aplicado durante letterbox
        val padX = (inputWidth - origWidth * scale) / 2f
        val padY = (inputHeight - origHeight * scale) / 2f
    
        // 3. Remover padding y convertir a coordenadas de imagen original
        val x1 = ((vehicle.x1 * inputWidth) - padX) / scale
        val y1 = ((vehicle.y1 * inputHeight) - padY) / scale
        val x2 = ((vehicle.x2 * inputWidth) - padX) / scale
        val y2 = ((vehicle.y2 * inputHeight) - padY) / scale
    
        // 4. Mapear a canvas
       // 4) Mostrar en overlay con la MISMA proyección del preview (letterbox FIT)
val displayScale = min(canvasWidth / origWidth, canvasHeight / origHeight)
val displayW = origWidth * displayScale
val displayH = origHeight * displayScale
val displayOffsetX = (canvasWidth - displayW) / 2f
val displayOffsetY = (canvasHeight - displayH) / 2f

val left = x1 * displayScale + displayOffsetX
val top = y1 * displayScale + displayOffsetY
val right = x2 * displayScale + displayOffsetX
val bottom = y2 * displayScale + displayOffsetY

return RectF(left, top, right, bottom)
    }
    
    

    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}