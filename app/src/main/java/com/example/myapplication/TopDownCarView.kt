package com.example.myapplication

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 2D top-down view showing user car and surrounding vehicles
 * Grid: x (-5, 5), y (-8, 8)
 * User car fixed at x=2, y=0
 */
class TopDownCarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Coordinate system bounds (increased for less zoom)
    private val gridMinX = -18f
    private val gridMaxX = 18f
    private val gridMinY = -25f
    private val gridMaxY = 25f
    
    // User car fixed position in grid coordinates (in right lane)
    private val userCarGridX = 4.7f  // Position in right lane
    private val userCarGridY = 0f
    
    // Other car positions (relative to user car in meters)
    private val otherCars = mutableListOf<CarPosition>()
    
    // Emergency vehicle positions (relative to user car in meters)
    private val evCars = mutableListOf<CarPosition>()
    
    // Paint objects
    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#040404ff")  // Dark grass
        style = Paint.Style.FILL
    }
    
    private val roadPaint = Paint().apply {
        color = Color.parseColor("#2E2E2E")  // Dark asphalt
        style = Paint.Style.FILL
    }
    
    private val roadEdgePaint = Paint().apply {
        color = Color.parseColor("#FFFFFF")  // White edge line
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    
    private val centerLinePaint = Paint().apply {
        color = Color.parseColor("#FDD835")  // Yellow center line
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(20f, 15f), 0f)
    }
    
    private val laneDividerPaint = Paint().apply {
        color = Color.parseColor("#ffffffff")  // White lane divider
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(15f, 15f), 0f)
    }
    
    private val userCarPaint = Paint().apply {
        color = Color.parseColor("#E53935")  // Red
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(8f, 0f, 0f, Color.parseColor("#AA000000"))
    }
    
    private val otherCarPaint = Paint().apply {
        color = Color.parseColor("#757575")  // Gray
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(6f, 0f, 0f, Color.parseColor("#AA000000"))
    }
    
    private val evCarPaint = Paint().apply {
        color = Color.parseColor("#2196F3")  // Blue for emergency vehicle
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(8f, 0f, 0f, Color.parseColor("#AA0000FF"))
    }
    
    private val evCarStrokePaint = Paint().apply {
        color = Color.parseColor("#FF4444")  // Red stroke ring
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = Color.parseColor("#9E9E9E")
        textSize = 24f
        isAntiAlias = true
    }
    
    data class CarPosition(val x: Float, val y: Float)
    
    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // For shadow support
    }
    
    /**
     * Update positions of other cars relative to user
     * @param cars List of car positions in meters relative to user (x: lateral, y: longitudinal)
     */
    fun updateOtherCars(cars: List<CarPosition>) {
        android.util.Log.d("TOP_DOWN_VIEW", "Received ${cars.size} cars to draw")
        otherCars.clear()
        otherCars.addAll(cars)
        invalidate()
    }
    
    /**
     * Update positions of emergency vehicles relative to user
     */
    fun updateEVCars(cars: List<CarPosition>) {
        android.util.Log.d("TOP_DOWN_VIEW", "Received ${cars.size} EV cars to draw")
        evCars.clear()
        evCars.addAll(cars)
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw background (grass/surroundings)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        
        // Calculate scale factors
        val gridWidth = gridMaxX - gridMinX
        val gridHeight = gridMaxY - gridMinY
        
        val scaleX = width / gridWidth
        val scaleY = height / gridHeight
        val scale = min(scaleX, scaleY) * 0.95f // 95% to leave small margins
        
        val offsetX = width / 2f
        val offsetY = height / 2f
        
        // Helper function to convert grid coordinates to screen coordinates
        fun gridToScreen(gridX: Float, gridY: Float): Pair<Float, Float> {
            val screenX = gridX * scale + offsetX
            val screenY = height - (gridY * scale + offsetY) // Flip Y axis
            return Pair(screenX, screenY)
        }
        
        // Draw road centered on screen (2 lanes: one each direction)
        val laneWidth = 9f // Width of each lane
        val roadWidth = laneWidth * 2f // Total road width
        val roadCenterX = 0f // Center the road at x=0
        
        val (roadLeftScreen, roadTopScreen) = gridToScreen(roadCenterX - roadWidth/2, gridMaxY)
        val (roadRightScreen, roadBottomScreen) = gridToScreen(roadCenterX + roadWidth/2, gridMinY)
        
        canvas.drawRect(roadLeftScreen, roadTopScreen, roadRightScreen, roadBottomScreen, roadPaint)
        
        // Draw road edges (white lines)
        canvas.drawLine(roadLeftScreen, roadTopScreen, roadLeftScreen, roadBottomScreen, roadEdgePaint)
        canvas.drawLine(roadRightScreen, roadTopScreen, roadRightScreen, roadBottomScreen, roadEdgePaint)
        
        // Draw center line (yellow dashed) - divides the two opposing lanes
        val (centerXScreen, _) = gridToScreen(roadCenterX, 0f)
        canvas.drawLine(centerXScreen, roadTopScreen, centerXScreen, roadBottomScreen, centerLinePaint)
        
        // Car radius in grid units
        val carRadius = 2.2f
        
        // Draw other cars
        for (car in otherCars) {
            // car.x and car.y are already in meters, direction-aware from MainActivity
            // Convert to grid coordinates (1 grid unit = ~2 meters)
            val gridX = userCarGridX + (car.x / 0.25f)
            val gridY = userCarGridY + (car.y / 1f)
            
            // Only draw if within reasonable bounds
            if (gridX >= gridMinX && gridX <= gridMaxX && gridY >= gridMinY && gridY <= gridMaxY) {
                val (screenX, screenY) = gridToScreen(gridX, gridY)
                canvas.drawCircle(screenX, screenY, carRadius * scale, otherCarPaint)
            }
        }
        
        // Draw emergency vehicles (blue with red ring)
        for (car in evCars) {
            val gridX = userCarGridX + (car.x / 0.25f)
            val gridY = userCarGridY + (car.y / 1f)
            
            if (gridX >= gridMinX && gridX <= gridMaxX && gridY >= gridMinY && gridY <= gridMaxY) {
                val (screenX, screenY) = gridToScreen(gridX, gridY)
                canvas.drawCircle(screenX, screenY, carRadius * scale, evCarPaint)
                canvas.drawCircle(screenX, screenY, carRadius * scale + 3f, evCarStrokePaint)
            }
        }
        
        // Draw user car in right lane
        val (userScreenX, userScreenY) = gridToScreen(userCarGridX, userCarGridY)
        canvas.drawCircle(userScreenX, userScreenY, carRadius * scale, userCarPaint)
    }
}
