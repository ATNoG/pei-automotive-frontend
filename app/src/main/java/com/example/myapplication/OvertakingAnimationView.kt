package com.example.myapplication

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * Custom view that displays a 3D-style overtaking animation
 * Shows two cars on a road with perspective from behind the user's car
 */
class OvertakingAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Paint objects for drawing
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#505050")
        style = Paint.Style.FILL
    }
    
    private val laneDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    
    private val roadEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    
    private val blueCarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3") // Blue - user car (overtaking)
        style = Paint.Style.FILL
    }
    
    private val redCarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F44336") // Red - other car (being overtaken)
        style = Paint.Style.FILL
    }
    
    private val carWindowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#90CAF9") // Light blue
        style = Paint.Style.FILL
    }
    
    private val grassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50") // Green
        style = Paint.Style.FILL
    }

    // Car positions (0.0 to 1.0, where 0 is left lane, 1 is right lane)
    // Blue car (user) - starts ahead in right lane
    private var blueCarLanePosition = 0.7f  // Right lane
    private var blueCarDistance = 0.4f      // Ahead
    
    // Red car (other) - starts behind in right lane
    private var redCarLanePosition = 0.7f   // Right lane
    private var redCarDistance = 0.15f      // Behind
    
    // Animation state
    private var isAnimating = false



    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        
        if (width == 0f || height == 0f) return
        
        // Draw grass background
        canvas.drawRect(0f, 0f, width, height, grassPaint)
        
        // Calculate perspective road dimensions
        val roadTopWidth = width * 0.3f
        val roadBottomWidth = width * 0.9f
        val roadTop = height * 0.1f
        val roadBottom = height * 0.95f
        
        // Draw road with perspective
        val roadPath = Path().apply {
            moveTo(width / 2 - roadBottomWidth / 2, roadBottom)
            lineTo(width / 2 - roadTopWidth / 2, roadTop)
            lineTo(width / 2 + roadTopWidth / 2, roadTop)
            lineTo(width / 2 + roadBottomWidth / 2, roadBottom)
            close()
        }
        canvas.drawPath(roadPath, roadPaint)
        
        // Draw road edges
        canvas.drawLine(
            width / 2 - roadBottomWidth / 2, roadBottom,
            width / 2 - roadTopWidth / 2, roadTop,
            roadEdgePaint
        )
        canvas.drawLine(
            width / 2 + roadBottomWidth / 2, roadBottom,
            width / 2 + roadTopWidth / 2, roadTop,
            roadEdgePaint
        )
        
        // Draw lane dividers (dashed line in middle)
        drawDashedLaneDivider(canvas, width, height, roadTopWidth, roadBottomWidth, roadTop, roadBottom)
        
        // Draw red car (behind) - drawn first so it's behind blue car when overlapping
        drawCar(canvas, width, height, roadTopWidth, roadBottomWidth, roadTop, roadBottom, 
                redCarLanePosition, redCarDistance, redCarPaint, "red")
        
        // Draw blue car (ahead)
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
                
                // Calculate X positions with perspective
                val xStartTop = width / 2
                val xStartBottom = width / 2
                
                val progressStart = (yStart - roadTop) / (roadBottom - roadTop)
                val progressEnd = (yEnd - roadTop) / (roadBottom - roadTop)
                
                val widthAtStart = roadTopWidth + (roadBottomWidth - roadTopWidth) * progressStart
                val widthAtEnd = roadTopWidth + (roadBottomWidth - roadTopWidth) * progressEnd
                
                canvas.drawLine(
                    xStartTop, yStart,
                    xStartBottom, yEnd,
                    laneDividerPaint
                )
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
        // Calculate position based on distance (perspective)
        val yPosition = roadBottom - (roadBottom - roadTop) * distance
        val roadWidthAtY = roadTopWidth + (roadBottomWidth - roadTopWidth) * (1f - distance)
        
        // Scale car based on distance (further = smaller)
        val scale = 0.4f + (1f - distance) * 0.6f
        val carWidth = roadWidthAtY * 0.15f * scale
        val carHeight = carWidth * 1.6f
        
        // Lane position (with perspective)
        val laneOffset = (lanePosition - 0.5f) * roadWidthAtY * 0.5f
        val carCenterX = width / 2 + laneOffset
        
        val carTop = yPosition - carHeight
        val carBottom = yPosition
        
        // Car body
        val carRect = RectF(
            carCenterX - carWidth / 2,
            carTop,
            carCenterX + carWidth / 2,
            carBottom
        )
        canvas.drawRoundRect(carRect, carWidth * 0.15f, carWidth * 0.15f, carPaint)
        
        // Windshield
        val windowRect = RectF(
            carCenterX - carWidth / 3,
            carTop + carHeight * 0.15f,
            carCenterX + carWidth / 3,
            carTop + carHeight * 0.4f
        )
        canvas.drawRoundRect(windowRect, carWidth * 0.1f, carWidth * 0.1f, carWindowPaint)
        
        // Rear lights for red car
        if (carType == "red") {
            val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF0000")
                style = Paint.Style.FILL
            }
            val lightRadius = carWidth * 0.1f
            canvas.drawCircle(
                carCenterX - carWidth / 3,
                carBottom - carHeight * 0.08f,
                lightRadius,
                lightPaint
            )
            canvas.drawCircle(
                carCenterX + carWidth / 3,
                carBottom - carHeight * 0.08f,
                lightRadius,
                lightPaint
            )
        }
    }
    
    private var currentAnimator: android.animation.ValueAnimator? = null
    
    /**
     * Start overtaking animation
     * Blue car (ahead) overtakes red car (behind) by moving left, passing, then returning right
     */
    fun startOvertakingAnimation(duration: Long = 5000) {
        if (isAnimating) return // Don't start if already animating
        
        // Cancel any existing animation
        currentAnimator?.cancel()
        
        isAnimating = true
        
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
        animator.duration = duration
        animator.interpolator = android.view.animation.LinearInterpolator()
        
        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float
            
            // Blue car overtakes red car
            // Phase 1 (0-0.2): Blue moves to left lane
            // Phase 2 (0.2-0.5): Blue accelerates past red in left lane
            // Phase 3 (0.5-0.7): Blue moves back to right lane ahead of red
            // Phase 4 (0.7-1.0): Both continue in right lane, blue ahead
            
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
