package com.example.myapplication.navigation.models

import org.maplibre.geojson.Point

/**
 * Represents a geographic coordinate with latitude and longitude.
 */
data class LatLng(
    val latitude: Double,
    val longitude: Double
) {
    fun toPoint(): Point = Point.fromLngLat(longitude, latitude)
    
    companion object {
        fun fromPoint(point: Point): LatLng = LatLng(point.latitude(), point.longitude())
    }
}

/**
 * Represents the current position of the vehicle with bearing.
 */
data class VehiclePosition(
    val location: LatLng,
    val bearing: Float,
    val speedKmh: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Represents a navigation maneuver/step instruction.
 */
data class NavigationStep(
    val instruction: String,          // e.g., "Turn right onto R. de Aveiro"
    val maneuverType: ManeuverType,   // Type of maneuver
    val distance: Double,             // Distance to this step in meters
    val duration: Double,             // Duration to this step in seconds
    val location: LatLng,             // Location where maneuver occurs
    val roadName: String? = null      // Name of the road after maneuver
)

/**
 * Types of navigation maneuvers.
 */
enum class ManeuverType {
    DEPART,
    ARRIVE,
    TURN_LEFT,
    TURN_RIGHT,
    TURN_SLIGHT_LEFT,
    TURN_SLIGHT_RIGHT,
    TURN_SHARP_LEFT,
    TURN_SHARP_RIGHT,
    STRAIGHT,
    ROUNDABOUT,
    ROUNDABOUT_LEFT,
    ROUNDABOUT_RIGHT,
    ROUNDABOUT_STRAIGHT,
    ROUNDABOUT_EXIT,
    ROUNDABOUT_SHARP_LEFT,
    ROUNDABOUT_SHARP_RIGHT,
    ROUNDABOUT_SLIGHT_LEFT,
    ROUNDABOUT_SLIGHT_RIGHT,
    UTURN,
    MERGE,
    MERGE_LEFT,
    MERGE_RIGHT,
    MERGE_SLIGHT_LEFT,
    MERGE_SLIGHT_RIGHT,
    ON_RAMP,
    OFF_RAMP,
    FORK_LEFT,
    FORK_RIGHT,
    FORK_SLIGHT_LEFT,
    FORK_SLIGHT_RIGHT,
    CONTINUE,
    CONTINUE_LEFT,
    CONTINUE_RIGHT,
    CONTINUE_SLIGHT_LEFT,
    CONTINUE_SLIGHT_RIGHT,
    CONTINUE_STRAIGHT,
    CONTINUE_UTURN,
    END_OF_ROAD_LEFT,
    END_OF_ROAD_RIGHT,
    NEW_NAME_LEFT,
    NEW_NAME_RIGHT,
    NEW_NAME_STRAIGHT,
    NOTIFICATION_LEFT,
    NOTIFICATION_RIGHT,
    NOTIFICATION_STRAIGHT,
    UNKNOWN;
    
    companion object {
        fun fromOsrmModifier(type: String?, modifier: String?): ManeuverType {
            return when (type) {
                "depart" -> DEPART
                "arrive" -> ARRIVE
                
                "turn" -> when (modifier) {
                    "left" -> TURN_LEFT
                    "right" -> TURN_RIGHT
                    "slight left" -> TURN_SLIGHT_LEFT
                    "slight right" -> TURN_SLIGHT_RIGHT
                    "sharp left" -> TURN_SHARP_LEFT
                    "sharp right" -> TURN_SHARP_RIGHT
                    "uturn" -> UTURN
                    "straight" -> STRAIGHT
                    else -> TURN_RIGHT
                }
                
                "new name" -> when (modifier) {
                    "left" -> NEW_NAME_LEFT
                    "right" -> NEW_NAME_RIGHT
                    "straight" -> NEW_NAME_STRAIGHT
                    else -> CONTINUE
                }
                
                "continue" -> when (modifier) {
                    "left" -> CONTINUE_LEFT
                    "right" -> CONTINUE_RIGHT
                    "slight left" -> CONTINUE_SLIGHT_LEFT
                    "slight right" -> CONTINUE_SLIGHT_RIGHT
                    "straight" -> CONTINUE_STRAIGHT
                    "uturn" -> CONTINUE_UTURN
                    else -> CONTINUE
                }
                
                "merge" -> when (modifier) {
                    "left" -> MERGE_LEFT
                    "right" -> MERGE_RIGHT
                    "slight left" -> MERGE_SLIGHT_LEFT
                    "slight right" -> MERGE_SLIGHT_RIGHT
                    else -> MERGE
                }
                
                "on ramp" -> ON_RAMP
                "off ramp" -> OFF_RAMP
                
                "fork" -> when (modifier) {
                    "left" -> FORK_LEFT
                    "right" -> FORK_RIGHT
                    "slight left" -> FORK_SLIGHT_LEFT
                    "slight right" -> FORK_SLIGHT_RIGHT
                    else -> FORK_RIGHT
                }
                
                "end of road" -> when (modifier) {
                    "left" -> END_OF_ROAD_LEFT
                    "right" -> END_OF_ROAD_RIGHT
                    else -> TURN_LEFT
                }
                
                "roundabout", "rotary" -> when (modifier) {
                    "left" -> ROUNDABOUT_LEFT
                    "right" -> ROUNDABOUT_RIGHT
                    "straight" -> ROUNDABOUT_STRAIGHT
                    "sharp left" -> ROUNDABOUT_SHARP_LEFT
                    "sharp right" -> ROUNDABOUT_SHARP_RIGHT
                    "slight left" -> ROUNDABOUT_SLIGHT_LEFT
                    "slight right" -> ROUNDABOUT_SLIGHT_RIGHT
                    else -> ROUNDABOUT
                }
                
                "roundabout turn", "exit roundabout", "exit rotary" -> ROUNDABOUT_EXIT
                
                "notification" -> when (modifier) {
                    "left" -> NOTIFICATION_LEFT
                    "right" -> NOTIFICATION_RIGHT
                    "straight" -> NOTIFICATION_STRAIGHT
                    else -> CONTINUE
                }
                
                else -> STRAIGHT
            }
        }
    }
}

/**
 * Represents a complete navigation route.
 */
data class NavigationRoute(
    val origin: LatLng,
    val destination: LatLng,
    val routePoints: List<LatLng>,           // Full route geometry for drawing on map
    val steps: List<NavigationStep>,         // Turn-by-turn instructions
    val totalDistance: Double,               // Total distance in meters
    val totalDuration: Double,               // Total duration in seconds
    val routeName: String? = null            // Optional route name/summary
)

/**
 * Represents the current navigation state.
 */
data class NavigationState(
    val isNavigating: Boolean = false,
    val route: NavigationRoute? = null,
    val currentStepIndex: Int = 0,
    val remainingDistance: Double = 0.0,     // Meters to destination
    val remainingDuration: Double = 0.0,     // Seconds to destination
    val distanceToNextStep: Double = 0.0,    // Meters to next maneuver
    val currentPosition: VehiclePosition? = null
) {
    val currentStep: NavigationStep?
        get() = route?.steps?.getOrNull(currentStepIndex)
    
    val nextStep: NavigationStep?
        get() = route?.steps?.getOrNull(currentStepIndex + 1)
    
    /**
     * Format remaining distance as human-readable string.
     */
    fun formatRemainingDistance(): String {
        return when {
            remainingDistance >= 1000 -> String.format("%.1f Km", remainingDistance / 1000)
            else -> String.format("%.0f m", remainingDistance)
        }
    }
    
    /**
     * Format remaining duration as human-readable string.
     */
    fun formatRemainingDuration(): String {
        val minutes = (remainingDuration / 60).toInt()
        return when {
            minutes >= 60 -> {
                val hours = minutes / 60
                val mins = minutes % 60
                "${hours}h ${mins}min"
            }
            else -> "$minutes minutes"
        }
    }
    
    /**
     * Format distance to next step.
     */
    fun formatDistanceToNextStep(): String {
        return when {
            distanceToNextStep >= 1000 -> String.format("%.1f km", distanceToNextStep / 1000)
            else -> String.format("%.0f m", distanceToNextStep)
        }
    }
}

/**
 * Events that can occur during navigation.
 */
sealed class NavigationEvent {
    data class RouteCalculated(val route: NavigationRoute) : NavigationEvent()
    data class RouteError(val error: String) : NavigationEvent()
    data class StepChanged(val newStep: NavigationStep, val stepIndex: Int) : NavigationEvent()
    data class PositionUpdated(val state: NavigationState) : NavigationEvent()
    object NavigationStarted : NavigationEvent()
    object NavigationStopped : NavigationEvent()
    object DestinationReached : NavigationEvent()
    data class OffRoute(val currentPosition: LatLng) : NavigationEvent()
}
