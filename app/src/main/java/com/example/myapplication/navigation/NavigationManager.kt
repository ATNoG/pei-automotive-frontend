package com.example.myapplication.navigation

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.myapplication.navigation.models.*
import com.example.myapplication.navigation.routing.OsrmApiClient
import kotlinx.coroutines.*

/**
 * NavigationManager - Coordinates all navigation functionality.
 * 
 * OPTIMIZED FOR DIGITAL TWIN SIMULATION:
 * - Receives position updates from MQTT digital twin system
 * - Intelligent API throttling: only calls routing API if 2+ seconds passed AND car moved 20+ meters
 * - Dynamic route recalculation when off-route
 * - Real-time distance and ETA updates
 * - Trail tracking of traveled path
 * - Turn-by-turn instruction management
 * 
 * PERFORMANCE FEATURES:
 * - Configurable throttling prevents excessive API calls
 * - Route caching reduces duplicate calculations
 * - Efficient distance calculations
 * - Optimized for continuous MQTT position streams
 * 
 * SCALABILITY:
 * - All configuration externalized to NavigationConfig
 * - Clean separation of concerns
 * - Easy to extend with new features
 * - Professional architecture ready for production
 * 
 * Usage:
 * ```
 * val navigationManager = NavigationManager()
 * navigationManager.setNavigationListener(listener)
 * navigationManager.calculateAndStartNavigation(origin, destination)
 * // Then call onMqttPositionUpdate() when MQTT position arrives
 * ```
 */
class NavigationManager {
    
    companion object {
        private const val TAG = "NavigationManager"
        
        // Import all configuration from centralized config
        // This makes tuning and maintenance much easier
        // OVERRIDE: Use much smaller threshold to prevent premature step completion
        private const val MANEUVER_ARRIVAL_THRESHOLD = 8.0 // meters - very conservative
        private const val DESTINATION_ARRIVAL_THRESHOLD = NavigationConfig.DESTINATION_ARRIVAL_THRESHOLD_M
        private const val OFF_ROUTE_THRESHOLD = NavigationConfig.OFF_ROUTE_THRESHOLD_M
        private const val RECALCULATION_COOLDOWN_MS = NavigationConfig.RECALCULATION_COOLDOWN_MS
        private const val MIN_TIME_BETWEEN_RECALCULATIONS_MS = NavigationConfig.MIN_TIME_BETWEEN_RECALCULATIONS_MS
        private const val MIN_DISTANCE_FOR_RECALCULATION_M = NavigationConfig.MIN_DISTANCE_FOR_RECALCULATION_M
    }
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Current navigation state
    private var navigationState = NavigationState()
    
    // Listener for navigation events
    private var navigationListener: NavigationListener? = null
    
    // Rerouting control
    private var lastRerouteTime: Long = 0L
    private var isRerouting: Boolean = false
    
    // Performance optimization: Track last position for throttling
    private var lastRecalculationPosition: LatLng? = null
    private var lastRecalculationTime: Long = 0L
    
    // Track last valid distance to prevent showing 0m incorrectly
    private var lastValidDistanceToNextStep: Double = 0.0
    
    /**
     * Set the navigation event listener.
     */
    fun setNavigationListener(listener: NavigationListener) {
        this.navigationListener = listener
    }
    
    /**
     * Get current navigation state.
     */
    fun getCurrentState(): NavigationState = navigationState
    
    /**
     * Check if navigation is currently active.
     */
    fun isNavigating(): Boolean = navigationState.isNavigating
    
    /**
     * Calculate route and start navigation.
     * 
     * @param origin Starting point
     * @param destination End point
     */
    fun calculateAndStartNavigation(origin: LatLng, destination: LatLng) {
        coroutineScope.launch {
            Log.d(TAG, "Calculating route from $origin to $destination")
            navigationListener?.onRouteCalculating()
            
            val route = OsrmApiClient.calculateRoute(origin, destination)
            
            if (route != null) {
                Log.d(TAG, "Route calculated: ${route.routePoints.size} points, " +
                        "${route.steps.size} steps, ${route.totalDistance / 1000} km")
                
                startNavigation(route)
            } else {
                Log.e(TAG, "Failed to calculate route")
                navigationListener?.onNavigationError("Could not calculate route")
            }
        }
    }
    
    /**
     * Start navigation with a pre-calculated route.
     */
    fun startNavigation(route: NavigationRoute) {
        // Minimal logging: avoid verbose per-step/point output to reduce runtime noise
        Log.i(TAG, "Starting navigation - distance=${route.totalDistance}m, steps=${route.steps.size}")
        
        // Initialize navigation state
        navigationState = NavigationState(
            isNavigating = true,
            route = route,
            currentStepIndex = 0,
            remainingDistance = route.totalDistance,
            remainingDuration = route.totalDuration,
            distanceToNextStep = route.steps.firstOrNull()?.distance ?: 0.0
        )
        
        // Notify listener
        navigationListener?.onNavigationStarted(route)
        navigationListener?.onStateUpdated(navigationState)
    }
    
    /**
     * Stop current navigation.
     */
    fun stopNavigation() {
        Log.d(TAG, "Stopping navigation")
        
        // Reset all state
        navigationState = NavigationState(isNavigating = false)
        
        // Clear throttling state
        lastRecalculationPosition = null
        lastRecalculationTime = 0L
        lastRerouteTime = 0L
        isRerouting = false
        
        navigationListener?.onNavigationStopped()
    }
    
    /**
     * Called when MQTT position update is received.
     * This is the main entry point for position updates from the digital twin.
     * 
     * @param latitude Current latitude
     * @param longitude Current longitude
     * @param bearing Current bearing in degrees
     * @param speedKmh Current speed in km/h
     */
    fun onMqttPositionUpdate(
        latitude: Double,
        longitude: Double,
        bearing: Float,
        speedKmh: Double
    ) {
        if (!navigationState.isNavigating) {
            Log.d(TAG, "MQTT position received but not navigating")
            return
        }
        
        Log.d(TAG, "MQTT position update: lat=$latitude, lon=$longitude, bearing=$bearing")
        
        val position = VehiclePosition(
            location = LatLng(latitude, longitude),
            bearing = bearing,
            speedKmh = speedKmh,
            timestamp = System.currentTimeMillis()
        )
        
        onNewPosition(position)
    }
    
    /**
     * Handle new position update.
     * Synchronized to prevent race conditions from concurrent MQTT updates.
     */
    @Synchronized
    private fun onNewPosition(position: VehiclePosition) {
        if (!navigationState.isNavigating) return
        
        val route = navigationState.route ?: return
        
        Log.d(TAG, "Processing position update for navigation")
        
        navigationState = navigationState.copy(currentPosition = position)
        
        val distanceToRoute = calculateDistanceToRoute(position.location, route)
        Log.d(TAG, "Distance to route: ${distanceToRoute}m")
        
        if (distanceToRoute > OFF_ROUTE_THRESHOLD && !isRerouting) {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastRecalc = currentTime - lastRecalculationTime
            
            val shouldRecalculate = if (lastRecalculationPosition != null) {
                val distanceMoved = calculateDistance(lastRecalculationPosition!!, position.location)
                timeSinceLastRecalc >= MIN_TIME_BETWEEN_RECALCULATIONS_MS && 
                distanceMoved >= MIN_DISTANCE_FOR_RECALCULATION_M
            } else {
                timeSinceLastRecalc >= MIN_TIME_BETWEEN_RECALCULATIONS_MS
            }
            
            if (shouldRecalculate) {
                Log.d(TAG, "Off-route detected (${distanceToRoute}m), recalculating... " +
                        "(time: ${timeSinceLastRecalc}ms, " +
                        "moved: ${lastRecalculationPosition?.let { calculateDistance(it, position.location) } ?: 0.0}m)")
                
                lastRecalculationPosition = position.location
                lastRecalculationTime = currentTime
                recalculateRouteAsync()
            } else {
                Log.d(TAG, "Off-route but throttling recalculation " +
                        "(time: ${timeSinceLastRecalc}ms, needs ${MIN_TIME_BETWEEN_RECALCULATIONS_MS}ms)")
            }
        }
        
        val (remainingDistance, remainingDuration) = calculateRemainingDistanceAndTime(
            position.location, 
            route
        )
        
        val newStepIndex = checkStepProgress(position.location, route)
        val currentStepForDistance = route.steps.getOrNull(newStepIndex)
        
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "ACTIVE STEP: Index $newStepIndex")
        if (currentStepForDistance != null) {
            Log.d(TAG, "Instruction: ${currentStepForDistance.instruction}")
            Log.d(TAG, "Type: ${currentStepForDistance.maneuverType}")
            Log.d(TAG, "Road: ${currentStepForDistance.roadName ?: "N/A"}")
            Log.d(TAG, "Step Lat/Lon: ${currentStepForDistance.location.latitude}, ${currentStepForDistance.location.longitude}")
        } else {
            Log.e(TAG, "ERROR: No step found for index $newStepIndex!")
        }
        Log.d(TAG, "═══════════════════════════════════════")
        
        val calculatedDistance = calculateDistanceToNextStep(
            position.location,
            currentStepForDistance
        )
        
        val distanceToNextStep = when {
            calculatedDistance > 5.0 -> {
                lastValidDistanceToNextStep = calculatedDistance
                Log.d(TAG, "Distance to next step: ${calculatedDistance.toInt()}m (step ${newStepIndex}: ${currentStepForDistance?.instruction})")
                calculatedDistance
            }
            calculatedDistance > 0.0 -> {
                Log.d(TAG, "Very close to step: ${calculatedDistance.toInt()}m")
                calculatedDistance
            }
            else -> {
                if (lastValidDistanceToNextStep > 5.0) {
                    Log.d(TAG, "Maintaining previous distance: ${lastValidDistanceToNextStep.toInt()}m")
                    lastValidDistanceToNextStep
                } else {
                    calculatedDistance
                }
            }
        }
        
        // Check arrival
        val distanceToDestination = calculateDistance(position.location, route.destination)
        val isOnLastStep = newStepIndex >= route.steps.size - 1
        
        // Reduced periodic logging to avoid runtime noise
        
        // Check arrival conditions
        if (remainingDistance <= 1.0) {
            Log.d(TAG, "Destination reached by remainingDistance==0")
            onDestinationReached()
            return
        }

        if (isOnLastStep && distanceToDestination < DESTINATION_ARRIVAL_THRESHOLD) {
            Log.d(TAG, "Destination reached! Distance: ${distanceToDestination}m")
            onDestinationReached()
            return
        }
        
        val previousStepIndex = navigationState.currentStepIndex
        navigationState = navigationState.copy(
            remainingDistance = remainingDistance,
            remainingDuration = remainingDuration,
            distanceToNextStep = distanceToNextStep,
            currentStepIndex = newStepIndex
        )
        
        // Notify step change if needed
        if (newStepIndex != previousStepIndex) {
            val newStep = route.steps.getOrNull(newStepIndex)
            if (newStep != null) {
                Log.d(TAG, "Step changed to $newStepIndex: ${newStep.instruction}")
                navigationListener?.onStepChanged(newStep, newStepIndex)
            }
        }
        
        mainHandler.post {
            navigationListener?.onPositionUpdated(position)
            navigationListener?.onStateUpdated(navigationState)
        }
    }
    
    /**
     * Recalculate route from current position (e.g., when off-route).
     */
    private fun recalculateRouteAsync() {
        // Prevent multiple simultaneous rerouting operations
        if (isRerouting) {
            Log.d(TAG, "Rerouting already in progress, skipping")
            return
        }
        
        val currentPosition = navigationState.currentPosition?.location ?: return
        val destination = navigationState.route?.destination ?: return
        
        isRerouting = true
        lastRerouteTime = System.currentTimeMillis()
        
        Log.d(TAG, "Recalculating route from current position")
        
        coroutineScope.launch(Dispatchers.IO) {
            navigationListener?.onRouteCalculating()
            
            val newRoute = OsrmApiClient.recalculateRoute(currentPosition, destination)
            
            isRerouting = false
            
            if (newRoute != null) {
                // Update state synchronously on main thread to prevent race conditions
                withContext(Dispatchers.Main) {
                    synchronized(this@NavigationManager) {
                        // Keep navigation active but update route
                        navigationState = navigationState.copy(
                            route = newRoute,
                            currentStepIndex = 0,
                            remainingDistance = newRoute.totalDistance,
                            remainingDuration = newRoute.totalDuration,
                            distanceToNextStep = newRoute.steps.firstOrNull()?.distance ?: 0.0
                        )
                    }
                    
                    navigationListener?.onRouteRecalculated(newRoute)
                    navigationListener?.onStateUpdated(navigationState)
                }
            } else {
                navigationListener?.onNavigationError("Unable to recalculate route")
            }
        }
    }
    
    /**
     * Called when destination is reached.
     */
    private fun onDestinationReached() {
        Log.d(TAG, "Destination reached!")
        
        navigationState = navigationState.copy(
            isNavigating = false,
            remainingDistance = 0.0,
            remainingDuration = 0.0,
            distanceToNextStep = 0.0
        )
        
        mainHandler.post {
            navigationListener?.onDestinationReached()
            navigationListener?.onStateUpdated(navigationState)
        }
    }
    
    /**
     * Find closest point on route and return its index.
     */
    private fun findClosestPointOnRoute(currentPos: LatLng, route: NavigationRoute): Int {
        var minDistance = Double.MAX_VALUE
        var closestIndex = 0
        
        route.routePoints.forEachIndexed { index, point ->
            val distance = calculateDistance(currentPos, point)
            if (distance < minDistance) {
                minDistance = distance
                closestIndex = index
            }
        }
        
        return closestIndex
    }
    
    /**
     * Calculate distance from current position to the closest point on route.
     */
    private fun calculateDistanceToRoute(currentPos: LatLng, route: NavigationRoute): Double {
        val closestIndex = findClosestPointOnRoute(currentPos, route)
        return calculateDistance(currentPos, route.routePoints[closestIndex])
    }
    
    private fun checkStepProgress(currentPos: LatLng, route: NavigationRoute): Int {
        val steps = route.steps
        var currentIndex = navigationState.currentStepIndex
        
        Log.d(TAG, "== checkStepProgress: currentIdx=$currentIndex/${steps.size}")
        
        while (currentIndex < steps.size - 1) {
            val currentStep = steps[currentIndex]
            val nextStep = steps.getOrNull(currentIndex + 1)
            
            val distanceToStepManeuver = calculateDistanceAlongRoute(currentPos, currentStep.location, route)

            // If step is far behind (car already passed it), auto-advance
            if (distanceToStepManeuver < -30.0) {
                Log.i(TAG, "Auto-advancing: step ${currentIndex} behind by ${-distanceToStepManeuver}m")
                currentIndex++
                lastValidDistanceToNextStep = 0.0
                continue
            }

            // Within arrival threshold - validate maneuver completion
            if (distanceToStepManeuver <= MANEUVER_ARRIVAL_THRESHOLD && distanceToStepManeuver >= -20.0) {
                if (hasUserCompletedManeuver(currentPos, currentStep, nextStep, route)) {
                    Log.i(TAG, "Maneuver confirmed: advancing to step ${currentIndex + 1}")
                    currentIndex++
                    lastValidDistanceToNextStep = 0.0
                } else {
                    // Do not advance; insufficient evidence
                    break
                }
            } else {
                // Still approaching; leave step as-is
                break
            }
        }
        
        Log.d(TAG, "   Final result: step $currentIndex")
        return currentIndex
    }
    
    private fun hasUserCompletedManeuver(
        currentPos: LatLng,
        currentStep: NavigationStep,
        nextStep: NavigationStep?,
        route: NavigationRoute
    ): Boolean {
        if (nextStep == null) return true
        
        val currentRouteIndex = findClosestPointOnRoute(currentPos, route)
        val currentStepIndex = findClosestPointOnRoute(currentStep.location, route)
        val nextStepIndex = findClosestPointOnRoute(nextStep.location, route)
        
        if (currentStepIndex >= nextStepIndex) return true
        
        val progressThroughSegment = (currentRouteIndex - currentStepIndex).toFloat() / 
                                      (nextStepIndex - currentStepIndex).toFloat()
        
        if (progressThroughSegment < 0.1f) {
            return false
        }

        if (progressThroughSegment > 0.3f) {
            return true
        }
        
        val distanceToNextSegmentStart = calculateDistanceAlongRoute(currentPos, nextStep.location, route)
        val totalSegmentLength = calculateDistanceAlongRoute(currentStep.location, nextStep.location, route)
        
            if (totalSegmentLength > 0) {
                val remainingPercentage = distanceToNextSegmentStart / totalSegmentLength
                if (remainingPercentage < 0.7f) {
                    return true
                }
            }

        val routePoints = route.routePoints
        if (currentRouteIndex + 5 < routePoints.size) {
            val lookAheadPoints = routePoints.subList(currentRouteIndex, minOf(currentRouteIndex + 5, routePoints.size))
            val avgDistanceToRoute = lookAheadPoints.map { calculateDistance(currentPos, it) }.average()
            if (avgDistanceToRoute < 15.0) {
                return true
            }
        }
        return false
    }
    
    private fun calculateRemainingDistanceAndTime(
        currentPos: LatLng, 
        route: NavigationRoute
    ): Pair<Double, Double> {
        // Find closest point on route
        var minDistance = Double.MAX_VALUE
        var closestIndex = 0
        
        route.routePoints.forEachIndexed { index, point ->
            val distance = calculateDistance(currentPos, point)
            if (distance < minDistance) {
                minDistance = distance
                closestIndex = index
            }
        }
        
        // Sum distance from closest point to end
        var remainingDistance = 0.0
        for (i in closestIndex until route.routePoints.size - 1) {
            remainingDistance += calculateDistance(
                route.routePoints[i], 
                route.routePoints[i + 1]
            )
        }
        
        // Estimate time based on remaining distance and average speed from route
        val avgSpeed = if (route.totalDuration > 0) {
            route.totalDistance / route.totalDuration  // m/s
        } else {
            16.67  // Default ~60 km/h
        }
        val remainingDuration = remainingDistance / avgSpeed
        
        return Pair(remainingDistance, remainingDuration)
    }
    
    /**
     * Calculate distance to the next maneuver step along the route.
     * Uses route-based distance instead of straight-line to handle off-road positions.
     * Returns distance along the route, or 0 if at/past the step.
     */
    private fun calculateDistanceToNextStep(
        currentPos: LatLng,
        currentStep: NavigationStep?
    ): Double {
        if (currentStep == null) return 0.0
        
        val route = navigationState.route ?: return 0.0
        
        val distance = calculateDistanceAlongRoute(currentPos, currentStep.location, route)
        
        // If we're past the step (negative distance), return 0
        return if (distance < 0) 0.0 else distance
    }
    
    /**
     * Calculate distance along route from current position to target location.
     * Returns positive distance if target is ahead, negative if behind.
     * This is the core distance calculation function used by both step detection and distance display.
     */
    private fun calculateDistanceAlongRoute(
        currentPos: LatLng,
        targetPos: LatLng,
        route: NavigationRoute
    ): Double {
        val routePoints = route.routePoints
        if (routePoints.size < 2) {
            Log.w(TAG, "calculateDistanceAlongRoute: Not enough route points")
            return 0.0
        }
        
        // Find closest point on route for current position
        val currentRouteIndex = findClosestPointOnRoute(currentPos, route)
        
        // Find closest point on route for target location
        val targetRouteIndex = findClosestPointOnRoute(targetPos, route)
        
        // If target is at same point, return 0
        if (targetRouteIndex == currentRouteIndex) return 0.0

        // If target is behind us, return negative distance
        if (targetRouteIndex < currentRouteIndex) {
            var distanceBehind = 0.0
            for (i in targetRouteIndex until currentRouteIndex) {
                if (i + 1 < routePoints.size) {
                    distanceBehind += calculateDistance(routePoints[i], routePoints[i + 1])
                }
            }
            return -distanceBehind
        }

        // Target is ahead - sum distance along route from current to target
        var distanceAhead = 0.0
        for (i in currentRouteIndex until targetRouteIndex) {
            if (i + 1 < routePoints.size) {
                distanceAhead += calculateDistance(routePoints[i], routePoints[i + 1])
            }
        }
        return distanceAhead
    }
    
    /**
     * Calculate distance between two points (Haversine formula).
     */
    private fun calculateDistance(p1: LatLng, p2: LatLng): Double {
        val R = 6371000.0 // Earth radius in meters
        
        val lat1 = Math.toRadians(p1.latitude)
        val lat2 = Math.toRadians(p2.latitude)
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLon = Math.toRadians(p2.longitude - p1.longitude)
        
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        
        return R * c
    }
    
    /**
     * Clean up resources.
     */
    fun destroy() {
        stopNavigation()
        coroutineScope.cancel()
    }
}

/**
 * Listener interface for navigation events.
 * Implement this in your Activity/Fragment to receive navigation updates.
 */
interface NavigationListener {
    /** Called when route calculation starts */
    fun onRouteCalculating()
    
    /** Called when navigation starts with a valid route */
    fun onNavigationStarted(route: NavigationRoute)
    
    /** Called when navigation is stopped */
    fun onNavigationStopped()
    
    /** Called when the vehicle position is updated */
    fun onPositionUpdated(position: VehiclePosition)
    
    /** Called when navigation state changes (distance, time, etc.) */
    fun onStateUpdated(state: NavigationState)
    
    /** Called when the current instruction/step changes */
    fun onStepChanged(step: NavigationStep, stepIndex: Int)
    
    /** Called when destination is reached */
    fun onDestinationReached()
    
    /** Called when route is recalculated (dynamic rerouting) */
    fun onRouteRecalculated(route: NavigationRoute)
    
    /** Called on navigation errors */
    fun onNavigationError(error: String)
}
