package com.example.myapplication

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
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
        
        // Emergency vehicle marker
        private const val EV_SOURCE_ID = "ev-source"
        private const val EV_LAYER_ID = "ev-layer"
        private const val EV_IMAGE_ID = "ev-image"
        
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
    
    // Track other car position for smooth animation
    private var otherCarLat: Double = 0.0
    private var otherCarLon: Double = 0.0
    private var otherCarBearing: Float = 0.0f
    private var otherCarAnimationRunnable: Runnable? = null
    
    // Track emergency vehicle positions for smooth animation
    private val evPositions = mutableMapOf<String, EVMarkerState>()
    
    data class EVMarkerState(
        var lat: Double = 0.0,
        var lon: Double = 0.0,
        var bearing: Float = 0f,
        var animationRunnable: Runnable? = null
    )
    
    // Navigation route tracking for traveled/remaining display
    private var fullRoutePoints: List<NavLatLng> = emptyList()
    private var traveledPath: MutableList<NavLatLng> = mutableListOf()
    private var lastRouteUpdateTime: Long = 0L
    
    // Camera update throttling for performance
    private var lastCameraUpdateTime: Long = 0L

    fun init(onReady: () -> Unit) {
        this.styleLoadedCallback = onReady
        mapView.getMapAsync(this)
    }

    // Map lifecycle passthroughs
    fun onStart() { mapView.onStart() }
    fun onResume() { mapView.onResume() }
    fun onPause() { mapView.onPause() }
    fun onStop() { mapView.onStop() }
    fun onDestroy() { stopRouteSimulation(); mapView.onDestroy() }

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
            
            // 2c) add emergency vehicle image, source, and symbol layer
            addEVImageToStyle(style)
            addEVSourceAndLayer(style)

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


    // --- emergency vehicle symbol setup ---
    private fun addEVImageToStyle(style: Style) {
        try {
            // Try to load the dedicated EV navigation arrow PNG first
            val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.ev_navigation_arrow)
            if (bmp != null) {
                style.addImage(EV_IMAGE_ID, bmp)
                Log.d(TAG, "EV navigation arrow image loaded (PNG)")
            } else {
                // Fallback to vector drawable
                val vectorBmp = BitmapFactory.decodeResource(context.resources, R.drawable.ic_ev_arrow)
                if (vectorBmp != null) {
                    style.addImage(EV_IMAGE_ID, vectorBmp)
                    Log.d(TAG, "EV navigation arrow image loaded (vector fallback)")
                } else {
                    Log.e(TAG, "Failed to load EV arrow image")
                }
            }
        } catch (t: Throwable) {
            // Fallback: use the vector drawable
            try {
                val drawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_ev_arrow)
                if (drawable != null) {
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        drawable.intrinsicWidth.coerceAtLeast(1),
                        drawable.intrinsicHeight.coerceAtLeast(1),
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    style.addImage(EV_IMAGE_ID, bitmap)
                    Log.d(TAG, "EV arrow loaded via drawable conversion")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error loading EV arrow image: ${e.message}")
            }
        }
    }

    private fun addEVSourceAndLayer(style: Style) {
        val src = GeoJsonSource(
            EV_SOURCE_ID,
            FeatureCollection.fromFeatures(emptyArray())
        )
        style.addSource(src)

        val symbolLayer = SymbolLayer(EV_LAYER_ID, EV_SOURCE_ID).apply {
            setProperties(
                iconImage(EV_IMAGE_ID),
                iconSize(0.06f),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconAnchor(Property.ICON_ANCHOR_CENTER),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconRotate(0.0f),
                iconOpacity(0.95f)
            )
        }
        style.addLayer(symbolLayer)
    }

    /**
     * Update an emergency vehicle position on the map with smooth animation.
     * Uses a distinct arrow icon to differentiate from regular vehicles.
     */
    fun updateEmergencyVehicle(evId: String, lat: Double, lon: Double, bearing: Float) {
        Log.d(TAG, "updateEmergencyVehicle: $evId lat=$lat, lon=$lon, bearing=$bearing")
        val map = mapLibreMap ?: return
        val style = map.style ?: return

        val state = evPositions.getOrPut(evId) { EVMarkerState() }

        val startLat = if (state.lat == 0.0) lat else state.lat
        val startLon = if (state.lon == 0.0) lon else state.lon
        val startBearing = if (state.bearing == 0f) bearing else state.bearing

        // Cancel any existing animation
        state.animationRunnable?.let { mainHandler.removeCallbacks(it) }

        val animDuration = 800L
        val frameRate = 16L
        val startTime = System.currentTimeMillis()

        state.animationRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / animDuration).coerceIn(0f, 1f)

                val curLat = startLat + (lat - startLat) * progress
                val curLon = startLon + (lon - startLon) * progress
                var bearingDiff = bearing - startBearing
                if (bearingDiff > 180f) bearingDiff -= 360f
                if (bearingDiff < -180f) bearingDiff += 360f
                val curBearing = startBearing + bearingDiff * progress

                // Rebuild full feature collection with all EV markers
                updateAllEVFeatures(style, evId, curLat, curLon, curBearing)

                if (progress < 1.0f) {
                    mainHandler.postDelayed(this, frameRate)
                } else {
                    state.lat = lat
                    state.lon = lon
                    state.bearing = bearing
                    state.animationRunnable = null
                }
            }
        }
        state.animationRunnable?.run()
    }

    /**
     * Build FeatureCollection from all tracked EV positions and push to the source.
     * During animation of one EV, we use the animated position for that EV and stored positions for others.
     */
    private fun updateAllEVFeatures(
        style: Style,
        animatingId: String,
        animLat: Double,
        animLon: Double,
        animBearing: Float
    ) {
        val features = mutableListOf<Feature>()

        for ((id, state) in evPositions) {
            val lat = if (id == animatingId) animLat else state.lat
            val lon = if (id == animatingId) animLon else state.lon
            val bearing = if (id == animatingId) animBearing else state.bearing

            if (lat == 0.0 && lon == 0.0) continue

            val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))
            feature.addNumberProperty("bearing", bearing.toDouble())
            features.add(feature)
        }

        (style.getSourceAs(EV_SOURCE_ID) as? GeoJsonSource)
            ?.setGeoJson(FeatureCollection.fromFeatures(features))

        // Note: SymbolLayer uses data-driven rotation when we have multiple features;
        // for simplicity with single EV, we just set the layer rotation to the last animated value.
        val layer = style.getLayerAs<SymbolLayer>(EV_LAYER_ID)
        layer?.setProperties(iconRotate(animBearing))
    }

    /**
     * Remove a specific emergency vehicle marker from the map.
     */
    fun clearEmergencyVehicle(evId: String) {
        val state = evPositions.remove(evId) ?: return
        state.animationRunnable?.let { mainHandler.removeCallbacks(it) }

        val style = mapLibreMap?.style ?: return
        // Rebuild features without the removed EV
        val features = evPositions.entries.filter { it.value.lat != 0.0 }.map { (_, s) ->
            Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat))
        }
        (style.getSourceAs(EV_SOURCE_ID) as? GeoJsonSource)
            ?.setGeoJson(FeatureCollection.fromFeatures(features))

        Log.d(TAG, "Emergency vehicle $evId cleared")
    }

    /**
     * Clear all emergency vehicle markers from the map.
     */
    fun clearAllEmergencyVehicles() {
        evPositions.values.forEach { state ->
            state.animationRunnable?.let { mainHandler.removeCallbacks(it) }
        }
        evPositions.clear()

        val style = mapLibreMap?.style ?: return
        (style.getSourceAs(EV_SOURCE_ID) as? GeoJsonSource)
            ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))

        Log.d(TAG, "All emergency vehicles cleared")
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
        
        // Cancel any animation
        otherCarAnimationRunnable?.let { mainHandler.removeCallbacks(it) }
        otherCarAnimationRunnable = null
        
        // Clear the source (empty feature collection)
        (style.getSourceAs(OTHER_CAR_SOURCE_ID) as? GeoJsonSource)
            ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        
        // Reset tracking variables
        otherCarLat = 0.0
        otherCarLon = 0.0
        otherCarBearing = 0.0f
        
        android.util.Log.d("MapController", "Other car cleared")
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
            addEVImageToStyle(style)
            addEVSourceAndLayer(style)
            brightenRoads(style)
            
            // Restore current position if available
            if (userCarVisualLat != 0.0 && userCarVisualLon != 0.0) {
                updateArrowPosition(userCarVisualLat, userCarVisualLon, userCarVisualBearing)
            }
            
            onStyleLoaded?.invoke()
        }
    }
}