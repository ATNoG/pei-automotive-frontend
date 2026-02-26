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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.config.AlertPreferenceManager
import com.example.myapplication.config.AlertSettingsDialog
import com.example.myapplication.config.AppConfig
import com.example.myapplication.mqtt.MqttEventListener
import com.example.myapplication.mqtt.MqttEventRouter
import com.example.myapplication.navigation.NavigationListener
import com.example.myapplication.navigation.NavigationManager
import com.example.myapplication.navigation.models.*
import com.example.myapplication.navigation.routing.OsrmApiClient
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer

class MainActivity : AppCompatActivity(), NavigationListener, MqttEventListener {

    companion object {
        private const val TAG = "MainActivity"

        // Car IDs configuration - delegate to AppConfig for centralized management
        val USER_CAR_IDS get() = AppConfig.USER_CAR_IDS
        val OTHER_CAR_IDS get() = AppConfig.OTHER_CAR_IDS
    }

    private lateinit var mapController: MapController
    private lateinit var uiController: UiController
    private lateinit var mqttManager: MqttManager
    private lateinit var mqttEventRouter: MqttEventRouter
    private lateinit var vehicleTracker: VehicleTracker
    private lateinit var navigationManager: NavigationManager
    private lateinit var alertNotificationManager: AlertNotificationManager
    private lateinit var alertPreferenceManager: AlertPreferenceManager
    private lateinit var alertSettingsDialog: AlertSettingsDialog

    // Initial position from config
    private val initialPosition = AppConfig.DEFAULT_INITIAL_POSITION

    // Destination from config
    private val mercadoSantiago = AppConfig.Destinations.MERCADO_SANTIAGO

    private var currentLat: Double = AppConfig.DEFAULT_INITIAL_POSITION.latitude
    private var currentLon: Double = AppConfig.DEFAULT_INITIAL_POSITION.longitude
    private var currentSpeed: Double = 0.0
    private var currentBearing: Float = 0f

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
        alertPreferenceManager = AlertPreferenceManager(this)
        alertNotificationManager = AlertNotificationManager(this, alertPreferenceManager)
        alertNotificationManager.requestNotificationPermission()
        alertSettingsDialog = AlertSettingsDialog(this, alertPreferenceManager, mapController)

        // Initialize vehicle tracker (owns position state, throttling, top-down view)
        vehicleTracker = VehicleTracker(
            mapController,
            findViewById(R.id.topDownCarView),
            findViewById(R.id.overtakingWarningIcon),
            findViewById(R.id.evOverlay)
        )

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

        // Setup MQTT via event router (after uiController is initialized)
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
            
            // Update UI with first step (if navigation alerts enabled)
            if (alertNotificationManager.shouldProcessAlert(AlertPreferenceManager.AlertType.NAVIGATION)) {
                route.steps.firstOrNull()?.let { step ->
                    uiController.updateNavigationStep(step)
                }
            } else {
                // Hide instruction banner but keep nav panel
                findViewById<LinearLayout>(R.id.navigationBanner)?.visibility = View.GONE
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
            if (alertNotificationManager.shouldProcessAlert(AlertPreferenceManager.AlertType.NAVIGATION)) {
                uiController.updateNavigationStep(step)
            }
        }
        // Speak navigation instruction (respects audio preference, non-flush to not interrupt)
        alertNotificationManager.speakForAlert(
            AlertPreferenceManager.AlertType.NAVIGATION,
            step.instruction,
            flush = false
        )
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
        alertSettingsDialog.show()
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
        if (alerts.isNotEmpty() && alertNotificationManager.shouldProcessAlert(AlertPreferenceManager.AlertType.WEATHER)) {
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

    private fun setupMqtt() {
        mqttManager = MqttManager(this, BuildConfig.MQTT_BROKER_ADDRESS, BuildConfig.MQTT_BROKER_PORT.toInt())
        mqttEventRouter = MqttEventRouter(mqttManager, alertNotificationManager, USER_CAR_IDS)
        mqttEventRouter.setListener(this)
        mqttEventRouter.connectAndSubscribe()
    }

    // ========== MqttEventListener Implementation ==========

    override fun onSpeedAlert() {
        runOnUiThread { uiController.showSpeedAlert() }
    }

    override fun onOvertakingAlert() {
        runOnUiThread { vehicleTracker.showOvertakingWarning() }
    }

    override fun onAccidentAlert(topic: String, payload: String) {
        handleAccidentAlert(topic, payload)
    }

    override fun onAccidentCleared(payload: String) {
        handleAccidentCleared(payload)
    }

    override fun onEmergencyVehicleAlert(payload: String) {
        handleEmergencyVehicleAlert(payload)
    }

    override fun onCarUpdate(data: MqttEventRouter.CarUpdateData) {
        runOnUiThread {
            when {
                data.carId in USER_CAR_IDS -> handleUserCarUpdate(data)
                data.carId in OTHER_CAR_IDS -> handleOtherCarUpdate(data)
                data.carId in AppConfig.EMERGENCY_VEHICLE_IDS -> handleEVCarUpdate(data)
                else -> Log.d(TAG, "Unknown car_id ${data.carId}, ignoring")
            }
        }
    }

    override fun onMqttConnected() {
        runOnUiThread { uiController.showConnectionStatus("Connected to broker") }
    }

    override fun onMqttError(error: String) {
        runOnUiThread { uiController.showConnectionStatus(error) }
    }

    // ========== Car Update Handlers ==========

    private fun handleUserCarUpdate(data: MqttEventRouter.CarUpdateData) {
        currentLat = data.latitude
        currentLon = data.longitude
        currentBearing = data.headingDeg
        currentSpeed = data.speedKmh

        vehicleTracker.updateUserPosition(data.latitude, data.longitude, data.headingDeg)

        if (navigationManager.isNavigating()) {
            navigationManager.onMqttPositionUpdate(data.latitude, data.longitude, data.headingDeg, data.speedKmh)
        } else {
            mapController.updateUserCar(data.latitude, data.longitude, data.headingDeg)
        }

        uiController.updateSpeedLimit(data.speedLimitKmh)

        // Only pass speed limit for visual speeding indicators if alert is enabled
        val effectiveLimit = if (alertNotificationManager.shouldProcessAlert(AlertPreferenceManager.AlertType.SPEEDING))
            data.speedLimitKmh else null
        uiController.updateCurrentSpeed(data.speedKmh.toInt(), effectiveLimit)
        updateSpeedAlert(data.speedKmh, effectiveLimit)
    }

    private fun handleOtherCarUpdate(data: MqttEventRouter.CarUpdateData) {
        vehicleTracker.updateOtherCar(data.carId, data.latitude, data.longitude, data.headingDeg)
    }

    private fun handleEVCarUpdate(data: MqttEventRouter.CarUpdateData) {
        vehicleTracker.updateEVCar(data.carId, data.latitude, data.longitude, data.headingDeg)
    }
    
    // ========== Accident Handling ==========

    private fun handleAccidentAlert(topic: String, message: String) {
        if (!alertNotificationManager.shouldProcessAlert(AlertPreferenceManager.AlertType.ACCIDENT)) return

        try {
            val json = org.json.JSONObject(message)
            val targetCarId = topic.substringAfterLast("/")
            if (targetCarId !in USER_CAR_IDS) return

            val notificationType = json.optString("notification_type", "")
            if (notificationType != "accident_alert") return

            val eventId = json.optString("event_id", "")
            val distanceM = json.optDouble("distance_m", 0.0)
            val timestamp = json.optDouble("timestamp", System.currentTimeMillis() / 1000.0)

            val accidentObj = json.optJSONObject("accident")
            val latitude = accidentObj?.optDouble("latitude", 0.0) ?: 0.0
            val longitude = accidentObj?.optDouble("longitude", 0.0) ?: 0.0

            if (!vehicleTracker.isValidCoordinate(latitude, longitude)) {
                Log.w(TAG, "SECURITY: Invalid coordinates, rejecting alert")
                return
            }

            val accidentData = AlertNotificationManager.AccidentAlertData(
                eventId = eventId,
                latitude = latitude,
                longitude = longitude,
                distanceMeters = distanceM,
                timestamp = (timestamp * 1000).toLong()
            )

            runOnUiThread {
                alertNotificationManager.showAccidentAlert(accidentData) { data ->
                    mapController.addAccidentMarker(data.eventId, data.latitude, data.longitude)
                    uiController.showAccidentAlert(data.distanceMeters)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing accident alert", e)
        }
    }

    private fun handleAccidentCleared(message: String) {
        try {
            val json = org.json.JSONObject(message)
            if (json.optString("notification_type", "") != "accident_cleared") return

            val eventId = json.optString("event_id", "")
            if (eventId.isEmpty()) return

            runOnUiThread {
                mapController.removeAccidentMarker(eventId)
                Log.d(TAG, "Removed accident marker: $eventId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing accident cleared notification", e)
        }
    }

    // ========== Emergency Vehicle Handling ==========

    private fun handleEmergencyVehicleAlert(message: String) {
        try {
            val json = org.json.JSONObject(message)
            val evId = json.optString("emergency_vehicle_id", "unknown")
            val regularCarId = json.optString("regular_car_id", "")
            val evLat = json.optDouble("ev_latitude", 0.0)
            val evLon = json.optDouble("ev_longitude", 0.0)

            if (regularCarId.isNotEmpty() && regularCarId !in USER_CAR_IDS) return

            runOnUiThread {
                vehicleTracker.handleEVProximityAlert(evId, evLat, evLon)

                val liveDistance = vehicleTracker.liveDistanceToUser(evLat, evLon)
                alertNotificationManager.speakForAlert(
                    AlertPreferenceManager.AlertType.EMERGENCY_VEHICLE,
                    "Warning, emergency vehicle approaching"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing EV alert: ${e.message}")
        }
    }

    // ========== Speed Alert ==========

    private fun updateSpeedAlert(speedKmh: Double, speedLimitKmh: Int?) {
        if (!alertNotificationManager.shouldProcessAlert(AlertPreferenceManager.AlertType.SPEEDING)) {
            uiController.hideSpeedAlert()
            return
        }
        if (speedLimitKmh != null && speedKmh > speedLimitKmh) {
            uiController.showSpeedAlert()
        } else {
            uiController.hideSpeedAlert()
        }
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
        vehicleTracker.destroy()
        mqttEventRouter.disconnect()
        navigationManager.destroy()
        alertNotificationManager.shutdown()
        uiController.cleanup()
        mapController.onDestroy()
        super.onDestroy()
    }
}
