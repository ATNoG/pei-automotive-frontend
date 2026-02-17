package com.example.myapplication

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.config.AppConfig
import com.example.myapplication.navigation.NavigationListener
import com.example.myapplication.navigation.NavigationManager
import com.example.myapplication.navigation.models.*
import com.example.myapplication.navigation.routing.OsrmApiClient
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity(), NavigationListener {

    companion object {
        private const val TAG = "MainActivity"
        
        // Car IDs configuration - delegate to AppConfig for centralized management
        val USER_CAR_IDS get() = AppConfig.USER_CAR_IDS
        val OTHER_CAR_IDS get() = AppConfig.OTHER_CAR_IDS
        
        // Speed threshold from config
        val SPEED_ALERT_THRESHOLD get() = AppConfig.SPEED_ALERT_THRESHOLD_KMH
    }

    private lateinit var mapController: MapController
    private lateinit var uiController: UiController
    private lateinit var mqttManager: MqttManager
    private lateinit var navigationManager: NavigationManager
    private lateinit var topDownCarView: TopDownCarView
    private lateinit var overtakingWarningIcon: ImageView
    private lateinit var alertNotificationManager: AlertNotificationManager
    private lateinit var evOverlay: EmergencyVehicleOverlay
    
    // Track active emergency vehicles in proximity (evId -> last alert timestamp)
    private val activeEmergencyVehicles = mutableMapOf<String, Long>()
    
    // Initial position from config
    private val initialPosition = AppConfig.DEFAULT_INITIAL_POSITION
    
    // Destination from config
    private val mercadoSantiago = AppConfig.Destinations.MERCADO_SANTIAGO
    
    private var currentLat: Double = AppConfig.DEFAULT_INITIAL_POSITION.latitude
    private var currentLon: Double = AppConfig.DEFAULT_INITIAL_POSITION.longitude
    private var currentSpeed: Double = 0.0
    private var currentSpeedLimit: Int? = null // Default speed limit (null = unknown)
    private var currentBearing: Float = 0f
    
    // Track car positions for top-down view
    private var userCarLat: Double = 0.0
    private var userCarLon: Double = 0.0
    private var userCarBearing: Float = 0f
    
    // Other car positions
    data class OtherCarPosition(val carId: String, val lat: Double, val lon: Double, val heading: Float)
    private val otherCarPositions = mutableMapOf<String, OtherCarPosition>()
    
    // Throttling for other car map updates (prevents overload)
    private val otherCarUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var otherCarUpdateRunnable: Runnable? = null
    private val OTHER_CAR_UPDATE_THROTTLE_MS = 100L // Update map at most every 100ms
    
    // Throttling for emergency vehicle map updates (same efficient pattern)
    private val evUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var evUpdateRunnable: Runnable? = null
    private val EV_UPDATE_THROTTLE_MS = 100L // Update map at most every 100ms
    
    // Security: Rate limiting timestamps (thread-safe)
    private val alertTimestamps = java.util.Collections.synchronizedList(mutableListOf<Long>())
    
    // Track emergency vehicle positions for top-down view and map (evId -> position with heading)
    data class EVCarPosition(val carId: String, val lat: Double, val lon: Double, val heading: Float)
    private val evCarPositions = mutableMapOf<String, EVCarPosition>()

    // Track if we've seen both cars (to detect overtaking start)
    private var hasSeenUserCar = false
    private var hasSeenOtherCar = false
    private var overtakingAnimationStarted = false
    
    // Pending route for navigation dialog
    private var pendingRoute: NavigationRoute? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // initialize MapLibre (only required once, before creating MapView)
        MapLibre.getInstance(this, BuildConfig.MAPTILER_API_KEY, WellKnownTileServer.MapTiler)

        setContentView(R.layout.activity_main)

        // Initialize OsrmApiClient with OpenRouteService API key
        OsrmApiClient.initialize(BuildConfig.OPENROUTESERVICE_API_KEY)

        // create controllers (after setContentView so views exist)
        mapController = MapController(this, findViewById(R.id.mapView))
        uiController = UiController(this)
        topDownCarView = findViewById(R.id.topDownCarView)
        overtakingWarningIcon = findViewById(R.id.overtakingWarningIcon)
        alertNotificationManager = AlertNotificationManager(this)
        alertNotificationManager.requestNotificationPermission()
        evOverlay = findViewById(R.id.evOverlay)
        
        // Setup Navigation Manager
        setupNavigation()

        // Setup settings button click listener
        setupSettingsButton()
        
        // Setup navigation button click listener
        setupNavigationButton()
        
        // Setup weather updates (which includes alerts)
        setupWeatherUpdates()
        
        // Setup weather card click listener
        uiController.setupWeatherCardClick()

        // Setup MQTT (after uiController is initialized)
        setupMqtt()

        // wire map ready callback
        mapController.init {
            // called when style & layers are ready
            // Set initial position on the map
            mapController.setSingleLocation(
                initialPosition.latitude,
                initialPosition.longitude,
                0f
            )
            
            // Apply saved map style preference
            val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
            val isLightMode = prefs.getBoolean("lightMode", false)
            if (isLightMode) {
                mapController.setMapStyle(true)
            }
        }
    }
    
    private fun setupNavigation() {
        navigationManager = NavigationManager()
        navigationManager.setNavigationListener(this)
    }
    
    private fun setupNavigationButton() {
        // Start Route button (top right panel)
        findViewById<TextView>(R.id.btnStartRoute)?.setOnClickListener {
            showNavigationDialog()
        }
        
        // Stop navigation button (below nav panel when active)
        findViewById<ImageView>(R.id.btnStopNavigation)?.setOnClickListener {
            stopNavigation()
        }
    }
    
    @SuppressLint("SetTextI18n", "DefaultLocale")
    private fun showNavigationDialog() {
        // Inflate overlay layout
        val overlayView = layoutInflater.inflate(R.layout.dialog_navigation, null)
        
        // Add overlay to root layout
        val rootView = findViewById<ViewGroup>(android.R.id.content)
        rootView.addView(overlayView)
        
        val routeInfoPreview = overlayView.findViewById<LinearLayout>(R.id.routeInfoPreview)
        val txtRouteInfo = overlayView.findViewById<TextView>(R.id.txtRouteInfo)
        val btnStartNavigation = overlayView.findViewById<Button>(R.id.btnStartNavigation)
        
        // Close button (X)
        overlayView.findViewById<ImageButton>(R.id.btnClose)?.setOnClickListener {
            pendingRoute = null
            rootView.removeView(overlayView)
        }
        
        // Mercado Santiago button - calculate route
        overlayView.findViewById<Button>(R.id.btnDestMercadoSantiago)?.setOnClickListener {
            routeInfoPreview?.visibility = View.VISIBLE
            txtRouteInfo?.text = "Calculating route..."
            btnStartNavigation?.isEnabled = false
            btnStartNavigation?.text = "Calculating..."
            
            // Calculate route to Mercado Santiago
            calculateRouteForDialog(mercadoSantiago) { route ->
                if (route != null) {
                    pendingRoute = route
                    val distKm = String.format("%.1f", route.totalDistance / 1000)
                    val timeMin = (route.totalDuration / 60).toInt()
                    txtRouteInfo?.text = "$distKm km · $timeMin minutes"
                    btnStartNavigation?.isEnabled = true
                    btnStartNavigation?.text = "Start Navigation"
                    
                    // Auto-start navigation after route calculation
                    rootView.postDelayed({
                        if (pendingRoute != null) {
                            rootView.removeView(overlayView)
                            startNavigation(route)
                        }
                    }, 300)
                } else {
                    txtRouteInfo?.text = "Error calculating route"
                    btnStartNavigation?.isEnabled = false
                    btnStartNavigation?.text = "Try Again"
                }
            }
        }
        
        // Start navigation button
        btnStartNavigation?.setOnClickListener {
            pendingRoute?.let { route ->
                rootView.removeView(overlayView)
                startNavigation(route)
            }
        }
        
        // Cancel button
        overlayView.findViewById<Button>(R.id.btnCancel)?.setOnClickListener {
            pendingRoute = null
            rootView.removeView(overlayView)
        }
        
        // Close on background click
        overlayView.setOnClickListener {
            pendingRoute = null
            rootView.removeView(overlayView)
        }
        
        // Prevent clicks on card from closing overlay
        overlayView.findViewById<View>(R.id.dialogCard)?.setOnClickListener {
            // Do nothing - prevent propagation
        }
    }
    
    private fun calculateRouteForDialog(destination: LatLng, callback: (NavigationRoute?) -> Unit) {
        lifecycleScope.launch {
            val origin = LatLng(currentLat, currentLon)
            val route = com.example.myapplication.navigation.routing.OsrmApiClient.calculateRoute(origin, destination)
            runOnUiThread {
                callback(route)
            }
        }
    }
    
    private fun startNavigation(route: NavigationRoute) {
        Log.d("MainActivity", "Starting navigation: ${route.totalDistance / 1000} km")
        navigationManager.startNavigation(route)
    }
    
    private fun stopNavigation() {
        navigationManager.stopNavigation()
    }
    
    /**
     * Stop navigation after arrival popup is closed.
     * Called by UiController when user closes the arrival popup.
     */
    fun stopNavigationAfterArrival() {
        Log.d("MainActivity", "Stopping navigation after arrival")
        uiController.hideNavigationMode()
        mapController.clearRoute()
        navigationManager.stopNavigation()
    }
    
    // ========== NavigationListener Implementation ==========
    
    override fun onRouteCalculating() {
        runOnUiThread {
            uiController.showRouteCalculating()
        }
    }
    
    override fun onNavigationStarted(route: NavigationRoute) {
        Log.d("MainActivity", "Navigation started: ${route.totalDistance / 1000} km")
        runOnUiThread {
            // Show navigation UI
            uiController.showNavigationMode()
            
            // Display route on map - DON'T fit bounds, keep camera following car
            mapController.displayRoute(route, fitBounds = false)
            
            // Update UI with first step
            route.steps.firstOrNull()?.let { step ->
                uiController.updateNavigationStep(step)
            }
        }
    }
    
    override fun onNavigationStopped() {
        Log.d("MainActivity", "Navigation stopped")
        runOnUiThread {
            uiController.hideNavigationMode()
            mapController.clearRoute()
        }
    }
    
    override fun onPositionUpdated(position: VehiclePosition) {
        runOnUiThread {
            Log.d("MainActivity", "onPositionUpdated: ${position.location.latitude}, ${position.location.longitude}")
            // Update vehicle marker on map during navigation
            mapController.updateVehiclePosition(
                position.location.latitude,
                position.location.longitude,
                position.bearing
            )
        }
    }
    
    override fun onStateUpdated(state: NavigationState) {
        runOnUiThread {
            uiController.updateNavigationState(state)
        }
    }
    
    override fun onStepChanged(step: NavigationStep, stepIndex: Int) {
        Log.d("MainActivity", "Step changed: $stepIndex - ${step.instruction}")
        runOnUiThread {
            uiController.updateNavigationStep(step)
        }
    }
    
    override fun onDestinationReached() {
        Log.d("MainActivity", "Destination reached!")
        runOnUiThread {
            // Show popup first
            uiController.showDestinationReached()
        }
    }
    
    override fun onRouteRecalculated(route: NavigationRoute) {
        Log.d("MainActivity", "Route recalculated")
        runOnUiThread {
            mapController.displayRoute(route, fitBounds = false)
        }
    }
    
    override fun onNavigationError(error: String) {
        Log.e("MainActivity", "Navigation error: $error")
        runOnUiThread {
            uiController.showNavigationError(error)
        }
    }

    private fun setupSettingsButton() {
        val settingsButton = findViewById<ImageView>(R.id.btnSettings)
        settingsButton?.apply {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Log.d("SETTINGS", "Settings button clicked")
                showSettingsDialog()
            }
        }
        Log.d("SETTINGS", "Settings button setup complete: ${settingsButton != null}")
    }
    
    @SuppressLint("InflateParams")
    private fun showSettingsDialog() {
        // Inflate settings layout
        val settingsView = layoutInflater.inflate(R.layout.dialog_settings, null)
        
        // Add settings overlay to root layout
        val rootView = findViewById<ViewGroup>(android.R.id.content)
        rootView.addView(settingsView)
        
        // Get the switch
        val switchLightMode = settingsView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchLightMode)
        
        // Load current preference
        val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
        val isLightMode = prefs.getBoolean("lightMode", false)
        switchLightMode.isChecked = isLightMode
        
        // Handle switch changes
        switchLightMode.setOnCheckedChangeListener { _, isChecked ->
            // Save preference
            prefs.edit().putBoolean("lightMode", isChecked).apply()
            // Apply map style change
            mapController.setMapStyle(isChecked)
            Toast.makeText(this, if (isChecked) "Light mode enabled" else "Dark mode enabled", Toast.LENGTH_SHORT).show()
        }
        
        // Close button (X)
        settingsView.findViewById<ImageButton>(R.id.btnCloseSettings)?.setOnClickListener {
            rootView.removeView(settingsView)
        }
        
        // Done button
        settingsView.findViewById<Button>(R.id.btnSaveSettings)?.setOnClickListener {
            rootView.removeView(settingsView)
        }
        
        // Close on background click
        settingsView.setOnClickListener {
            rootView.removeView(settingsView)
        }
        
        // Prevent clicks on card from closing overlay
        settingsView.findViewById<View>(R.id.settingsCard)?.setOnClickListener {
            // Do nothing - prevent propagation
        }
    }

    private fun setupWeatherUpdates() {
        // Fetch weather every 10 minutes
        lifecycleScope.launch {
            fetchAndUpdateWeather()
            // Schedule periodic updates
            while (true) {
                kotlinx.coroutines.delay(600000) // 10 minutes
                fetchAndUpdateWeather()
            }
        }
    }

    private suspend fun fetchAndUpdateWeather() {
        val apiKey = BuildConfig.OPENWEATHER_API_KEY
        if (apiKey.isEmpty()) {
            Log.w("WEATHER", "OpenWeatherMap API key not configured")
            return
        }

        Log.d("WEATHER", "Fetching weather for location: $currentLat, $currentLon")
        val (weatherData, alerts) = OpenWeatherMapClient.getWeatherAndAlerts(currentLat, currentLon, apiKey)
        
        if (weatherData != null) {
            runOnUiThread {
                uiController.updateFullWeatherData(weatherData, alerts)
                Log.d("WEATHER", "Updated: ${weatherData.temperature}°C, Wind: ${weatherData.windSpeed}km/h, Humidity: ${weatherData.humidity}%, Condition: ${weatherData.weatherCondition}")
            }
        } else {
            Log.e("WEATHER", "Failed to fetch weather data - received null response")
        }
        
        // Handle weather alerts
        if (alerts.isNotEmpty()) {
            Log.d("WEATHER", "Found ${alerts.size} weather alerts")
            runOnUiThread {
                val activeAlerts = alertNotificationManager.getActiveAlerts(alerts)
                if (activeAlerts.isNotEmpty()) {
                    Log.d("WEATHER", "Showing ${activeAlerts.size} active weather alerts")
                    alertNotificationManager.showWeatherAlerts(activeAlerts)
                }
            }
        }
    }

    private fun fetchAndUpdateSpeedLimit(lat: Double, lon: Double) {
        lifecycleScope.launch {
            val speedLimit = OverpassApiClient.getSpeedLimit(lat, lon)
            runOnUiThread {
                if (speedLimit != "--") {
                    // Parse the speed limit (might be "50", "50 mph", etc.)
                    val limitValue = speedLimit.replace("[^0-9]".toRegex(), "")
                    if (limitValue.isNotEmpty()) {
                        currentSpeedLimit = limitValue.toInt()
                        uiController.updateSpeedLimit(currentSpeedLimit)
                        Log.d("SPEED_LIMIT", "Updated speed limit: $limitValue km/h")
                    } else {
                        // Empty after parsing - no valid speed limit
                        currentSpeedLimit = null
                        uiController.updateSpeedLimit(null)
                        Log.d("SPEED_LIMIT", "No valid speed limit in data")
                    }
                } else {
                    // No speed limit data available
                    currentSpeedLimit = null
                    uiController.updateSpeedLimit(null)
                    Log.d("SPEED_LIMIT", "No speed limit data available")
                }
            }
        }
    }

    private fun setupMqtt() {
        // Create MQTT manager with your broker details
        mqttManager = MqttManager(this, BuildConfig.MQTT_BROKER_ADDRESS, BuildConfig.MQTT_BROKER_PORT.toInt())

        // Set callback for received messages
        mqttManager.setOnMessageReceived { topic, message ->
            Log.d("MQTT_MSG", "Received on topic: $topic, message length: ${message.length}")
            Log.d("MQTT_MSG", "Checking if topic '$topic' starts with '${AppConfig.MQTT_TOPIC_ACCIDENT_ALERT}'")
            
            when {
                topic == AppConfig.MQTT_TOPIC_SPEED_ALERT -> {
                    Log.d(TAG, "Speed alert received!")
                    runOnUiThread {
                        uiController.showSpeedAlert()
                    }
                }
                topic == AppConfig.MQTT_TOPIC_OVERTAKING_ALERT -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Overtaking alert received: $message")
                    runOnUiThread {
                        showOvertakingWarning()
                    }
                }
                topic == AppConfig.MQTT_TOPIC_ACCIDENT_CLEARED -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "*** ACCIDENT CLEARED *** message: $message")
                    handleAccidentCleared(message)
                }
                topic.startsWith(AppConfig.MQTT_TOPIC_ACCIDENT_ALERT) || topic.contains("accident") -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "*** ACCIDENT ALERT DETECTED *** on topic: $topic")
                    if (BuildConfig.DEBUG) Log.d(TAG, "Accident message: $message")
                    handleAccidentAlert(topic, message)
                }
                topic == AppConfig.MQTT_TOPIC_EV_ALERT -> {
                    Log.d(TAG, "Emergency vehicle alert received: $message")
                    handleEmergencyVehicleAlert(message)
                }
                topic == AppConfig.MQTT_TOPIC_CAR_UPDATES -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Car update received: $message")
                    try {
                        val carData = org.json.JSONObject(message)
                        
                        // Parse car data - support both Digital Twin nested format and flat format
                        val parsedData = parseCarUpdateMessage(carData)
                        val carId = parsedData.carId
                        val lat = parsedData.latitude
                        val lon = parsedData.longitude
                        val speedKmh = parsedData.speedKmh
                        val headingDeg = parsedData.headingDeg
                        
                        Log.d(TAG, "Parsed car: $carId, lat=$lat, lon=$lon, speed=$speedKmh km/h, heading=$headingDeg")
                        
                        runOnUiThread {
                            // Classify car type using configurable sets
                            when {
                                carId in USER_CAR_IDS -> handleUserCarUpdate(lat, lon, headingDeg, speedKmh)
                                carId in OTHER_CAR_IDS -> handleOtherCarUpdate(carId, lat, lon, headingDeg)
                                carId in AppConfig.EMERGENCY_VEHICLE_IDS -> handleEVCarUpdate(carId, lat, lon, headingDeg)
                                else -> handleUnknownCarUpdate(lat, lon, headingDeg, speedKmh)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MQTT_MSG", "Error parsing car update: ${e.message}")
                    }
                }
                else -> {
                    // Ignore other alerts
                }
            }
        }

        // Connect to broker
        mqttManager.connect(
            onSuccess = {
                Log.d("MQTT_CONNECT", "Successfully connected to MQTT broker")
                runOnUiThread {
                    uiController.showConnectionStatus("Connected to broker")
                }
                // Subscribe to topics after connection
                mqttManager.subscribe(AppConfig.MQTT_TOPIC_ALERTS,
                    onSuccess = { 
                        runOnUiThread {
                            uiController.showConnectionStatus("Subscribed to ${AppConfig.MQTT_TOPIC_ALERTS}")
                        }
                    },
                    onError = { error ->
                        runOnUiThread {
                            uiController.showConnectionStatus("Subscribe failed: $error")
                        }
                    }
                )

                // Also subscribe to car updates
                mqttManager.subscribe(AppConfig.MQTT_TOPIC_CAR_UPDATES,
                    onSuccess = { 
                        Log.d(TAG, "Successfully subscribed to ${AppConfig.MQTT_TOPIC_CAR_UPDATES}")
                        runOnUiThread {
                            uiController.showConnectionStatus("Subscribed to ${AppConfig.MQTT_TOPIC_CAR_UPDATES}")
                        }
                    },
                    onError = { error ->
                        runOnUiThread {
                            uiController.showConnectionStatus("Subscribe failed: $error")
                        }
                    }
                )
                
                // Subscribe to accident alerts for all user car IDs
                USER_CAR_IDS.forEach { carId ->
                    val accidentTopic = "${AppConfig.MQTT_TOPIC_ACCIDENT_ALERT}/$carId"
                    mqttManager.subscribe(accidentTopic,
                        onSuccess = {
                            Log.d(TAG, "Subscribed to accident alerts for $carId")
                        },
                        onError = { error ->
                            Log.e(TAG, "Failed to subscribe to accident alerts for $carId: $error")
                        }
                    )
                }
                
                // Subscribe to accident cleared notifications
                mqttManager.subscribe(AppConfig.MQTT_TOPIC_ACCIDENT_CLEARED,
                    onSuccess = {
                        Log.d(TAG, "Subscribed to accident cleared notifications")
                    },
                    onError = { error ->
                        Log.e(TAG, "Failed to subscribe to accident cleared: $error")
                    }
                )
            },
            onError = { error ->
                runOnUiThread {
                    uiController.showConnectionStatus("Connection failed: $error")
                }
            }
        )
    }
    
    /**
     * Handle incoming accident alert from MQTT.
     * Parses the accident data and triggers UI updates.
     */
    private fun handleAccidentAlert(topic: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "=== handleAccidentAlert ENTRY ===")
        
        try {
            val json = org.json.JSONObject(message)
            
            // Extract target car ID from topic (alerts/accident/{car_id})
            val targetCarId = topic.substringAfterLast("/")
            
            // Verify this alert is for one of our user cars
            if (targetCarId !in USER_CAR_IDS) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Accident alert not for our car, ignoring")
                return
            }
            
            // Parse accident data
            val notificationType = json.optString("notification_type", "")
            if (notificationType != "accident_alert") {
                if (BuildConfig.DEBUG) Log.w(TAG, "Not an accident_alert notification type, ignoring")
                return
            }
            
            val eventId = json.optString("event_id", "")
            val distanceM = json.optDouble("distance_m", 0.0)
            val timestamp = json.optDouble("timestamp", System.currentTimeMillis() / 1000.0)
            
            // Extract accident location from nested accident object
            val accidentObj = json.optJSONObject("accident")
            val latitude = accidentObj?.optDouble("latitude", 0.0) ?: 0.0
            val longitude = accidentObj?.optDouble("longitude", 0.0) ?: 0.0
            val accidentCarId = accidentObj?.optString("car_id", "") ?: ""

            if (!isValidCoordinate(latitude, longitude)) {
                Log.w(TAG, "SECURITY: Invalid coordinates, rejecting alert")
                return
            }
            
            if (BuildConfig.DEBUG) Log.d(TAG, "VALID ACCIDENT ALERT: $eventId at ($latitude, $longitude)")
            
            // Create accident data object
            val accidentData = AlertNotificationManager.AccidentAlertData(
                eventId = eventId,
                latitude = latitude,
                longitude = longitude,
                distanceMeters = distanceM,
                timestamp = (timestamp * 1000).toLong()
            )
            
            runOnUiThread {
                Log.d(TAG, "Showing accident alert UI (no notification)...")
                // Show TTS and UI only (no system notification)
                alertNotificationManager.showAccidentAlert(accidentData) { data ->
                    Log.d(TAG, "Accident callback triggered - adding marker to map")
                    // Add accident marker to map at the accident location
                    mapController.addAccidentMarker(data.eventId, data.latitude, data.longitude)
                    
                    // Show UI alert banner
                    uiController.showAccidentAlert(data.distanceMeters)
                }
            }
            
            if (BuildConfig.DEBUG) Log.d(TAG, "=== handleAccidentAlert EXIT SUCCESS ===")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing accident alert", e)
        }
    }
    
    /**
     * Handle incoming accident cleared notification from MQTT.
     * Removes the accident marker from the map when accident is resolved.
     */
    private fun handleAccidentCleared(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "=== handleAccidentCleared ENTRY ===")
        
        try {
            val json = org.json.JSONObject(message)
            
            // Parse cleared notification
            val notificationType = json.optString("notification_type", "")
            if (notificationType != "accident_cleared") {
                if (BuildConfig.DEBUG) Log.w(TAG, "Not an accident_cleared notification type, ignoring")
                return
            }
            
            val eventId = json.optString("event_id", "")
            if (eventId.isEmpty()) {
                Log.w(TAG, "Accident cleared message missing event_id, ignoring")
                return
            }
            
            if (BuildConfig.DEBUG) Log.d(TAG, "ACCIDENT CLEARED: $eventId")
            
            runOnUiThread {
                // Remove accident marker from map
                mapController.removeAccidentMarker(eventId)
                Log.d(TAG, "Removed accident marker: $eventId")
            }
            
            if (BuildConfig.DEBUG) Log.d(TAG, "=== handleAccidentCleared EXIT SUCCESS ===")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing accident cleared notification", e)
        }
    }

    
    /**
     * Validate geographic coordinates
     */
    private fun isValidCoordinate(latitude: Double, longitude: Double): Boolean {
        return latitude in -90.0..90.0 && 
               longitude in -180.0..180.0 &&
               !(latitude == 0.0 && longitude == 0.0)  // Null Island check
    }

    /**
     * Data class for parsed car update message.
     * Supports both Digital Twin nested format and flat format.
     */
    private data class CarUpdateData(
        val carId: String,
        val latitude: Double,
        val longitude: Double,
        val speedKmh: Double,
        val headingDeg: Float
    )
    
    /**
     * Parse car update message from MQTT.
     * Supports multiple payload formats:
     * 1. Digital Twin Ditto format: {"gps": {"properties": {"latitude": x, "longitude": y}}}
     * 2. Flat format: {"car_id": "x", "latitude": y, "longitude": z, "speed_kmh": w, "heading_deg": v}
     * 3. Hono/Ditto topic format from features
     */
    private fun parseCarUpdateMessage(json: org.json.JSONObject): CarUpdateData {
        val pf = AppConfig.PayloadFormat
        
        // Try to get car_id (flat format or Digital Twin)
        var carId = json.optString(pf.CAR_ID_KEY, "")
        var lat = 0.0
        var lon = 0.0
        var speed = 0.0
        var heading = 0.0f
        
        // Check for Digital Twin Ditto nested format (from the Python script)
        // Format: {"gps": {"properties": {"latitude": x, "longitude": y}}}
        val gpsFeature = json.optJSONObject(pf.GPS_FEATURE_KEY)
        if (gpsFeature != null) {
            val properties = gpsFeature.optJSONObject(pf.PROPERTIES_KEY)
            if (properties != null) {
                lat = properties.optDouble(pf.LATITUDE_KEY, 0.0)
                lon = properties.optDouble(pf.LONGITUDE_KEY, 0.0)
                speed = properties.optDouble(pf.SPEED_KMH_KEY, properties.optDouble(pf.SPEED_KEY, 0.0))
                heading = properties.optDouble(pf.HEADING_DEG_KEY, properties.optDouble(pf.HEADING_KEY, 0.0)).toFloat()
                // Try to extract car_id from thing_id in topic if not present
                if (carId.isEmpty()) {
                    carId = json.optString(pf.THING_ID_KEY, "main-car")
                        .substringAfterLast(":")
                        .substringAfterLast("/")
                        .ifEmpty { "main-car" }
                }
            }
        }
        
        // Check for features wrapper (Ditto thing format)
        // Format: {"features": {"gps": {"properties": {"latitude": x, "longitude": y}}}}
        val features = json.optJSONObject(pf.FEATURES_KEY)
        if (features != null && lat == 0.0) {
            val gpsFromFeatures = features.optJSONObject(pf.GPS_FEATURE_KEY)
            if (gpsFromFeatures != null) {
                val properties = gpsFromFeatures.optJSONObject(pf.PROPERTIES_KEY)
                if (properties != null) {
                    lat = properties.optDouble(pf.LATITUDE_KEY, 0.0)
                    lon = properties.optDouble(pf.LONGITUDE_KEY, 0.0)
                    speed = properties.optDouble(pf.SPEED_KMH_KEY, properties.optDouble(pf.SPEED_KEY, 0.0))
                    heading = properties.optDouble(pf.HEADING_DEG_KEY, properties.optDouble(pf.HEADING_KEY, 0.0)).toFloat()
                }
            }
            // Extract car_id from thingId
            if (carId.isEmpty()) {
                carId = json.optString(pf.THING_ID_KEY, "main-car")
                    .substringAfterLast(":")
                    .substringAfterLast("/")
                    .ifEmpty { "main-car" }
            }
        }
        
        // Fallback to flat format if nested format didn't work
        if (lat == 0.0 && lon == 0.0) {
            lat = json.optDouble(pf.LATITUDE_KEY, 0.0)
            lon = json.optDouble(pf.LONGITUDE_KEY, 0.0)
            speed = json.optDouble(pf.SPEED_KMH_KEY, 0.0)
            heading = json.optDouble(pf.HEADING_DEG_KEY, 0.0).toFloat()
        }
        
        // Default car_id if still empty
        if (carId.isEmpty()) {
            carId = "main-car"
        }
        
        return CarUpdateData(carId, lat, lon, speed, heading)
    }
    
    /**
     * Handle user's car position update.
     * Updates map, navigation, speed display, and triggers relevant UI updates.
     */
    private fun handleUserCarUpdate(lat: Double, lon: Double, heading: Float, speedKmh: Double) {
        // Update current location and bearing
        currentLat = lat
        currentLon = lon
        currentBearing = heading
        currentSpeed = speedKmh
        
        // Update map and navigation
        if (navigationManager.isNavigating()) {
            // During navigation, feed position to NavigationManager
            // This triggers route tracking, step updates, and rerouting
            navigationManager.onMqttPositionUpdate(lat, lon, heading, speedKmh)
            // Map update is handled by NavigationListener.onPositionUpdated()
        } else {
            // Not navigating - just update position on map
            mapController.updateUserCar(lat, lon, heading)
        }
        
        // Update speed display
        uiController.updateCurrentSpeed(speedKmh.toInt(), currentSpeedLimit)
        
        // Track user position and bearing for top-down view
        userCarLat = lat
        userCarLon = lon
        userCarBearing = heading
        
        // Fetch speed limit (throttled internally)
        fetchAndUpdateSpeedLimit(lat, lon)
        
        // Handle overtaking animation state
        if (!hasSeenUserCar) {
            hasSeenUserCar = true
        }
        updateTopDownView()
        
        // Check speed threshold for alerts
        updateSpeedAlert(speedKmh)
    }
    
    /**
     * Handle other car (e.g., overtaking car) position update.
     * Uses throttling to prevent map overload - updates stored immediately,
     * but map is updated in batches at most every 100ms.
     */
    private fun handleOtherCarUpdate(carId: String, lat: Double, lon: Double, heading: Float) {
        // Track other car position (update or add without clearing others)
        otherCarPositions[carId] = OtherCarPosition(carId, lat, lon, heading)
        
        if (!hasSeenOtherCar) {
            hasSeenOtherCar = true
        }
        
        // Schedule throttled map update
        scheduleOtherCarMapUpdate()
        
        // Update top-down view immediately (lightweight)
        updateTopDownView()
    }
    
    /**
     * Schedule a throttled update of other cars on the map.
     * If an update is already pending, it will be cancelled and rescheduled.
     * This ensures we batch multiple position updates together.
     */
    private fun scheduleOtherCarMapUpdate() {
        // Cancel any pending update
        otherCarUpdateRunnable?.let { otherCarUpdateHandler.removeCallbacks(it) }
        
        // Create new update runnable
        otherCarUpdateRunnable = Runnable {
            // Update map with all current other car positions
            val carDataList = otherCarPositions.values.map { 
                MapController.OtherCarData(it.carId, it.lat, it.lon, it.heading) 
            }
            mapController.updateOtherCars(carDataList)
        }
        
        // Schedule update after throttle delay
        otherCarUpdateRunnable?.let { 
            otherCarUpdateHandler.postDelayed(it, OTHER_CAR_UPDATE_THROTTLE_MS) 
        }
    }
    
    /**
     * Handle unknown car ID - ignore unknown cars to prevent interference.
     * Only known cars (in USER_CAR_IDS or OTHER_CAR_IDS) should be processed.
     */
    private fun handleUnknownCarUpdate(lat: Double, lon: Double, heading: Float, speedKmh: Double) {
        // IGNORE unknown cars - don't update anything
        // This prevents random cars from moving our camera or markers
        Log.d(TAG, "Unknown car_id received, ignoring (not in USER_CAR_IDS or OTHER_CAR_IDS)")
    }
    
    /**
     * Reset overtaking animation state.
     */
    private fun resetOvertakingState() {
        if (hasSeenUserCar || hasSeenOtherCar || overtakingAnimationStarted) {
            hasSeenUserCar = false
            hasSeenOtherCar = false
            overtakingAnimationStarted = false
            otherCarPositions.clear()
            mapController.clearOtherCar()
            overtakingWarningIcon.visibility = View.GONE
            Log.d(TAG, "Reset overtaking animation state")
        }
    }
    
    /**
     * Show overtaking warning when alert is received via MQTT.
     */
    private fun showOvertakingWarning() {
        Log.d(TAG, "Showing overtaking warning")
        overtakingAnimationStarted = true
        overtakingWarningIcon.visibility = View.VISIBLE
        
        // Auto-hide after 5 seconds
        overtakingWarningIcon.postDelayed({
            hideOvertakingWarning()
        }, 5000)
    }
    
    /**
     * Hide overtaking warning.
     */
    private fun hideOvertakingWarning() {
        Log.d(TAG, "Hiding overtaking warning")
        overtakingWarningIcon.visibility = View.GONE
        overtakingAnimationStarted = false
    }
    
    // ========== Emergency Vehicle Handling ==========
    
    /**
     * Handle an emergency vehicle proximity alert from MQTT.
     * Alert JSON format (from backend ev-detector):
     * {
     *   "alert_type": "emergency_vehicle_nearby",
     *   "emergency_vehicle_id": "ev-test-emergency",
     *   "regular_car_id": "navigation-car",
     *   "distance_m": 342.15,
     *   "ev_latitude": ..., "ev_longitude": ...,
     *   "car_latitude": ..., "car_longitude": ...,
     *   "timestamp": ...
     * }
     */
    private fun handleEmergencyVehicleAlert(message: String) {
        try {
            val json = org.json.JSONObject(message)
            val evId = json.optString("emergency_vehicle_id", "unknown")
            val regularCarId = json.optString("regular_car_id", "")
            val evLat = json.optDouble("ev_latitude", 0.0)
            val evLon = json.optDouble("ev_longitude", 0.0)
            
            // Only process if this alert is for one of our user cars
            if (regularCarId.isNotEmpty() && regularCarId !in USER_CAR_IDS) {
                Log.d(TAG, "EV alert not for our car ($regularCarId), ignoring")
                return
            }
            
            // Compute live distance from current user position
            val liveDistanceM = haversineDistanceM(currentLat, currentLon, evLat, evLon)
            
            Log.d(TAG, "EV alert: $evId is ${liveDistanceM.toInt()}m away (live)")
            
            // Track as active EV
            activeEmergencyVehicles[evId] = System.currentTimeMillis()
            
            // Update EV marker on map and tracking
            runOnUiThread {
                if (evLat != 0.0 && evLon != 0.0) {
                    val bearing = calculateBearingFromCoords(evLat, evLon, currentLat, currentLon)
                    
                    // Track EV position for top-down view and map (consistent with handleEVCarUpdate)
                    evCarPositions[evId] = EVCarPosition(evId, evLat, evLon, bearing)
                    
                    // Schedule throttled map update
                    scheduleEVMapUpdate()
                    
                    // Update top-down view
                    updateTopDownView()
                }
                
                // Show/expand the EV notification overlay (only triggers expand on first time)
                evOverlay.showAlert(evId, liveDistanceM)
            }
            
            // Schedule EV cleanup check - if no new alert within 10s, EV likely out of range
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                checkAndCleanupEV(evId)
            }, 10000)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing EV alert: ${e.message}")
        }
    }
    
    /**
     * Handle position updates from an emergency vehicle (shows unique arrow on map).
     * Uses throttling to prevent map overload - same efficient pattern as other cars.
     * Supports multiple emergency vehicles simultaneously.
     */
    private fun handleEVCarUpdate(carId: String, lat: Double, lon: Double, heading: Float) {
        // Track EV position (update or add without clearing others)
        evCarPositions[carId] = EVCarPosition(carId, lat, lon, heading)
        
        // Schedule throttled map update (batches multiple EV updates together)
        scheduleEVMapUpdate()
        
        // Update top-down view immediately (lightweight)
        updateTopDownView()
        
        // If this EV is actively tracked (in range), refresh timestamp and update live distance
        if (activeEmergencyVehicles.containsKey(carId)) {
            activeEmergencyVehicles[carId] = System.currentTimeMillis()
            
            // Compute live distance and update overlay in real-time
            val liveDistanceM = haversineDistanceM(currentLat, currentLon, lat, lon)
            evOverlay.updateDistance(liveDistanceM)
        }
    }
    
    /**
     * Schedule a throttled update of emergency vehicles on the map.
     * Uses the same efficient batch pattern as other cars.
     * Supports multiple EVs and coexists with non-emergency vehicles.
     */
    private fun scheduleEVMapUpdate() {
        // Cancel any pending update
        evUpdateRunnable?.let { evUpdateHandler.removeCallbacks(it) }
        
        // Create new update runnable
        evUpdateRunnable = Runnable {
            // Update map with all current EV positions in a single batch
            val evDataList = evCarPositions.values.map { 
                MapController.EVCarData(it.carId, it.lat, it.lon, it.heading) 
            }
            mapController.updateEmergencyVehicles(evDataList)
        }
        
        // Schedule update after throttle delay
        evUpdateRunnable?.let { 
            evUpdateHandler.postDelayed(it, EV_UPDATE_THROTTLE_MS) 
        }
    }
    
    /**
     * Check if an EV should be cleaned up (no recent alerts = out of range).
     */
    private fun checkAndCleanupEV(evId: String) {
        val lastSeen = activeEmergencyVehicles[evId] ?: return
        val elapsed = System.currentTimeMillis() - lastSeen
        
        // If no new alert in 10+ seconds, consider EV out of range
        if (elapsed >= 9500) {
            Log.d(TAG, "EV $evId appears out of range, cleaning up")
            activeEmergencyVehicles.remove(evId)
            evCarPositions.remove(evId)
            
            // Schedule map update to reflect removal
            scheduleEVMapUpdate()
            updateTopDownView()
            
            // If no more active EVs, dismiss the overlay entirely
            if (activeEmergencyVehicles.isEmpty()) {
                evOverlay.dismiss()
            }
        }
    }
    
    /**
     * Calculate bearing from one point toward another.
     */
    private fun calculateBearingFromCoords(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double
    ): Float {
        val lat1 = Math.toRadians(fromLat)
        val lon1 = Math.toRadians(fromLon)
        val lat2 = Math.toRadians(toLat)
        val lon2 = Math.toRadians(toLon)
        val y = Math.sin(lon2 - lon1) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(lon2 - lon1)
        val brng = Math.toDegrees(Math.atan2(y, x))
        return ((brng + 360.0) % 360.0).toFloat()
    }
    
    /**
     * Compute haversine distance in meters between two lat/lon points.
     */
    private fun haversineDistanceM(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }
    
    /**
     * Update speed alert based on current speed.
     */
    private fun updateSpeedAlert(speedKmh: Double) {
        if (speedKmh < SPEED_ALERT_THRESHOLD) {
            uiController.hideSpeedAlert()
        } else {
            uiController.showSpeedAlert()
        }
    }

    /**
     * Convert GPS coordinates to relative meters (approximate)
     * Returns Pair(lateralMeters, longitudinalMeters) relative to user car
     * Takes into account the user car's bearing for direction-aware positioning
     */
    private fun calculateRelativePosition(otherLat: Double, otherLon: Double): Pair<Float, Float> {
        // Earth radius in meters
        val earthRadius = 6371000.0

        // Convert to radians
        val lat1 = Math.toRadians(userCarLat)
        val lon1 = Math.toRadians(userCarLon)
        val lat2 = Math.toRadians(otherLat)
        val lon2 = Math.toRadians(otherLon)

        // Calculate differences in meters (approximate for small distances)
        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        val northMeters = (dLat * earthRadius).toFloat()
        val eastMeters = (dLon * earthRadius * Math.cos(lat1)).toFloat()

        // Rotate coordinates based on user car bearing
        // Bearing 0° = North, 90° = East, 180° = South, 270° = West
        val bearingRad = Math.toRadians(userCarBearing.toDouble())
        val cosB = Math.cos(bearingRad).toFloat()
        val sinB = Math.sin(bearingRad).toFloat()

        // Transform to car's reference frame (forward = +y, left = -x, right = +x)
        val lateralMeters = eastMeters * cosB - northMeters * sinB  // Perpendicular to heading
        val longitudinalMeters = northMeters * cosB + eastMeters * sinB  // Along heading

        return Pair(lateralMeters, longitudinalMeters)
    }

    /**
     * Update the top-down car view with current relative positions
     */
    private fun updateTopDownView() {
        if (userCarLat == 0.0 || userCarLon == 0.0) {
            return // User position not set yet
        }

        // Convert other car positions to relative coordinates
        val relativePositions = otherCarPositions.values.map { otherCar ->
            val (x, y) = calculateRelativePosition(otherCar.lat, otherCar.lon)
            TopDownCarView.CarPosition(x, y)
        }

        topDownCarView.updateOtherCars(relativePositions)
        
        // Convert EV positions to relative coordinates
        val evRelativePositions = evCarPositions.values.map { ev ->
            val (x, y) = calculateRelativePosition(ev.lat, ev.lon)
            TopDownCarView.CarPosition(x, y)
        }

        topDownCarView.updateEVCars(evRelativePositions)
    }

    override fun onStart() {
        super.onStart()
        mapController.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapController.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapController.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapController.onStop()
    }



    override fun onDestroy() {
        // Cancel any pending other car updates
        otherCarUpdateRunnable?.let { otherCarUpdateHandler.removeCallbacks(it) }
        
        navigationManager.destroy()
        mqttManager.disconnect()
        alertNotificationManager.shutdown()
        uiController.cleanup()
        mapController.onDestroy()
        super.onDestroy()
    }
}
