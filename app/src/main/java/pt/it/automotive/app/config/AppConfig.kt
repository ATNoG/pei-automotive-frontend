package pt.it.automotive.app.config

import pt.it.automotive.app.navigation.models.LatLng

/**
 * AppConfig - Centralized configuration for the automotive navigation app.
 *
 * This object provides a single source of truth for all application-wide
 * configuration parameters, making the system easy to tune and maintain.
 *
 * SCALABILITY FEATURES:
 * - All hardcoded values extracted to configurable constants
 * - Clear documentation of each parameter's purpose
 * - Easy to extend with new configuration options
 * - Future: Can be loaded from external config file or server
 */
object AppConfig {

    /**
    * overtaking-car-front
    * navigation-car
    * main-car
    * speed-car
    * curved-route-car
    * ev-test-regular
    * car-behind
    * entering-car
    * entering-car-2
    * minimal-jam-car-5
    * jam-lead
    * rw-entering-car
    * rw-direct-entering
    * prox-aveiro-slow
    * sumo-0
    * sumo-merging-car
    */

    /**
    * rw-left-car
    * rw-direct-left
    */

    // ========== SUMO Simulation ==========

    /**
     * Prefix shared by all SUMO-generated vehicles. Any car_id that starts
     * with this prefix but is not in the user's car list is shown as an
     * "other car" on the map (no camera follow, no alert routing).
     */
    const val SUMO_CAR_PREFIX = "sumo-"

    /**
     * All known test/simulation car IDs, pre-populated in the settings vehicle section.
     * Users can add/remove cars freely; this list seeds the initial state.
     */
    val DEFAULT_CAR_IDS = listOf(
        "minimal-jam-lead", "minimal-jam-car-1", "minimal-jam-car-2", "minimal-jam-car-3",
        "minimal-jam-car-4", "minimal-jam-car-5", "speed-car", "rw-direct-entering",
        "rw-direct-left", "curved-route-car", "ev-test-regular", "ev-test-emergency",
        "highway-car", "entering-car", "highway-car-2", "entering-car-2",
        "overtaking-car-front", "overtaking-car-behind", "accident-car", "car-behind",
        "car-ahead", "rw-entering-car", "rw-entering-car-2", "rw-left-car",
        "prox-aveiro-slow", "prox-aveiro-fast", "prox-lisbon-fast", "prox-lisbon-slow",
        "jam-lead", "jam-car-1", "jam-car-2", "jam-car-3", "jam-car-4",
        "jam-car-5", "jam-car-6", "jam-car-7", "jam-car-8", "jam-car-9",
        "navigation-car", "main-car"
    )

    /**
     * Default subset of [DEFAULT_CAR_IDS] pre-selected as "my cars" on first launch.
     * Only the primary subject cars from each test scenario — background/other traffic
     * cars are not pre-selected (they show as other cars on the map).
     */
    val DEFAULT_USER_CAR_IDS = setOf(
        "speed-car",
        "curved-route-car",
        "ev-test-regular",
        "rw-direct-entering",
        "overtaking-car-front",
        "entering-car",
        "entering-car-2",
        "accident-car",
        "car-behind",
        "minimal-jam-lead",
        // deprecated test subjects
        "rw-entering-car",
        "jam-lead",
        "prox-aveiro-slow"
    )

    /**
     * Car IDs that represent other vehicles (for overtaking animation).
     * These vehicles are displayed on the map but don't control the camera.
     */
    val OTHER_CAR_IDS = setOf(
        "overtaking-car-behind",
        "accident-car",
        "car-ahead",
        "highway-car",
        "highway-car-2",
        "minimal-jam-car-1",
        "minimal-jam-car-2",
        "minimal-jam-car-3",
        "minimal-jam-car-4",
        "minimal-jam-lead",
        "jam-car-1",
        "jam-car-2",
        "jam-car-3",
        "jam-car-4",
        "jam-car-5",
        "jam-car-6",
        "jam-car-7",
        "jam-car-8",
        "jam-car-9",
        "prox-aveiro-fast",
        "prox-lisbon-slow",
        "prox-lisbon-fast",
    )

    // ========== Default Positions ==========

    /**
     * Default initial position when app starts.
     * This is used before any GPS/MQTT data is received.
     *
     * Location: Aveiro, Portugal
     */
    val DEFAULT_INITIAL_POSITION = LatLng(40.63200640747191, -8.65031482855802)

    /**
     * Predefined destinations for navigation demo.
     * These can be extended for different demo scenarios.
     */
    object Destinations {
        val MERCADO_SANTIAGO = LatLng(40.62682666363219, -8.650806765235274)

        // Add more predefined destinations here as needed
        // val UNIVERSITY = LatLng(40.XXX, -8.XXX)
        // val HOSPITAL = LatLng(40.XXX, -8.XXX)
    }

    // ========== Weather Updates ==========

    /**
     * Interval between weather updates in milliseconds.
     * Default: 10 minutes (600000ms)
     */
    const val WEATHER_UPDATE_INTERVAL_MS = 600000L

    /**
     * Enable/disable weather alert notifications.
     * When true, weather alerts from OpenWeatherMap will be displayed as popups.
     */
    const val WEATHER_ALERTS_ENABLED = true

    /**
     * Auto-dismiss time for weather alert popups in milliseconds.
     * Default: 8 seconds (8000ms)
     */
    const val WEATHER_ALERT_AUTO_DISMISS_MS = 8000L

    /**
     * Delay between showing multiple alerts (staggered display).
     * Default: 500ms
     */
    const val WEATHER_ALERT_STAGGER_MS = 500L

    // ========== Map Settings ==========

    /**
     * Default map zoom level when following the vehicle.
     */
    const val DEFAULT_MAP_ZOOM = 19.0

    /**
     * Default map tilt angle (degrees).
     *   60.0 – original 3D perspective view
     *    0.0 – flat 2D top-down view (better road visibility on low-detail styles)
     */
    const val DEFAULT_MAP_TILT = 0.0

    /**
     * Tilt angle for 3D perspective view (degrees).
     */
    const val MAP_TILT_3D = 60.0

    /**
     * Animation duration for camera movements (milliseconds).
     */
    const val CAMERA_ANIMATION_MS = 800L

    // ========== MQTT Topics ==========

    /**
     * MQTT topic for car position updates.
     */
    const val MQTT_TOPIC_CAR_UPDATES = "cars/updates"

    /**
     * MQTT topic pattern for alerts.
     */
    const val MQTT_TOPIC_ALERTS = "alerts/#"

    /**
     * MQTT topic for speed alerts specifically.
     */
    const val MQTT_TOPIC_SPEED_ALERT = "alerts/speed"

    /**
     * MQTT topic for overtaking alerts.
     */
    const val MQTT_TOPIC_OVERTAKING_ALERT = "alerts/overtaking"

    /**
     * MQTT topic for lane merge alerts.
     */
    const val MQTT_TOPIC_LANE_MERGE_ALERT = "alerts/lane_merge"

    /**
     * MQTT topic for traffic jam alerts.
     */
    const val MQTT_TOPIC_TRAFFIC_JAM_ALERT = "alerts/traffic_jam"
    const val MQTT_TOPIC_TRAFFIC_JAM_PATTERN = "alerts/traffic_jam/#"

    /**
     * MQTT topic for emergency vehicle proximity alerts.
     * Published by the backend emergency_vehicle_detector service.
     */
    const val MQTT_TOPIC_EV_ALERT = "alerts/emergency_vehicle"

    // ========== Emergency Vehicle ==========

    /**
     * Car IDs that represent emergency vehicles.
     * These are displayed with a different navigation arrow on the map.
     */
    val EMERGENCY_VEHICLE_IDS = setOf(
        "ev-test-emergency"
    )

    /**
     * Proximity radius in meters for emergency vehicle alerts.
     * Should match the backend EVDetector.PROXIMITY_M value (500m).
     */
    const val EV_PROXIMITY_RADIUS_M = 500
    /**
     * MQTT topic for accident alerts.
     */
    const val MQTT_TOPIC_ACCIDENT_ALERT = "alerts/accident"

    /**
     * MQTT topic pattern for accident alerts for all cars.
     * Uses wildcard to receive all accident notifications.
     */
    const val MQTT_TOPIC_ACCIDENT_ALERTS_PATTERN = "alerts/accident/#"

    /**
     * MQTT topic for accident cleared/resolved notifications.
     * Published when an accident is no longer blocking the road.
     */
    const val MQTT_TOPIC_ACCIDENT_CLEARED = "alerts/accident/cleared"

    /**
     * MQTT topic base for per-car weather station assignments.
     * Published by the station_assigner backend service as `cars/station/{car_id}`.
     * Payload includes station info and the latest measurement.
     */
    const val MQTT_TOPIC_STATION_ASSIGNMENT_BASE = "cars/station"

    /**
     * MQTT topic for bulk meteo station updates.
     * Published by the meteo_consumer backend service every 5 minutes.
     * Contains all stations with their latest measurements.
     */
    const val MQTT_TOPIC_METEO_UPDATES = "meteo/updates"

    // ========== Accident Management ==========

    /**
     * Auto-remove accident markers after this duration if not explicitly cleared (milliseconds).
     * Default: 15 minutes (900000ms) - safety timeout in case cleared message is missed
     */
    const val ACCIDENT_AUTO_CLEAR_TIMEOUT_MS = 900000L

    // ========== Digital Twin Message Parsing ==========

    /**
     * Supported payload formats for MQTT messages.
     * The app will try to parse messages in this order.
     */
    object PayloadFormat {
        /**
         * Digital Twin Ditto format with nested features.
         * Example: {"gps": {"properties": {"latitude": x, "longitude": y}}}
         */
        const val GPS_FEATURE_KEY = "gps"
        const val FEATURES_KEY = "features"
        const val PROPERTIES_KEY = "properties"
        const val THING_ID_KEY = "thingId"

        /**
         * Flat format keys (legacy/simple format).
         */
        const val CAR_ID_KEY = "car_id"
        const val LATITUDE_KEY = "latitude"
        const val LONGITUDE_KEY = "longitude"
        const val SPEED_KMH_KEY = "speed_kmh"
        const val SPEED_KEY = "speed"
        const val HEADING_DEG_KEY = "heading_deg"
        const val HEADING_KEY = "heading"
        const val SPEED_LIMIT_KMH_KEY = "speed_limit_kmh"
    }

    // ========== Overtaking Animation ==========

    /**
     * Distance range (in meters) for displaying other cars in top-down view.
     */
    const val OVERTAKING_VIEW_RANGE_METERS = 50.0
}
