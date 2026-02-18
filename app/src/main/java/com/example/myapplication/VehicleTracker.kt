package com.example.myapplication

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import com.example.myapplication.config.AppConfig
import kotlin.math.cos
import kotlin.math.sin

/**
 * VehicleTracker — Manages all vehicle position state and map updates.
 *
 * Responsibilities:
 * - Track positions for user car, other cars, and emergency vehicles
 * - Throttle map updates to prevent overload (batched every 100ms)
 * - Manage top-down view updates
 * - Provide geo-math utilities (haversine distance, bearing, relative position)
 * - Handle overtaking animation state
 *
 * Threading: All public methods should be called from the UI thread.
 */
class VehicleTracker(
    private val mapController: MapController,
    private val topDownCarView: TopDownCarView,
    private val overtakingWarningIcon: ImageView,
    private val evOverlay: EmergencyVehicleOverlay
) {

    companion object {
        private const val TAG = "VehicleTracker"
        private const val THROTTLE_MS = 100L
        private const val OVERTAKING_WARNING_DURATION_MS = 5000L
        private const val EV_CLEANUP_DELAY_MS = 10_000L
        private const val EV_CLEANUP_THRESHOLD_MS = 9_500L
    }

    // ── User Car State ───────────────────────────────────────────────────

    var userLat: Double = 0.0; private set
    var userLon: Double = 0.0; private set
    var userBearing: Float = 0f; private set

    // ── Other Car Positions ──────────────────────────────────────────────

    data class CarPosition(val carId: String, val lat: Double, val lon: Double, val heading: Float)

    private val otherCarPositions = mutableMapOf<String, CarPosition>()

    // ── Emergency Vehicle Positions & Active Tracking ────────────────────

    private val evCarPositions = mutableMapOf<String, CarPosition>()
    private val activeEmergencyVehicles = mutableMapOf<String, Long>()

    // ── Overtaking State ─────────────────────────────────────────────────

    private var hasSeenUserCar = false
    private var hasSeenOtherCar = false
    private var overtakingAnimationStarted = false

    // ── Throttling Handlers ──────────────────────────────────────────────

    private val mainHandler = Handler(Looper.getMainLooper())

    private var otherCarUpdateRunnable: Runnable? = null
    private var evUpdateRunnable: Runnable? = null

    // ====================================================================
    //  User Car
    // ====================================================================

    /**
     * Update the user car's tracked position and bearing.
     * Does NOT update the map or navigation — the caller chooses how to
     * reflect this on screen.
     */
    fun updateUserPosition(lat: Double, lon: Double, bearing: Float) {
        userLat = lat
        userLon = lon
        userBearing = bearing

        if (!hasSeenUserCar) hasSeenUserCar = true
        refreshTopDownView()
    }

    // ====================================================================
    //  Other Cars (e.g. overtaking)
    // ====================================================================

    fun updateOtherCar(carId: String, lat: Double, lon: Double, heading: Float) {
        otherCarPositions[carId] = CarPosition(carId, lat, lon, heading)
        if (!hasSeenOtherCar) hasSeenOtherCar = true

        scheduleThrottledUpdate(
            runnable = { otherCarUpdateRunnable },
            setter = { otherCarUpdateRunnable = it },
            action = {
                val list = otherCarPositions.values.map {
                    MapController.OtherCarData(it.carId, it.lat, it.lon, it.heading)
                }
                mapController.updateOtherCars(list)
            }
        )
        refreshTopDownView()
    }

    // ====================================================================
    //  Overtaking
    // ====================================================================

    fun showOvertakingWarning() {
        Log.d(TAG, "Showing overtaking warning")
        overtakingAnimationStarted = true
        overtakingWarningIcon.visibility = View.VISIBLE

        overtakingWarningIcon.postDelayed({
            hideOvertakingWarning()
        }, OVERTAKING_WARNING_DURATION_MS)
    }

    private fun hideOvertakingWarning() {
        Log.d(TAG, "Hiding overtaking warning")
        overtakingWarningIcon.visibility = View.GONE
        overtakingAnimationStarted = false
    }

    @Suppress("unused")
    fun resetOvertakingState() {
        if (hasSeenUserCar || hasSeenOtherCar || overtakingAnimationStarted) {
            hasSeenUserCar = false
            hasSeenOtherCar = false
            overtakingAnimationStarted = false
            otherCarPositions.clear()
            mapController.clearOtherCar()
            overtakingWarningIcon.visibility = View.GONE
            Log.d(TAG, "Overtaking state reset")
        }
    }

    // ====================================================================
    //  Emergency Vehicles
    // ====================================================================

    /**
     * Track an EV position update that arrives via the car-updates topic.
     */
    fun updateEVCar(carId: String, lat: Double, lon: Double, heading: Float) {
        evCarPositions[carId] = CarPosition(carId, lat, lon, heading)
        scheduleEVMapUpdate()
        refreshTopDownView()

        if (activeEmergencyVehicles.containsKey(carId)) {
            activeEmergencyVehicles[carId] = System.currentTimeMillis()
            val distance = haversineDistanceM(userLat, userLon, lat, lon)
            evOverlay.updateDistance(distance)
        }
    }

    /**
     * Handle an emergency-vehicle proximity alert (parsed by caller).
     * Updates tracking, map marker, top-down view, and the EV overlay.
     */
    fun handleEVProximityAlert(evId: String, evLat: Double, evLon: Double) {
        val liveDistance = haversineDistanceM(userLat, userLon, evLat, evLon)
        Log.d(TAG, "EV $evId is ${liveDistance.toInt()}m away")

        activeEmergencyVehicles[evId] = System.currentTimeMillis()

        if (evLat != 0.0 && evLon != 0.0) {
            val bearing = calculateBearing(evLat, evLon, userLat, userLon)
            evCarPositions[evId] = CarPosition(evId, evLat, evLon, bearing)
            scheduleEVMapUpdate()
            refreshTopDownView()
        }

        evOverlay.showAlert(evId, liveDistance)

        // Schedule cleanup in case no further alerts arrive
        mainHandler.postDelayed({ cleanupEV(evId) }, EV_CLEANUP_DELAY_MS)
    }

    /** Live distance (meters) from the user car to a given coordinate. */
    fun liveDistanceToUser(lat: Double, lon: Double): Double =
        haversineDistanceM(userLat, userLon, lat, lon)

    private fun cleanupEV(evId: String) {
        val lastSeen = activeEmergencyVehicles[evId] ?: return
        if (System.currentTimeMillis() - lastSeen >= EV_CLEANUP_THRESHOLD_MS) {
            Log.d(TAG, "EV $evId out of range, cleaning up")
            activeEmergencyVehicles.remove(evId)
            evCarPositions.remove(evId)
            scheduleEVMapUpdate()
            refreshTopDownView()

            if (activeEmergencyVehicles.isEmpty()) evOverlay.dismiss()
        }
    }

    private fun scheduleEVMapUpdate() {
        scheduleThrottledUpdate(
            runnable = { evUpdateRunnable },
            setter = { evUpdateRunnable = it },
            action = {
                val list = evCarPositions.values.map {
                    MapController.EVCarData(it.carId, it.lat, it.lon, it.heading)
                }
                mapController.updateEmergencyVehicles(list)
            }
        )
    }

    // ====================================================================
    //  Top-Down Radar View
    // ====================================================================

    private fun refreshTopDownView() {
        if (userLat == 0.0 || userLon == 0.0) return

        val otherRelative = otherCarPositions.values.map { car ->
            val (x, y) = relativePosition(car.lat, car.lon)
            TopDownCarView.CarPosition(x, y)
        }
        topDownCarView.updateOtherCars(otherRelative)

        val evRelative = evCarPositions.values.map { ev ->
            val (x, y) = relativePosition(ev.lat, ev.lon)
            TopDownCarView.CarPosition(x, y)
        }
        topDownCarView.updateEVCars(evRelative)
    }

    /**
     * Convert absolute GPS to meters relative to user car orientation.
     * Returns (lateral, longitudinal) in the car's reference frame.
     */
    private fun relativePosition(otherLat: Double, otherLon: Double): Pair<Float, Float> {
        val earthRadius = 6371000.0
        val lat1 = Math.toRadians(userLat)
        val lat2 = Math.toRadians(otherLat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(otherLon) - Math.toRadians(userLon)

        val northM = (dLat * earthRadius).toFloat()
        val eastM = (dLon * earthRadius * cos(lat1)).toFloat()

        val bearingRad = Math.toRadians(userBearing.toDouble())
        val cosB = cos(bearingRad).toFloat()
        val sinB = sin(bearingRad).toFloat()

        val lateral = eastM * cosB - northM * sinB
        val longitudinal = northM * cosB + eastM * sinB
        return lateral to longitudinal
    }

    // ====================================================================
    //  Geo-Math Utilities
    // ====================================================================

    /** Haversine distance in meters between two WGS-84 points. */
    fun haversineDistanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /** Bearing (degrees 0-360) from one point toward another. */
    fun calculateBearing(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double
    ): Float {
        val lat1 = Math.toRadians(fromLat)
        val lon1 = Math.toRadians(fromLon)
        val lat2 = Math.toRadians(toLat)
        val lon2 = Math.toRadians(toLon)
        val y = Math.sin(lon2 - lon1) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) -
                Math.sin(lat1) * Math.cos(lat2) * Math.cos(lon2 - lon1)
        return ((Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    /** Validate WGS-84 coordinates (rejects null island). */
    fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude in -90.0..90.0 &&
                longitude in -180.0..180.0 &&
                !(latitude == 0.0 && longitude == 0.0)

    // ====================================================================
    //  Lifecycle
    // ====================================================================

    fun destroy() {
        otherCarUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        evUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    // ── Private Throttle Helper ──────────────────────────────────────────

    private inline fun scheduleThrottledUpdate(
        runnable: () -> Runnable?,
        setter: (Runnable?) -> Unit,
        crossinline action: () -> Unit
    ) {
        runnable()?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { action() }
        setter(r)
        mainHandler.postDelayed(r, THROTTLE_MS)
    }
}
