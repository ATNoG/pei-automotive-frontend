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
        private const val STYLE_URL = "https://api.maptiler.com/maps/streets/style.json?key=VpgyO2ogB4DeaiIKkKXE"
        private const val ARROW_SOURCE_ID = "arrow-source"
        private const val ARROW_LAYER_ID = "arrow-layer"
        private const val ARROW_IMAGE_ID = "arrow-image"
    }

    // simulation state
    private var routePoints: List<Point> = emptyList()
    private var routeIndex = 0
    private var routeRunnable: Runnable? = null

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

            // 2) add arrow image, source, and symbol layer
            addArrowImageToStyle(style)
            addArrowSourceAndLayer(style)

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
            style.addImage(ARROW_IMAGE_ID, bmp)
        } catch (t: Throwable) {
            // silently continue if no drawable
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
                iconSize(0.6f),
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


    // update arrow feature & rotation, move camera to it
    fun updateArrowPosition(lat: Double, lon: Double, bearing: Float, animateMs: Long = 600) {
        val map = mapLibreMap ?: return
        val style = map.style ?: return

        val pt = Point.fromLngLat(lon, lat)
        val feature = Feature.fromGeometry(pt)

        (style.getSourceAs(ARROW_SOURCE_ID) as? GeoJsonSource)
            ?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(feature)))

        val layer = style.getLayerAs<SymbolLayer>(ARROW_LAYER_ID)
        layer?.setProperties(iconRotate(bearing))

        val camera = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(19.0)
            .tilt(60.0)
            .bearing(bearing.toDouble())
            .build()

        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(camera),
            animateMs.toInt()   // ← FIX HERE
        )
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