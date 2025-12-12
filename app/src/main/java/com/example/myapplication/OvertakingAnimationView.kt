package com.example.myapplication

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Custom view that displays a 3D-style overtaking animation
 * Shows two cars on a road with perspective from behind the user's car
 * Modernized with gradients, shadows, and HUD aesthetics
 */
class OvertakingAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 1. IMPROVEMENT: Use Gradient for the road to simulate "fog" and distance
    // We initialize shaders in onSizeChanged to get correct dimensions
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val laneDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF") // White with slight transparency
        alpha = 200
        style = Paint.Style.FILL
    }
    
    private val roadEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 6f // Thicker edge
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // 2. IMPROVEMENT: Car paints now use Shadows and Gradients
    private val blueCarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Add a subtle drop shadow
        setShadowLayer(12f, 0f, 10f, Color.BLACK)
    }
    
    private val redCarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        setShadowLayer(12f, 0f, 10f, Color.BLACK)
    }
    
    private val carWindowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Darker window for modern look
        color = Color.parseColor("#37474F") 
        style = Paint.Style.FILL
    }
    
    // 3. IMPROVEMENT: Modern Grass/Background color (Darker, less saturated)
    private val grassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1b2e1b") // Very dark green, almost black (Night mode style)
        style = Paint.Style.FILL
    }
    
    // Shadow paint for under the car
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 100
        maskFilter = BlurMaskFilter(15f, BlurMaskFilter.Blur.NORMAL)
    }

    // State variables (kept from original code)
    private var blueCarLanePosition = 0.7f
    private var blueCarDistance = 0.4f
    private var redCarLanePosition = 0.7f
    private var redCarDistance = 0.15f
    private var isAnimating = false
    private var currentAnimator: android.animation.ValueAnimator? = null

    // Initialize Gradients when view size is known
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            // Road Gradient: Darker at top (distance), Lighter at bottom
            roadPaint.shader = LinearGradient(
                w / 2f, h * 0.1f, w / 2f, h.toFloat(),
                Color.parseColor("#121212"), // Deep dark gray at horizon
                Color.parseColor("#3E3E3E"), // Lighter gray near user
                Shader.TileMode.CLAMP
            )
            
            // Blue Car Gradient (Metallic look)
            blueCarPaint.shader = LinearGradient(
                0f, 0f, 0f, 100f, // Coordinates adjusted dynamically in drawCar
                Color.parseColor("#2979FF"), // Lighter Blue
                Color.parseColor("#1565C0"), // Darker Blue
                Shader.TileMode.MIRROR
            )

            // Red Car Gradient
            redCarPaint.shader = LinearGradient(
                0f, 0f, 0f, 100f,
                Color.parseColor("#FF5252"), // Lighter Red
                Color.parseColor("#D32F2F"), // Darker Red
                Shader.TileMode.MIRROR
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        if (width == 0f || height == 0f) return
        
        // Draw Background
        canvas.drawRect(0f, 0f, width, height, grassPaint)
        
        // Perspective Math
        val roadTopWidth = width * 0.25f // Narrower top for more dramatic perspective
        val roadBottomWidth = width * 0.95f
        val roadTop = height * 0.15f
        val roadBottom = height * 1.0f // Go all the way to bottom
        
        // Draw Road Body
        val roadPath = Path().apply {
            moveTo(width / 2 - roadBottomWidth / 2, roadBottom)
            lineTo(width / 2 - roadTopWidth / 2, roadTop)
            lineTo(width / 2 + roadTopWidth / 2, roadTop)
            lineTo(width / 2 + roadBottomWidth / 2, roadBottom)
            close()
        }
        canvas.drawPath(roadPath, roadPaint)
        
        // Draw Lane Dividers
        drawDashedLaneDivider(canvas, width, height, roadTopWidth, roadBottomWidth, roadTop, roadBottom)

        // Draw Cars
        // Note: We need to update shader coordinates for cars based on their size during drawCar
        drawCar(canvas, width, height, roadTopWidth, roadBottomWidth, roadTop, roadBottom, 
                redCarLanePosition, redCarDistance, redCarPaint, "red")
        
        drawCar(canvas, width, height, roadTopWidth, roadBottomWidth, roadTop, roadBottom,
                blueCarLanePosition, blueCarDistance, blueCarPaint, "blue")
    }
    
    private fun drawDashedLaneDivider(
        canvas: Canvas,
        width: Float,
        height: Float,
        roadTopWidth: Float,
        roadBottomWidth: Float,
        roadTop: Float,
        roadBottom: Float
    ) {
        val segments = 8
        val segmentHeight = (roadBottom - roadTop) / segments
        
        for (i in 0 until segments) {
            if (i % 2 == 0) {
                val yStart = roadTop + i * segmentHeight
                val yEnd = roadTop + (i + 1) * segmentHeight
                val progressStart = (yStart - roadTop) / (roadBottom - roadTop)
                val progressEnd = (yEnd - roadTop) / (roadBottom - roadTop)
                val widthAtStart = roadTopWidth + (roadBottomWidth - roadTopWidth) * progressStart
                val widthAtEnd = roadTopWidth + (roadBottomWidth - roadTopWidth) * progressEnd
                
                // Draw tapered line (thinner at top)
                laneDividerPaint.strokeWidth = 2f + (10f * progressStart) 
                
                canvas.drawLine(width / 2, yStart, width / 2, yEnd, laneDividerPaint)
            }
        }
    }
    
    private fun drawCar(
        canvas: Canvas,
        width: Float,
        height: Float,
        roadTopWidth: Float,
        roadBottomWidth: Float,
        roadTop: Float,
        roadBottom: Float,
        lanePosition: Float,
        distance: Float,
        carPaint: Paint,
        carType: String
    ) {
        val yPosition = roadBottom - (roadBottom - roadTop) * distance
        val roadWidthAtY = roadTopWidth + (roadBottomWidth - roadTopWidth) * (1f - distance)
        
        val scale = 0.4f + (1f - distance) * 0.6f
        val carWidth = roadWidthAtY * 0.18f * scale // Slightly wider cars
        val carHeight = carWidth * 1.8f
        
        val laneOffset = (lanePosition - 0.5f) * roadWidthAtY * 0.5f
        val carCenterX = width / 2 + laneOffset
        val carTop = yPosition - carHeight
        val carBottom = yPosition
        
        // 4. IMPROVEMENT: Update Shader locally to match car size (Vertical Gradient)
        val shader = LinearGradient(
            0f, carTop, 0f, carBottom,
            if(carType=="blue") Color.parseColor("#448AFF") else Color.parseColor("#FF5252"),
            if(carType=="blue") Color.parseColor("#1565C0") else Color.parseColor("#B71C1C"),
            Shader.TileMode.CLAMP
        )
        carPaint.shader = shader

        // Draw Soft Shadow under car
        val shadowRect = RectF(
            carCenterX - carWidth * 0.6f,
            carBottom - carHeight * 0.1f,
            carCenterX + carWidth * 0.6f,
            carBottom + carHeight * 0.1f
        )
        canvas.drawOval(shadowRect, shadowPaint)

        // Draw Car Body
        val carRect = RectF(carCenterX - carWidth / 2, carTop, carCenterX + carWidth / 2, carBottom)
        canvas.drawRoundRect(carRect, carWidth * 0.3f, carWidth * 0.3f, carPaint)
        
        // Draw Rear Window (Trapezoid shape using Path for better look)
        val windowPath = Path()
        val wTop = carTop + carHeight * 0.2f
        val wBottom = carTop + carHeight * 0.45f
        val wHalfWidthTop = carWidth * 0.25f
        val wHalfWidthBottom = carWidth * 0.35f
        
        windowPath.moveTo(carCenterX - wHalfWidthTop, wTop)
        windowPath.lineTo(carCenterX + wHalfWidthTop, wTop)
        windowPath.lineTo(carCenterX + wHalfWidthBottom, wBottom)
        windowPath.lineTo(carCenterX - wHalfWidthBottom, wBottom)
        windowPath.close()
        canvas.drawPath(windowPath, carWindowPaint)
        
        // Rear lights (Glow effect)
        val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.FILL
            // Add glow
            maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.SOLID)
        }
        
        canvas.drawCircle(carCenterX - carWidth * 0.3f, carBottom - carHeight * 0.1f, carWidth * 0.12f, lightPaint)
        canvas.drawCircle(carCenterX + carWidth * 0.3f, carBottom - carHeight * 0.1f, carWidth * 0.12f, lightPaint)
    }
    
    fun startOvertakingAnimation(duration: Long = 5000) {
        if (isAnimating) return
        currentAnimator?.cancel()
        isAnimating = true
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
        animator.duration = duration
        // IMPROVEMENT: Use AccelerateDecelerate for more realistic car movement
        animator.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        
        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float
            when {
                progress < 0.2f -> {
                    // Phase 1: Blue moves to left lane
                    val phase = progress / 0.2f
                    blueCarLanePosition = 0.7f - phase * 0.4f  // 0.7 -> 0.3 (right to left)
                    blueCarDistance = 0.4f
                    redCarLanePosition = 0.7f  // Red stays in right lane
                    redCarDistance = 0.15f
                }
                progress < 0.5f -> {
                    // Phase 2: Blue passes red in left lane
                    val phase = (progress - 0.2f) / 0.3f
                    blueCarLanePosition = 0.3f  // Blue in left lane
                    blueCarDistance = 0.4f - phase * 0.25f  // 0.4 -> 0.15 (moves closer/past)
                    redCarLanePosition = 0.7f  // Red stays in right lane
                    redCarDistance = 0.15f
                }
                progress < 0.7f -> {
                    // Phase 3: Blue moves back to right lane
                    val phase = (progress - 0.5f) / 0.2f
                    blueCarLanePosition = 0.3f + phase * 0.4f  // 0.3 -> 0.7 (left to right)
                    blueCarDistance = 0.15f - phase * 0.05f  // 0.15 -> 0.1 (slightly ahead)
                    redCarLanePosition = 0.7f
                    redCarDistance = 0.15f
                }
                else -> {
                    // Phase 4: Both in right lane, blue ahead
                    blueCarLanePosition = 0.7f
                    blueCarDistance = 0.1f  // Blue ahead
                    redCarLanePosition = 0.7f
                    redCarDistance = 0.2f  // Red behind
                }
            }
            
            invalidate()
        }
        
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // Reset to initial idle state: blue ahead, red behind, both in right lane
                blueCarLanePosition = 0.7f
                blueCarDistance = 0.4f
                redCarLanePosition = 0.7f
                redCarDistance = 0.15f
                isAnimating = false
                invalidate()
                currentAnimator = null
            }
        })
        
        currentAnimator = animator
        animator.start()
    }
    
    fun stopAnimation() {
        currentAnimator?.cancel()
        currentAnimator = null
        // Reset to initial idle state
        blueCarLanePosition = 0.7f
        blueCarDistance = 0.4f
        redCarLanePosition = 0.7f
        redCarDistance = 0.15f
        isAnimating = false
        invalidate()
    }
}
