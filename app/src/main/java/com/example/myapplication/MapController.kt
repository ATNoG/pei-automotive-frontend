package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.myapplication.config.AppConfig
import com.example.myapplication.navigation.NavigationConfig
import com.example.myapplication.navigation.models.LatLng as NavLatLng
import com.example.myapplication.navigation.models.NavigationRoute
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.Property
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.LineString

/**
 * MapController - encapsulates MapLibre map handling.
 *
 * Usage:
 *   val mapController = MapController(context, mapView)
 *   mapController.init { /* called when style loaded */ }
 *   mapController.setSingleLocation(lat, lon, bearing)
 *   mapController.simulateRoute(listOf(Pair(lat,lon), ...))
 */
class MapController(
    private val context: Context,
    private val mapView: MapView
) : OnMapReadyCallback {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mapLibreMap: MapLibreMap? = null
    private var styleLoadedCallback: (() -> Unit)? = null

    companion object {
        private const val TAG = "MapController"
        private val STYLE_URL_DARK = "https://api.maptiler.com/maps/streets-v2-dark/style.json?key=${BuildConfig.MAPTILER_API_KEY}"
        private val STYLE_URL_LIGHT = "https://api.maptiler.com/maps/streets-v2/style.json?key=${BuildConfig.MAPTILER_API_KEY}"
        private const val ARROW_SOURCE_ID = "arrow-source"
        private const val ARROW_LAYER_ID = "arrow-layer"
        private const val ARROW_IMAGE_ID = "arrow-image"
        
        // Second car (other vehicle)
        private const val OTHER_CAR_SOURCE_ID = "other-car-source"
        private const val OTHER_CAR_LAYER_ID = "other-car-layer"
        private const val OTHER_CAR_IMAGE_ID = "other-car-image"
        
        // Route line constants
        private const val ROUTE_SOURCE_ID = "route-source"
        private const val ROUTE_LAYER_ID = "route-layer"
        private const val ROUTE_CASING_LAYER_ID = "route-casing-layer"
        
        // Traveled route constants (gray)
        private const val ROUTE_TRAVELED_SOURCE_ID = "route-traveled-source"
        private const val ROUTE_TRAVELED_LAYER_ID = "route-traveled-layer"
        
        // Destination marker constants
        private const val DESTINATION_SOURCE_ID = "destination-source"
        private const val DESTINATION_LAYER_ID = "destination-layer"
        private const val DESTINATION_IMAGE_ID = "destination-flag-image"
        
        // Accident marker constants
        private const val ACCIDENT_SOURCE_ID = "accident-source"
        private const val ACCIDENT_LAYER_ID = "accident-layer"
        private const val ACCIDENT_IMAGE_ID = "accident-marker-image"
        private const val ACCIDENT_MARKER_ICON_SIZE = 1.2f  // Size for visibility on road
    }

    // simulation state
    private var routePoints: List<Point> = emptyList()
    private var routeIndex = 0
    private var routeRunnable: Runnable? = null
    
    // Track user car target position (where we want to go)
    private var userCarLat: Double = 0.0
    private var userCarLon: Double = 0.0
    private var userCarBearing: Float = 0.0f
    
    // Track user car current visual position (where arrow currently is displayed)
    private var userCarVisualLat: Double = 0.0
    private var userCarVisualLon: Double = 0.0
    private var userCarVisualBearing: Float = 0.0f
    
    // Track other car position for smooth animation (legacy single car)
    private var otherCarLat: Double = 0.0
    private var otherCarLon: Double = 0.0
    private var otherCarBearing: Float = 0.0f
    private var otherCarAnimationRunnable: Runnable? = null
    
    // Track multiple other cars for smooth animation
    data class OtherCarVisualState(var lat: Double, var lon: Double, var heading: Float)
    private val otherCarsVisualState = mutableMapOf<String, OtherCarVisualState>()
    private var otherCarsAnimationRunnable: Runnable? = null
    
    // Navigation route tracking for traveled/remaining display
    private var fullRoutePoints: List<NavLatLng> = emptyList()
    private var traveledPath: MutableList<NavLatLng> = mutableListOf()
    private var lastRouteUpdateTime: Long = 0L
    
    // Camera update throttling for performance
    private var lastCameraUpdateTime: Long = 0L
    
    // Accident markers tracking
    data class AccidentMarker(
        val eventId: String, 
        val latitude: Double, 
        val longitude: Double,
        val timestamp: Long = System.currentTimeMillis()
    )
    private val activeAccidents = mutableMapOf<String, AccidentMarker>()
    private var accidentCleanupRunnable: Runnable? = null

    fun init(onReady: () -> Unit) {
        this.styleLoadedCallback = onReady
        mapView.getMapAsync(this)
    }

    // Map lifecycle passthroughs
    fun onStart() { mapView.onStart() }
    fun onResume() { mapView.onResume() }
    fun onPause() { mapView.onPause() }
    fun onStop() { mapView.onStop() }
    fun onDestroy() { 
        stopRouteSimulation()
        stopAccidentCleanupTimer()
        // Cancel other car animations
        otherCarAnimationRunnable?.let { mainHandler.removeCallbacks(it) }
        otherCarsAnimationRunnable?.let { mainHandler.removeCallbacks(it) }
        mapView.onDestroy() 
    }

    override fun onMapReady(map: MapLibreMap) {
        this.mapLibreMap = map
        
        // Enable and configure compass - positioned on top right in map area
        map.uiSettings.isCompassEnabled = true
        // Position: from right edge of screen minus right panel width (approximately 350px from right for visible map area)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val rightPanelWidth = if (context.resources.configuration.smallestScreenWidthDp >= 600) {
            (screenWidth * 0.25).toInt()  // 25% for tablet
        } else {
            (130 * context.resources.displayMetrics.density).toInt()  // 130dp for phone
        }
        val compassRightMargin = rightPanelWidth + 10  // More to the left
        map.uiSettings.setCompassMargins(0, 30, compassRightMargin, 0)  // Higher up
        map.uiSettings.setCompassGravity(android.view.Gravity.TOP or android.view.Gravity.END)
        map.uiSettings.setAllGesturesEnabled(true)
        
        map.setStyle(Style.Builder().fromUri(STYLE_URL_DARK)) { style ->
            // Performance optimization: Remove unnecessary labels and POIs
            optimizeMapLayers(style)
            
            // Add right padding to center navigation arrow on visible map area (excluding right panel)
            // Right panel is 25% of screen width on tablet (~200dp), or 130dp on phone
            val rightPaddingDp = if (context.resources.configuration.smallestScreenWidthDp >= 600) 200 else 65
            setMapCameraPaddingDp(0, 0, rightPaddingDp, 0)
            
            // Add route line layers (below arrow)
            addRouteSourceAndLayers(style)
            
            // Add destination marker layer
            addDestinationSourceAndLayer(style)

            // 2) add arrow image, source, and symbol layer for user car
            addArrowImageToStyle(style)
            addArrowSourceAndLayer(style)
            
            // 2b) add other car image, source, and symbol layer
            addOtherCarImageToStyle(style)
            addOtherCarSourceAndLayer(style)
            
            // Add accident marker layer (above all other markers for visibility)
            addAccidentSourceAndLayer(style)

            // 3) try to brighten common road layers (best-effort)
            brightenRoads(style)

            // notify caller
            styleLoadedCallback?.invoke()
        }
    }

    // --- camera padding helper (dp -> px) ---
    private fun setMapCameraPaddingDp(leftDp: Int, topDp: Int, rightDp: Int, bottomDp: Int) {
        val density = context.resources.displayMetrics.density
        mapLibreMap?.setPadding(
            (leftDp * density).toInt(),
            (topDp * density).toInt(),
            (rightDp * density).toInt(),
            (bottomDp * density).toInt()
        )
    }

    // --- arrow symbol setup ---
    private fun addArrowImageToStyle(style: Style) {
        // load drawable (navigation_arrow.png in res/drawable)
        try {
            val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.navigation_arrow)
            if (bmp != null) {
                style.addImage(ARROW_IMAGE_ID, bmp)
                android.util.Log.d("MapController", "User car arrow image loaded successfully")
            } else {
                android.util.Log.e("MapController", "Failed to load user car arrow image - bitmap is null")
            }
        } catch (t: Throwable) {
            android.util.Log.e("MapController", "Error loading user car arrow image: ${t.message}")
        }
    }

    private fun addArrowSourceAndLayer(style: Style) {

        val initialFeature = Feature.fromGeometry(Point.fromLngLat(0.0, 0.0))
        val src = GeoJsonSource(
            ARROW_SOURCE_ID,
            FeatureCollection.fromFeatures(arrayOf(initialFeature))
        )
        style.addSource(src)

        val symbolLayer = SymbolLayer(ARROW_LAYER_ID, ARROW_SOURCE_ID).apply {
            setProperties(
                iconImage(ARROW_IMAGE_ID),
                iconSize(0.07f),  // Smaller arrow for better map visibility
                iconAllowOverlap(true),
                iconIgnorePlacement(true),

                // Correct MapLibre v11 constants:
                iconAnchor(Property.ICON_ANCHOR_CENTER),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),

                // rotation will be updated in updateArrowPosition()
                iconRotate(0.0f)
            )
        }

        style.addLayer(symbolLayer)
    }
    
    // --- other car symbol setup ---
    private fun addOtherCarImageToStyle(style: Style) {
        // load drawable for the other car (other_navigation_arrow.png)
        try {
            val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.other_navigation_arrow)
            if (bmp != null) {
                style.addImage(OTHER_CAR_IMAGE_ID, bmp)
                android.util.Log.d("MapController", "Other car arrow image loaded successfully")
            } else {
                android.util.Log.e("MapController", "Failed to load other car arrow image - bitmap is null")
            }
        } catch (t: Throwable) {
            android.util.Log.e("MapController", "Error loading other car arrow image: ${t.message}")
        }
    }

    private fun addOtherCarSourceAndLayer(style: Style) {
        val initialFeature = Feature.fromGeometry(Point.fromLngLat(0.0, 0.0))
        val src = GeoJsonSource(
            OTHER_CAR_SOURCE_ID,
            FeatureCollection.fromFeatures(arrayOf(initialFeature))
        )
        style.addSource(src)

        val symbolLayer = SymbolLayer(OTHER_CAR_LAYER_ID, OTHER_CAR_SOURCE_ID).apply {
            setProperties(
                iconImage(OTHER_CAR_IMAGE_ID),
                iconSize(0.05f),  // Smaller to distinguish from user car
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconAnchor(Property.ICON_ANCHOR_CENTER),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconRotate(0.0f),
                iconOpacity(0.85f)  // Slightly transparent to distinguish
            )
        }

        style.addLayer(symbolLayer)
    }


    // Track animation state
    private var currentAnimationRunnable: Runnable? = null
    private var lastUpdateTime: Long = 0
    
    // update arrow feature & rotation with smooth animation, move camera to it
    fun updateArrowPosition(lat: Double, lon: Double, bearing: Float, animateMs: Long = 800) {
        val map = mapLibreMap ?: return
        val style = map.style ?: return
        
        // Cancel previous animation if still running
        currentAnimationRunnable?.let { mainHandler.removeCallbacks(it) }
        
        // Calculate adaptive animation duration based on update frequency
        val currentTime = System.currentTimeMillis()
        val timeSinceLastUpdate = currentTime - lastUpdateTime
        lastUpdateTime = currentTime
        
        // Use shorter animation for frequent updates (smoother), longer for infrequent ones
        val adaptiveAnimateMs = if (timeSinceLastUpdate > 0 && timeSinceLastUpdate < 2000) {
            // Frequent updates: use 70% of time since last update, min 400ms, max 800ms
            (timeSinceLastUpdate * 0.7f).toLong().coerceIn(400, 800)
        } else {
            animateMs // Use default for first update or very infrequent updates
        }
        
        // Use current VISUAL position as start (where arrow currently is), not target position
        val startLat = if (userCarVisualLat == 0.0) lat else userCarVisualLat
        val startLon = if (userCarVisualLon == 0.0) lon else userCarVisualLon
        val startBearing = if (userCarVisualBearing == 0.0f) bearing else userCarVisualBearing
        
        // Animate both camera and icon smoothly
        val startTime = System.currentTimeMillis()
        val frameRate = 16L // ~60fps
        
        val animationRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / adaptiveAnimateMs).coerceIn(0f, 1f)
                
                // Use linear interpolation for smoother, more predictable movement
                val easedProgress = progress
                
                // Interpolate position
                val currentLat = startLat + (lat - startLat) * easedProgress
                val currentLon = startLon + (lon - startLon) * easedProgress
                
                // Smooth bearing interpolation (handle 360° wraparound)
                var bearingDiff = bearing - startBearing
                if (bearingDiff > 180f) bearingDiff -= 360f
                if (bearingDiff < -180f) bearingDiff += 360f
                val currentBearing = startBearing + bearingDiff * easedProgress
                
                // Update visual position tracking
                userCarVisualLat = currentLat
                userCarVisualLon = currentLon
                userCarVisualBearing = currentBearing
                
                // Update icon position
                val pt = Point.fromLngLat(currentLon, currentLat)
                val feature = Feature.fromGeometry(pt)
                (style.getSourceAs(ARROW_SOURCE_ID) as? GeoJsonSource)
                    ?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(feature)))
                
                val layer = style.getLayerAs<SymbolLayer>(ARROW_LAYER_ID)
                layer?.setProperties(iconRotate(currentBearing))
                
                // Continue animation
                if (progress < 1.0f) {
                    mainHandler.postDelayed(this, frameRate)
                } else {
                    // Ensure final position is exact
                    userCarVisualLat = lat
                    userCarVisualLon = lon
                    userCarVisualBearing = bearing
                    currentAnimationRunnable = null
                }
            }
        }
        
        currentAnimationRunnable = animationRunnable
        
        // Start icon animation
        animationRunnable.run()
        
        // Animate camera separately (smoother, slightly longer duration)
        val camera = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(19.0)
            .tilt(60.0)
            .bearing(bearing.toDouble())
            .build()

        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(camera),
            adaptiveAnimateMs.toInt()
        )
    }
    
    // Update the user's car position (main car that camera follows)
    fun updateUserCar(lat: Double, lon: Double, bearing: Float, animateMs: Long = 800) {
        android.util.Log.d("MapController", "updateUserCar: lat=$lat, lon=$lon, bearing=$bearing")
        userCarLat = lat
        userCarLon = lon
        userCarBearing = bearing
        updateArrowPosition(lat, lon, bearing, animateMs)
    }
    
    // Update the other car position with smooth animation (doesn't move camera)
    fun updateOtherCar(lat: Double, lon: Double, bearing: Float) {
        android.util.Log.d("MapController", "updateOtherCar: lat=$lat, lon=$lon, bearing=$bearing")
        val map = mapLibreMap ?: return
        val style = map.style ?: return
        
        // Store the previous position
        val startLat = if (otherCarLat == 0.0) lat else otherCarLat
        val startLon = if (otherCarLon == 0.0) lon else otherCarLon
        val startBearing = if (otherCarBearing == 0.0f) bearing else otherCarBearing
        
        // Cancel any existing animation
        otherCarAnimationRunnable?.let { mainHandler.removeCallbacks(it) }
        
        // Animate over 800ms with 60fps
        val animationDuration = 800L
        val frameRate = 16L // ~60fps
        val totalFrames = (animationDuration / frameRate).toInt()
        var currentFrame = 0
        val startTime = System.currentTimeMillis()
        
        otherCarAnimationRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / animationDuration).coerceIn(0f, 1f)
                
                // Ease-in-out interpolation for smooth movement
                val easedProgress = if (progress < 0.5f) {
                    2 * progress * progress
                } else {
                    1 - Math.pow((-2.0 * progress + 2.0), 2.0).toFloat() / 2
                }
                
                // Interpolate position
                val currentLat = startLat + (lat - startLat) * easedProgress
                val currentLon = startLon + (lon - startLon) * easedProgress
                val currentBearing = startBearing + (bearing - startBearing) * easedProgress
                
                // Update the map
                val pt = Point.fromLngLat(currentLon, currentLat)
                val feature = Feature.fromGeometry(pt)
                
                (style.getSourceAs(OTHER_CAR_SOURCE_ID) as? GeoJsonSource)
                    ?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(feature)))
                
                val layer = style.getLayerAs<SymbolLayer>(OTHER_CAR_LAYER_ID)
                layer?.setProperties(iconRotate(currentBearing))
                
                // Continue animation or finish
                if (progress < 1.0f) {
                    mainHandler.postDelayed(this, frameRate)
                } else {
                    // Store final position
                    otherCarLat = lat
                    otherCarLon = lon
                    otherCarBearing = bearing
                    otherCarAnimationRunnable = null
                    android.util.Log.d("MapController", "Other car animation completed")
                }
            }
        }
        
        otherCarAnimationRunnable?.run()
    }
    
    // Data class for other car positions
    data class OtherCarData(val carId: String, val lat: Double, val lon: Double, val heading: Float)
    
    /**
     * Update multiple other cars at once with smooth animation.
     * Animates from current visual positions to new target positions.
     */
    fun updateOtherCars(cars: List<OtherCarData>) {
        android.util.Log.d("MapController", "updateOtherCars: ${cars.size} cars (with animation)")
        val map = mapLibreMap ?: return
        val style = map.style ?: return
        
        if (cars.isEmpty()) {
            // Clear all cars
            (style.getSourceAs(OTHER_CAR_SOURCE_ID) as? GeoJsonSource)
                ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            otherCarsVisualState.clear()
            return
        }
        
        // Cancel any existing animation
        otherCarsAnimationRunnable?.let { mainHandler.removeCallbacks(it) }
        
        // Initialize visual state for new cars (set to target position, no animation on first appearance)
        cars.forEach { car ->
            if (!otherCarsVisualState.containsKey(car.carId)) {
                otherCarsVisualState[car.carId] = OtherCarVisualState(car.lat, car.lon, car.heading)
            }
        }
        
        // Store start positions and target positions
        val startStates = cars.associate { car ->
            val current = otherCarsVisualState[car.carId] ?: OtherCarVisualState(car.lat, car.lon, car.heading)
            car.carId to Triple(current.lat, current.lon, current.heading)
        }
        
        // Animation parameters
        val animationDuration = 400L // Shorter for multiple cars (smoother)
        val frameRate = 16L // ~60fps
        val startTime = System.currentTimeMillis()
        
        otherCarsAnimationRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / animationDuration).coerceIn(0f, 1f)
                
                // Ease-out interpolation for smooth deceleration
                val easedProgress = 1 - (1 - progress) * (1 - progress)
                
                // Interpolate all cars
                val features = cars.mapNotNull { car ->
                    val (startLat, startLon, startHeading) = startStates[car.carId] ?: return@mapNotNull null
                    
                    // Interpolate position
                    val currentLat = startLat + (car.lat - startLat) * easedProgress
                    val currentLon = startLon + (car.lon - startLon) * easedProgress
                    
                    // Smooth heading interpolation (handle 360° wraparound)
                    var headingDiff = car.heading - startHeading
                    if (headingDiff > 180f) headingDiff -= 360f
                    if (headingDiff < -180f) headingDiff += 360f
                    val currentHeading = startHeading + headingDiff * easedProgress
                    
                    // Update visual state
                    otherCarsVisualState[car.carId] = OtherCarVisualState(currentLat, currentLon, currentHeading)
                    
                    // Create feature
                    val pt = Point.fromLngLat(currentLon, currentLat)
                    Feature.fromGeometry(pt).apply {
                        addStringProperty("carId", car.carId)
                        addNumberProperty("bearing", currentHeading.toDouble())
                    }
                }
                
                // Update the map source
                (style.getSourceAs(OTHER_CAR_SOURCE_ID) as? GeoJsonSource)
                    ?.setGeoJson(FeatureCollection.fromFeatures(features.toTypedArray()))
                
                // Update rotation (use average heading for now since single layer)
                if (features.isNotEmpty()) {
                    val avgHeading = otherCarsVisualState.values
                        .map { it.heading.toDouble() }
                        .average()
                        .toFloat()
                    val layer = style.getLayerAs<SymbolLayer>(OTHER_CAR_LAYER_ID)
                    layer?.setProperties(iconRotate(avgHeading))
                }
                
                // Continue animation or finish
                if (progress < 1.0f) {
                    mainHandler.postDelayed(this, frameRate)
                } else {
                    // Ensure final positions are exact
                    cars.forEach { car ->
                        otherCarsVisualState[car.carId] = OtherCarVisualState(car.lat, car.lon, car.heading)
                    }
                    otherCarsAnimationRunnable = null
                    android.util.Log.d("MapController", "Other cars animation completed")
                }
            }
        }
        
        otherCarsAnimationRunnable?.run()
    }
    

    // --- single location helper ---
    fun setSingleLocation(lat: Double, lon: Double, bearing: Float) {
        stopRouteSimulation()
        updateArrowPosition(lat, lon, bearing)
    }

    // --- route simulation: pass list of Pair(lat,lon) or Points ---
    fun simulateRoute(points: List<Pair<Double, Double>>, stepMs: Long = 900L) {
        if (points.isEmpty()) return
        stopRouteSimulation()

        routePoints = points.map { (lat, lon) -> Point.fromLngLat(lon, lat) }
        routeIndex = 0

        routeRunnable = object : Runnable {
            override fun run() {
                if (routeIndex == 0) {
                    mainHandler.postDelayed(this, 7000L)
                }

                if (routeIndex >= routePoints.size) {
                    routeRunnable = null
                    return
                }
                val p = routePoints[routeIndex]
                val bearing = if (routeIndex < routePoints.size - 1)
                    computeBearing(routePoints[routeIndex], routePoints[routeIndex + 1])
                else 0f

                updateArrowPosition(p.latitude(), p.longitude(), bearing)
                routeIndex++
                mainHandler.postDelayed(this, stepMs)
            }
        }
        routeRunnable?.run()
    }

    private fun stopRouteSimulation() {
        routeRunnable?.let { mainHandler.removeCallbacks(it) }
        routeRunnable = null
    }
    
    /**
     * Clear/hide the other car marker (used when switching to speed test)
     */
    fun clearOtherCar() {
        val map = mapLibreMap ?: return
        val style = map.style ?: return
        
        // Cancel any animations
        otherCarAnimationRunnable?.let { mainHandler.removeCallbacks(it) }
        otherCarAnimationRunnable = null
        otherCarsAnimationRunnable?.let { mainHandler.removeCallbacks(it) }
        otherCarsAnimationRunnable = null
        
        // Clear the source (empty feature collection)
        (style.getSourceAs(OTHER_CAR_SOURCE_ID) as? GeoJsonSource)
            ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        
        // Reset tracking variables (legacy single car)
        otherCarLat = 0.0
        otherCarLon = 0.0
        otherCarBearing = 0.0f
        
        // Clear multiple cars visual state
        otherCarsVisualState.clear()
        
        android.util.Log.d("MapController", "Other cars cleared")
    }

    // compute bearing from point a to b (bearing in degrees)
    private fun computeBearing(a: Point, b: Point): Float {
        val lat1 = Math.toRadians(a.latitude())
        val lon1 = Math.toRadians(a.longitude())
        val lat2 = Math.toRadians(b.latitude())
        val lon2 = Math.toRadians(b.longitude())
        val y = Math.sin(lon2 - lon1) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(lon2 - lon1)
        val brng = Math.toDegrees(Math.atan2(y, x))
        return ((brng + 360.0) % 360.0).toFloat()
    }

    // attempt to brighten common road layers (names vary by style)
    private fun brightenRoads(style: Style) {
        val candidates = listOf("road", "road-primary", "road_major", "highway-primary", "trunk")
        for (id in candidates) {
            try {
                val layer = style.getLayer(id)
                if (layer is LineLayer) {
                    layer.setProperties(
                        lineColor("#FFD27A"),
                        lineWidth(2.5f)
                    )
                }
            } catch (_: Exception) {
                // ignore missing layer
            }
        }
    }
    
    /**
     * Optimize map performance by removing unnecessary labels and POIs.
     * Only keep essential road labels for navigation.
     * MapTiler Streets-v2-dark style layer IDs.
     * 
     * PERFORMANCE CRITICAL: Removes ALL non-essential visual elements
     * for maximum rendering performance during navigation simulation.
     */
    private fun optimizeMapLayers(style: Style) {
        Log.d(TAG, "Optimizing map layers - aggressively hiding POIs and non-essential elements")
        
        // AGGRESSIVE OPTIMIZATION: Remove ALL these layer patterns
        val patternsToHide = listOf(
            // POI labels - all variations
            "poi", "place", "label", "symbol", "text", "icon",
            // Buildings and structures
            "building", "housenumber", "house", "housenum",
            // Natural features
            "water", "waterway", "park", "landuse", "landcover", "mountain", "peak",
            // Transit and infrastructure
            "transit", "airport", "aeroway", "railway",
            // Road labels (keep roads visible but hide labels for performance)
            "road_label", "road-label", "road_shield", "road-shield", 
            "road-number", "highway-shield", "road-oneway",
            // 3D and extrusions (heavy on GPU)
            "3d", "extrusion",
            // Boundaries and administrative
            "boundary", "admin", "state", "country",
            // Miscellaneous
            "barrier", "bridge", "tunnel"
        )
        
        // Method 1: Hide by known layer IDs (fast)
        val explicitLayersToHide = listOf(
            // POI comprehensive list
            "poi", "poi-level-1", "poi-level-2", "poi-level-3", "poi_label", "poi-label",
            "poi_z14", "poi_z15", "poi_z16", "poi-railway", "poi-bus", "poi-park", 
            "poi-hospital", "poi-school", "poi-restaurant", "poi-cafe", "poi-shop",
            
            // Place labels
            "place-other", "place_other", "place-city", "place_city", "place-village", 
            "place_village", "place-town", "place_town", "place-neighbourhood", 
            "place_neighbourhood", "place-suburb", "place_suburb", "place-hamlet", 
            "place_hamlet", "place-island", "place_island", "place-residential", 
            "place-quarter", "place_label",
            
            // House and building labels
            "housenumber", "house-number", "housenum-label", "housenum", 
            "building", "building-top", "building-number", "building-3d", 
            "building_3d", "building-extrusion",
            
            // Water labels
            "water-name", "water_name", "water-name-lakeline", "water-name-ocean", 
            "water-name-other", "waterway-name", "waterway_label", "waterway-label",
            
            // Park and landuse
            "park", "park-label", "park_label", "landcover", "landcover-grass", 
            "landcover-label", "landuse", "landuse-label", "landuse-park",
            
            // Transit
            "transit", "transit-label", "transit_label", "airport", "airport-label", 
            "airport_label", "aeroway", "aeroway-label", "railway-label",
            
            // Road labels and shields (hide for performance, keep road lines)
            "road_label", "road-label", "road_label_primary", "road_label_secondary",
            "road-label-small", "road_label_small", "road-label-minor",
            "road_shield", "road-shield", "highway-shield", "road-number-shield",
            "road-oneway-arrows", "road-oneway-arrow",
            
            // Mountains and natural
            "mountain_peak", "mountain-peak-label", "peak", "hill-shade",
            
            // Generic labels
            "label", "symbol", "text"
        )
        
        var hiddenCount = 0
        explicitLayersToHide.forEach { layerId ->
            try {
                val layer = style.getLayer(layerId)
                if (layer != null) {
                    layer.setProperties(visibility(Property.NONE))
                    hiddenCount++
                }
            } catch (_: Exception) { }
        }
        
        // Method 2: Pattern-based hiding (comprehensive)
        // Iterate ALL layers and hide based on name patterns
        style.layers.forEach { layer ->
            try {
                val layerName = layer.id.lowercase()
                
                // Check if layer name contains any pattern to hide
                val shouldHide = patternsToHide.any { pattern ->
                    layerName.contains(pattern.lowercase())
                } && !layerName.contains("arrow") && !layerName.contains("route") && 
                  !layerName.contains("destination") && !layerName.contains("car")
                
                if (shouldHide) {
                    layer.setProperties(visibility(Property.NONE))
                    hiddenCount++
                }
            } catch (_: Exception) { }
        }
        
        Log.d(TAG, "Map optimization complete - hidden $hiddenCount layers for maximum performance")
        Log.d(TAG, "Map now shows: roads, navigation markers, and route lines only")
    }
    
    // --- Route line setup ---
    private fun addRouteSourceAndLayers(style: Style) {
        // Add empty route source (remaining route - magenta)
        val routeSource = GeoJsonSource(ROUTE_SOURCE_ID)
        style.addSource(routeSource)
        
        // Add traveled route source (gray)
        val routeTraveledSource = GeoJsonSource(ROUTE_TRAVELED_SOURCE_ID)
        style.addSource(routeTraveledSource)
        
        // Add route casing (outer line for border effect)
        val routeCasingLayer = LineLayer(ROUTE_CASING_LAYER_ID, ROUTE_SOURCE_ID).apply {
            setProperties(
                lineColor("#1565C0"),  // Dark blue border
                lineWidth(12f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)
            )
        }
        style.addLayer(routeCasingLayer)
        
        // Add traveled route layer (gray, below remaining route)
        val routeTraveledLayer = LineLayer(ROUTE_TRAVELED_LAYER_ID, ROUTE_TRAVELED_SOURCE_ID).apply {
            setProperties(
                lineColor("#666666"),  // Gray for traveled portion
                lineWidth(8f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
                lineOpacity(0.6f)
            )
        }
        style.addLayerAbove(routeTraveledLayer, ROUTE_CASING_LAYER_ID)
        
        // Add route line (inner, bright line - remaining route)
        val routeLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).apply {
            setProperties(
                lineColor("#FF4081"),  // Magenta/pink for remaining route
                lineWidth(8f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)
            )
        }
        style.addLayerAbove(routeLayer, ROUTE_TRAVELED_LAYER_ID)
    }
    
    private fun addDestinationSourceAndLayer(style: Style) {
        // Load destination flag image
        try {
            val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.check_flag)
            if (bmp != null) {
                style.addImage(DESTINATION_IMAGE_ID, bmp)
                Log.d(TAG, "Destination flag image loaded successfully")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error loading destination flag image: ${t.message}")
        }
        
        // Add destination source
        val destSource = GeoJsonSource(DESTINATION_SOURCE_ID)
        style.addSource(destSource)
        
        // Add destination marker as a symbol (flag icon)
        val destLayer = SymbolLayer(DESTINATION_LAYER_ID, DESTINATION_SOURCE_ID).apply {
            setProperties(
                iconImage(DESTINATION_IMAGE_ID),
                iconSize(0.06f),  // Adjust size as needed
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconAnchor(Property.ICON_ANCHOR_BOTTOM),  // Anchor at bottom for flag position
                iconOffset(arrayOf(0f, 0f))
            )
        }
        style.addLayerAbove(destLayer, ROUTE_LAYER_ID)
    }
    
    /**
     * Display a navigation route on the map.
     * 
     * @param route The NavigationRoute to display
     * @param fitBounds Whether to zoom to fit the entire route
     */
    fun displayRoute(route: NavigationRoute, fitBounds: Boolean = true) {
        val map = mapLibreMap ?: run {
            Log.e(TAG, "MapLibreMap is null, cannot display route")
            return
        }
        val style = map.style ?: run {
            Log.e(TAG, "Style is null, cannot display route")
            return
        }
        
        Log.d(TAG, "displayRoute called with ${route.routePoints.size} points, fitBounds=$fitBounds")
        
        // Store full route for traveled/remaining tracking
        fullRoutePoints = route.routePoints
        traveledPath.clear()
        lastRouteUpdateTime = 0L  // Reset timer to allow immediate trail updates
        
        Log.d(TAG, "Route stored: ${fullRoutePoints.size} points, traveled path cleared")
        
        // Convert route points to LineString
        val linePoints = route.routePoints.map { 
            Point.fromLngLat(it.longitude, it.latitude) 
        }
        
        if (linePoints.size < 2) {
            Log.e(TAG, "Route has insufficient points (${linePoints.size})")
            return
        }
        
        val lineString = LineString.fromLngLats(linePoints)
        val feature = Feature.fromGeometry(lineString)
        
        // Update route source (remaining route - starts as full route)
        (style.getSourceAs(ROUTE_SOURCE_ID) as? GeoJsonSource)?.let { source ->
            source.setGeoJson(FeatureCollection.fromFeatures(arrayOf(feature)))
            Log.d(TAG, "Updated ROUTE_SOURCE with ${linePoints.size} points")
        } ?: Log.e(TAG, "ROUTE_SOURCE not found!")
        
        // Clear traveled route initially
        (style.getSourceAs(ROUTE_TRAVELED_SOURCE_ID) as? GeoJsonSource)?.let { source ->
            source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            Log.d(TAG, "Cleared ROUTE_TRAVELED_SOURCE")
        } ?: Log.e(TAG, "ROUTE_TRAVELED_SOURCE not found!")
        
        // Update destination marker
        val destPoint = Point.fromLngLat(
            route.destination.longitude, 
            route.destination.latitude
        )
        (style.getSourceAs(DESTINATION_SOURCE_ID) as? GeoJsonSource)?.let { source ->
            source.setGeoJson(FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(destPoint))))
            Log.d(TAG, "Updated destination marker at ${route.destination.latitude}, ${route.destination.longitude}")
        } ?: Log.e(TAG, "DESTINATION_SOURCE not found!")
        
        // Fit camera to show entire route
        if (fitBounds && linePoints.size >= 2) {
            Log.d(TAG, "Fitting camera to route bounds")
            fitCameraToRoute(route)
        }
    }
    
    /**
     * Clear the route from the map.
     */
    fun clearRoute() {
        val style = mapLibreMap?.style ?: return
        
        // Clear full route tracking
        fullRoutePoints = emptyList()
        traveledPath.clear()
        
        // Clear route line (remaining)
        (style.getSourceAs(ROUTE_SOURCE_ID) as? GeoJsonSource)
            ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        
        // Clear traveled route
        (style.getSourceAs(ROUTE_TRAVELED_SOURCE_ID) as? GeoJsonSource)
            ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        
        // Clear destination marker
        (style.getSourceAs(DESTINATION_SOURCE_ID) as? GeoJsonSource)
            ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        
        Log.d(TAG, "Route cleared")
    }
    
    /**
     * Fit the camera to show the entire route.
     */
    fun fitCameraToRoute(route: NavigationRoute, paddingDp: Int = 80) {
        val map = mapLibreMap ?: return
        
        if (route.routePoints.size < 2) return
        
        val boundsBuilder = LatLngBounds.Builder()
        route.routePoints.forEach { point ->
            boundsBuilder.include(LatLng(point.latitude, point.longitude))
        }
        
        val bounds = boundsBuilder.build()
        val density = context.resources.displayMetrics.density
        val padding = (paddingDp * density).toInt()
        
        map.animateCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, padding),
            1500
        )
    }
    
    /**
     * Update vehicle position during navigation.
     * Also updates traveled/remaining route colors dynamically.
     * 
     * PERFORMANCE OPTIMIZED: Real-time updates for maximum responsiveness.
     */
    fun updateVehiclePosition(lat: Double, lon: Double, bearing: Float) {
        Log.d(TAG, "updateVehiclePosition called: lat=$lat, lon=$lon, bearing=$bearing")
        
        updateArrowPosition(lat, lon, bearing)
        
        if (fullRoutePoints.isEmpty()) {
            Log.d(TAG, "No route points to update")
            return
        }
        
        // Update traveled path
        val currentPos = NavLatLng(lat, lon)
        val currentTime = System.currentTimeMillis()
        
        // Always add to traveled path for real-time updates
        traveledPath.add(currentPos)
        lastRouteUpdateTime = currentTime
        
        val style = mapLibreMap?.style ?: run {
            Log.e(TAG, "Style is null, cannot update route visualization")
            return
        }
        
        // Update traveled route (gray) - always update for smooth visualization
        if (traveledPath.size >= 2) {
            val traveledPoints = traveledPath.map { Point.fromLngLat(it.longitude, it.latitude) }
            val traveledLine = LineString.fromLngLats(traveledPoints)
            (style.getSourceAs(ROUTE_TRAVELED_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(
                FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(traveledLine)))
            )
        }
        
        // Find closest point on full route to current position
        var closestIndex = 0
        var minDistance = Double.MAX_VALUE
        fullRoutePoints.forEachIndexed { index, point ->
            val distance = calculateDistance(currentPos, point)
            if (distance < minDistance) {
                minDistance = distance
                closestIndex = index
            }
        }
        
        // Update remaining route (magenta) - from closest point to end
        if (closestIndex < fullRoutePoints.size - 1) {
            val remainingPoints = listOf(currentPos) + fullRoutePoints.subList(closestIndex + 1, fullRoutePoints.size)
            val remainingLinePoints = remainingPoints.map { Point.fromLngLat(it.longitude, it.latitude) }
            if (remainingLinePoints.size >= 2) {
                val remainingLine = LineString.fromLngLats(remainingLinePoints)
                (style.getSourceAs(ROUTE_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(
                    FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(remainingLine)))
                )
            }
            if (traveledPath.size < 2) Log.d(TAG, "Not enough traveled points yet (${traveledPath.size})")
        }
    }
    
    // Helper to calculate distance between two NavLatLng points (meters)
    private fun calculateDistance(from: NavLatLng, to: NavLatLng): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(to.latitude - from.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(from.latitude)) * Math.cos(Math.toRadians(to.latitude)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }
    
    /**
     * Change map style to light or dark mode
     */
    fun setMapStyle(lightMode: Boolean, onStyleLoaded: (() -> Unit)? = null) {
        val styleUrl = if (lightMode) STYLE_URL_LIGHT else STYLE_URL_DARK
        
        mapLibreMap?.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
            // Reapply all configurations
            optimizeMapLayers(style)
            
            val rightPaddingDp = if (context.resources.configuration.smallestScreenWidthDp >= 600) 200 else 65
            setMapCameraPaddingDp(0, 0, rightPaddingDp, 0)
            
            addRouteSourceAndLayers(style)
            addDestinationSourceAndLayer(style)
            addArrowImageToStyle(style)
            addArrowSourceAndLayer(style)
            addOtherCarImageToStyle(style)
            addOtherCarSourceAndLayer(style)
            addAccidentSourceAndLayer(style)
            brightenRoads(style)
            
            // Restore current position if available
            if (userCarVisualLat != 0.0 && userCarVisualLon != 0.0) {
                updateArrowPosition(userCarVisualLat, userCarVisualLon, userCarVisualBearing)
            }
            
            // Restore other car position and accident state
            if (otherCarLat != 0.0 && otherCarLon != 0.0) {
                val pt = Point.fromLngLat(otherCarLon, otherCarLat)
                val feature = Feature.fromGeometry(pt)
                (style.getSourceAs(OTHER_CAR_SOURCE_ID) as? GeoJsonSource)
                    ?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(feature)))
                val layer = style.getLayerAs<SymbolLayer>(OTHER_CAR_LAYER_ID)
                layer?.setProperties(iconRotate(otherCarBearing))
            }
            
            // Restore accident markers if any
            refreshAccidentMarkers()
            
            onStyleLoaded?.invoke()
        }
    }
    
    // ========== Accident Marker Methods ==========
    
    /**
     * Add accident marker source and layer to the map style.
     */
    private fun addAccidentSourceAndLayer(style: Style) {
        // Load accident marker image from VectorDrawable XML
        try {
            val drawable = ContextCompat.getDrawable(context, R.drawable.ic_accident_marker)
            if (drawable != null) {
                val bmp = getBitmapFromDrawable(drawable)
                if (bmp != null) {
                    style.addImage(ACCIDENT_IMAGE_ID, bmp)
                    Log.d(TAG, "Accident marker image loaded successfully (${bmp.width}x${bmp.height})")
                } else {
                    Log.e(TAG, "Failed to convert accident marker drawable to bitmap")
                }
            } else {
                Log.e(TAG, "Accident marker drawable is null")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error loading accident marker image: ${t.message}", t)
        }
        
        // Add accident source
        val accidentSource = GeoJsonSource(ACCIDENT_SOURCE_ID)
        style.addSource(accidentSource)
        
        // Add accident marker layer (above all other markers for visibility)
        val accidentLayer = SymbolLayer(ACCIDENT_LAYER_ID, ACCIDENT_SOURCE_ID).apply {
            setProperties(
                iconImage(ACCIDENT_IMAGE_ID),
                iconSize(0.5f),  // Increased size for better visibility
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconAnchor(Property.ICON_ANCHOR_CENTER)  // Center anchor for circular icon
            )
        }
        style.addLayerAbove(accidentLayer, OTHER_CAR_LAYER_ID)
        Log.d(TAG, "Accident layer added above other car layer")
    }
    
    /**
     * Convert a Drawable (including VectorDrawable) to Bitmap
     */
    private fun getBitmapFromDrawable(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        
        try {
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error converting drawable to bitmap: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Add an accident marker to the map.
     */
    fun addAccidentMarker(eventId: String, latitude: Double, longitude: Double) {
        Log.d(TAG, "Adding accident marker: $eventId at ($latitude, $longitude)")
        
        // Check if this accident already exists
        if (activeAccidents.containsKey(eventId)) {
            Log.d(TAG, "Accident $eventId already exists, updating timestamp")
            activeAccidents[eventId] = AccidentMarker(eventId, latitude, longitude)
        } else {
            // Store the accident with current timestamp
            activeAccidents[eventId] = AccidentMarker(eventId, latitude, longitude)
            Log.d(TAG, "New accident added: $eventId")
        }
        
        // Refresh the map markers
        refreshAccidentMarkers()
        
        // Start cleanup timer if not already running
        startAccidentCleanupTimer()
    }
    
    /**
     * Remove an accident marker from the map.
     */
    fun removeAccidentMarker(eventId: String) {
        Log.d(TAG, "Removing accident marker: $eventId")
        activeAccidents.remove(eventId)
        refreshAccidentMarkers()
    }
    
    /**
     * Clear all accident markers from the map.
     */
    fun clearAllAccidentMarkers() {
        Log.d(TAG, "Clearing all accident markers")
        activeAccidents.clear()
        refreshAccidentMarkers()
    }
    
    /**
     * Refresh all accident markers on the map.
     */
    private fun refreshAccidentMarkers() {
        val style = mapLibreMap?.style ?: return
        
        val features = activeAccidents.values.map { accident ->
            Feature.fromGeometry(
                Point.fromLngLat(accident.longitude, accident.latitude)
            )
        }
        
        (style.getSourceAs(ACCIDENT_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(features)
        )
        
        Log.d(TAG, "Refreshed ${activeAccidents.size} accident markers on map")
    }
    
    /**
     * Check if an accident marker exists for the given event ID.
     */
    fun hasAccidentMarker(eventId: String): Boolean {
        return activeAccidents.containsKey(eventId)
    }
    
    /**
     * Start periodic cleanup timer to remove expired accidents.
     * Checks every 30 seconds for accidents older than ACCIDENT_AUTO_CLEAR_TIMEOUT_MS.
     */
    private fun startAccidentCleanupTimer() {
        // Don't start multiple timers
        if (accidentCleanupRunnable != null) return
        
        val checkIntervalMs = 30000L // Check every 30 seconds
        
        accidentCleanupRunnable = object : Runnable {
            override fun run() {
                cleanupExpiredAccidents()
                
                // Schedule next check if there are still accidents
                if (activeAccidents.isNotEmpty()) {
                    mainHandler.postDelayed(this, checkIntervalMs)
                } else {
                    // No more accidents, stop the timer
                    accidentCleanupRunnable = null
                    Log.d(TAG, "Accident cleanup timer stopped - no active accidents")
                }
            }
        }
        
        accidentCleanupRunnable?.let { mainHandler.postDelayed(it, checkIntervalMs) }
        Log.d(TAG, "Accident cleanup timer started")
    }
    
    /**
     * Stop the accident cleanup timer.
     */
    private fun stopAccidentCleanupTimer() {
        accidentCleanupRunnable?.let { mainHandler.removeCallbacks(it) }
        accidentCleanupRunnable = null
        Log.d(TAG, "Accident cleanup timer stopped")
    }
    
    /**
     * Remove accidents that have exceeded the auto-clear timeout.
     */
    private fun cleanupExpiredAccidents() {
        val currentTime = System.currentTimeMillis()
        val expiredAccidents = activeAccidents.filter { (_, marker) ->
            (currentTime - marker.timestamp) > AppConfig.ACCIDENT_AUTO_CLEAR_TIMEOUT_MS
        }
        
        if (expiredAccidents.isNotEmpty()) {
            Log.d(TAG, "Removing ${expiredAccidents.size} expired accidents")
            expiredAccidents.keys.forEach { eventId ->
                activeAccidents.remove(eventId)
                Log.d(TAG, "Expired accident removed: $eventId")
            }
            refreshAccidentMarkers()
        }
    }
    
    /**
     * Get the current count of active accidents.
     * Used for rate limiting in MainActivity.
     */
    fun getActiveAccidentCount(): Int {
        return activeAccidents.size
    }
}