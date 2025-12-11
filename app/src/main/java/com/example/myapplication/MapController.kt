package com.example.myapplication

import android.content.Context
import android.graphics.BitmapFactory
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import org.maplibre.android.annotations.Marker // NOTE: not used, safe import removed
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.Property
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

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
        private val STYLE_URL = "https://api.maptiler.com/maps/streets/style.json?key=${BuildConfig.MAPTILER_API_KEY}"
        private const val ARROW_SOURCE_ID = "arrow-source"
        private const val ARROW_LAYER_ID = "arrow-layer"
        private const val ARROW_IMAGE_ID = "arrow-image"
        
        // Second car (other vehicle)
        private const val OTHER_CAR_SOURCE_ID = "other-car-source"
        private const val OTHER_CAR_LAYER_ID = "other-car-layer"
        private const val OTHER_CAR_IMAGE_ID = "other-car-image"
    }

    // simulation state
    private var routePoints: List<Point> = emptyList()
    private var routeIndex = 0
    private var routeRunnable: Runnable? = null
    
    // Track user car position for camera
    private var userCarLat: Double = 0.0
    private var userCarLon: Double = 0.0
    private var userCarBearing: Float = 0.0f
    
    // Track other car position for smooth animation
    private var otherCarLat: Double = 0.0
    private var otherCarLon: Double = 0.0
    private var otherCarBearing: Float = 0.0f
    private var otherCarAnimationRunnable: Runnable? = null

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
        map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
            // 1) pad camera so map visual center is left of UI panel
            setMapCameraPaddingDp(0, 0, 280, 0) // right padding = panel width in dp

            // 2) add arrow image, source, and symbol layer for user car
            addArrowImageToStyle(style)
            addArrowSourceAndLayer(style)
            
            // 2b) add other car image, source, and symbol layer
            addOtherCarImageToStyle(style)
            addOtherCarSourceAndLayer(style)

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
        // load drawable (put ic_map_arrow in res/drawable)
        try {
            val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.ic_map_arrow)
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
                iconSize(0.25f),  // Smaller to fit on the road
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
        // load drawable for the other car
        try {
            // Try to use the same arrow for now (will be colored differently via tint)
            val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.ic_map_arrow)
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
                iconSize(0.18f),  // Much smaller to distinguish from user car
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


    // update arrow feature & rotation with smooth animation, move camera to it
    fun updateArrowPosition(lat: Double, lon: Double, bearing: Float, animateMs: Long = 800) {
        val map = mapLibreMap ?: return
        val style = map.style ?: return
        
        // Store the previous position
        val startLat = if (userCarLat == 0.0) lat else userCarLat
        val startLon = if (userCarLon == 0.0) lon else userCarLon
        val startBearing = if (userCarBearing == 0.0f) bearing else userCarBearing
        
        // Animate both camera and icon smoothly
        val startTime = System.currentTimeMillis()
        val frameRate = 16L // ~60fps
        
        val animationRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / animateMs).coerceIn(0f, 1f)
                
                // Ease-in-out interpolation
                val easedProgress = if (progress < 0.5f) {
                    2 * progress * progress
                } else {
                    1 - Math.pow((-2.0 * progress + 2.0), 2.0).toFloat() / 2
                }
                
                // Interpolate position
                val currentLat = startLat + (lat - startLat) * easedProgress
                val currentLon = startLon + (lon - startLon) * easedProgress
                val currentBearing = startBearing + (bearing - startBearing) * easedProgress
                
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
                }
            }
        }
        
        // Start icon animation
        animationRunnable.run()
        
        // Animate camera separately (smoother)
        val camera = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(19.0)
            .tilt(60.0)
            .bearing(bearing.toDouble())
            .build()

        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(camera),
            animateMs.toInt()
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
}