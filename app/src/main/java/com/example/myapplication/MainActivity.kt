package com.example.myapplication

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.config.AlertPreferenceManager
import com.example.myapplication.config.AlertSettingsDialog
import com.example.myapplication.config.AppConfig
import com.example.myapplication.config.WeatherSourcePreferenceManager
import com.example.myapplication.mqtt.MqttEventListener
import com.example.myapplication.mqtt.MqttEventRouter
import com.example.myapplication.navigation.NavigationListener
import com.example.myapplication.notifications.AlertNotificationManager
import com.example.myapplication.notifications.InAppNotificationManager
import com.example.myapplication.navigation.NavigationManager
import com.example.myapplication.navigation.models.*
import com.example.myapplication.navigation.routing.OsrmApiClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import java.util.Locale

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
    private lateinit var overtakingEdgeLightView: OvertakingEdgeLightView
    private lateinit var navigationManager: NavigationManager
    private lateinit var inAppNotificationManager: InAppNotificationManager
    private lateinit var alertNotificationManager: AlertNotificationManager
    // Note: both managers live in com.example.myapplication.notifications
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

    // Cached station assignment for Ditto weather source
    private var lastStationAssignment: MqttEventRouter.StationAssignmentData? = null
    private var hasRealStationAssignment = false // true when data came from station_assigner, not client-side fallback

    override fun onCreate(savedInstanceState: Bundle?) {
        val appPrefs = getSharedPreferences("AppSettings", MODE_PRIVATE)

        // Apply explicit app light/dark preference before view inflation.
        val isLightMode = appPrefs.getBoolean("lightMode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isLightMode) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )

        // Apply colorblind theme before inflating any views
        if (appPrefs.getBoolean("colorBlindMode", false)) {
            setTheme(R.style.Theme_MyApplication_ColorBlind)
        }

        super.onCreate(savedInstanceState)

        // Set app locale based on user preference
        setAppLocale()

        // initialize MapLibre (only required once, before creating MapView)
        MapLibre.getInstance(this, BuildConfig.MAPTILER_API_KEY, WellKnownTileServer.MapTiler)

        setContentView(R.layout.activity_main)

        // Initialize OsrmApiClient with OpenRouteService API key
        OsrmApiClient.initialize(BuildConfig.OPENROUTESERVICE_API_KEY)

        // create controllers (after setContentView so views exist)
        mapController = MapController(this, findViewById(R.id.mapView))
        uiController = UiController(this) { onWeatherSourceChanged() }
        overtakingEdgeLightView = attachOvertakingEdgeLight()
        alertPreferenceManager = AlertPreferenceManager(this)
        inAppNotificationManager = InAppNotificationManager(this)
        alertNotificationManager = AlertNotificationManager(this, alertPreferenceManager, inAppNotificationManager)
        alertNotificationManager.requestNotificationPermission()
        alertSettingsDialog = AlertSettingsDialog(this, alertPreferenceManager, mapController)

        // Initialize vehicle tracker (owns position state, throttling, top-down view)
        vehicleTracker = VehicleTracker(
            mapController,
            findViewById(R.id.topDownCarView),
            findViewById(R.id.overtakingWarningIcon),
            overtakingEdgeLightView
        )

        // Swap overtaking warning icon for colorblind mode
        if (appPrefs.getBoolean("colorBlindMode", false)) {
            findViewById<ImageView>(R.id.overtakingWarningIcon)
                .setImageResource(R.drawable.cb_warning)
        }

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
        }
        startTokenRefreshScheduler()
    }

    private fun setupNavigation() {
        navigationManager = NavigationManager()
        navigationManager.setNavigationListener(this)
    }

    private fun setupNavigationButton() {
        // Start Route button (top right panel)
        findViewById<TextView>(R.id.btnStartRoute)?.apply {
            applyPressAnimation(this@MainActivity) {
                showNavigationDialog()
            }
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
            txtRouteInfo?.text = getString(R.string.calculating_route)
            btnStartNavigation?.isEnabled = false
            btnStartNavigation?.text = getString(R.string.calculating)

            // Calculate route to Mercado Santiago
            calculateRouteForDialog(mercadoSantiago) { route ->
                if (route != null) {
                    pendingRoute = route
                    val distKm = String.format("%.1f", route.totalDistance / 1000)
                    val timeMin = (route.totalDuration / 60).toInt()
                    txtRouteInfo?.text = getString(R.string.route_info_format, distKm, timeMin.toString())
                    btnStartNavigation?.isEnabled = true
                    btnStartNavigation?.text = getString(R.string.start_navigation)

                    // Auto-start navigation after route calculation
                    rootView.postDelayed({
                        if (pendingRoute != null) {
                            rootView.removeView(overlayView)
                            startNavigation(route)
                        }
                    }, 300)
                } else {
                    txtRouteInfo?.text = getString(R.string.error_calculating_route)
                    btnStartNavigation?.isEnabled = false
                    btnStartNavigation?.text = getString(R.string.try_again)
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
        runOnUiThread { uiController.showRouteCalculating() }
    }

    override fun onNavigationStarted(route: NavigationRoute) {
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
            inAppNotificationManager.show(
                type = InAppNotificationManager.Type.SUCCESS,
                title = "You have arrived!",
                message = "Navigation complete",
                duration = 5_000L,
                onDismissed = ::stopNavigationAfterArrival
            )
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
        runOnUiThread { uiController.showNavigationError(error) }
    }

    private fun setupSettingsButton() {
        val settingsButton = findViewById<View>(R.id.btnSettings)
        settingsButton?.apply {
            isClickable = true
            isFocusable = true
            applyPressAnimation(this@MainActivity) {
                Log.d("SETTINGS", "Settings button clicked")
                showSettingsDialog()
            }
        }
        Log.d("SETTINGS", "Settings button setup complete: ${settingsButton != null}")
    }

    private fun attachOvertakingEdgeLight(): OvertakingEdgeLightView {
        val rootView = findViewById<ViewGroup>(android.R.id.content)
        return OvertakingEdgeLightView(this).also { edgeView ->
            edgeView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            rootView.addView(edgeView)
        }
    }

    @SuppressLint("InflateParams")
    private fun showSettingsDialog() {
        alertSettingsDialog.show()
    }

    private var weatherUpdateJob: kotlinx.coroutines.Job? = null

    private fun setupWeatherUpdates() {
        startOpenWeatherPolling()
    }

    private fun startOpenWeatherPolling() {
        weatherUpdateJob?.cancel()
        weatherUpdateJob = lifecycleScope.launch {
            fetchAndUpdateWeather()
            while (true) {
                kotlinx.coroutines.delay(AppConfig.WEATHER_UPDATE_INTERVAL_MS)
                fetchAndUpdateWeather()
            }
        }
    }

    private fun stopOpenWeatherPolling() {
        weatherUpdateJob?.cancel()
        weatherUpdateJob = null
    }

    /**
     * Called when the user toggles the weather data source in settings.
     * Starts/stops OpenWeatherMap polling accordingly.
     */
    private fun onWeatherSourceChanged() {
        val source = WeatherSourcePreferenceManager(this).getSource()
        Log.d(TAG, "Weather source changed to: ${source.label}")

        when (source) {
            WeatherSourcePreferenceManager.Source.OPEN_WEATHER_MAP -> {
                startOpenWeatherPolling()
            }
            WeatherSourcePreferenceManager.Source.DITTO -> {
                stopOpenWeatherPolling()
                runOnUiThread {
                    if (lastStationAssignment != null) {
                        Log.d(TAG, "Switching to Ditto — displaying cached station data")
                        uiController.updateDittoWeatherData(lastStationAssignment!!)
                    } else {
                        Log.d(TAG, "Switching to Ditto — no station data cached yet, showing waiting state")
                        uiController.showDittoWaitingState()
                    }
                }
            }
        }
    }

    private suspend fun fetchAndUpdateWeather() {
        // Skip if user selected Ditto as weather source
        if (uiController.getWeatherSource() == WeatherSourcePreferenceManager.Source.DITTO) return

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
                Log.d(
                    "WEATHER",
                    "Updated: ${weatherData.temperature}°C, Wind: ${weatherData.windSpeed}km/h, Humidity: ${weatherData.humidity}%, Condition: ${weatherData.weatherCondition}"
                )
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
        val token = com.example.myapplication.auth.TokenStore.getAccessToken(this)
        mqttManager = MqttManager(this, BuildConfig.MQTT_BROKER_ADDRESS, BuildConfig.MQTT_BROKER_PORT.toInt(), token)
        mqttEventRouter = MqttEventRouter(mqttManager, alertNotificationManager, USER_CAR_IDS)
        mqttEventRouter.setListener(this)
        mqttEventRouter.connectAndSubscribe()
    }

    // ========== MqttEventListener Implementation ==========

    override fun onSpeedAlert() {
        runOnUiThread { uiController.showSpeedAlert() }
    }

    override fun onOvertakingAlert(payload: String) {
        runOnUiThread { vehicleTracker.showOvertakingWarning(payload) }
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

    override fun onHighwayEntryAlert(payload: String) {
        handleHighwayEntryAlert(payload)
    }

    override fun onTrafficJamAlert(payload: String) {
        handleTrafficJamAlert(payload)
    }

    override fun onCarUpdate(data: MqttEventRouter.CarUpdateData) {
        runOnUiThread {
            val normalizedCarId = data.carId.trim()
            when {
                normalizedCarId in USER_CAR_IDS -> handleUserCarUpdate(data.copy(carId = normalizedCarId))
                normalizedCarId in OTHER_CAR_IDS -> handleOtherCarUpdate(data.copy(carId = normalizedCarId))
                normalizedCarId in AppConfig.EMERGENCY_VEHICLE_IDS -> handleEVCarUpdate(data.copy(carId = normalizedCarId))
                else -> {
                    // Debug: Show actual bytes to detect hidden characters
                    val bytes = data.carId.toByteArray().joinToString(",")
                    Log.d(
                        TAG,
                        "Unknown car_id raw='${data.carId}' normalized='$normalizedCarId' " +
                            "(rawLen=${data.carId.length}, normalizedLen=${normalizedCarId.length}), " +
                            "bytes=[$bytes], ignoring. Configured other cars: $OTHER_CAR_IDS"
                    )
                }
            }
        }
    }

    override fun onMeteoStationsUpdate(payload: String) {
        // Use meteo/updates as fallback: find nearest station client-side
        // This ensures data arrives even if station_assigner hasn't published a per-car assignment
        if (hasRealStationAssignment) {
            Log.d(TAG, "Meteo stations update received, but already have real station assignment — skipping")
            return
        }

        try {
            val json = org.json.JSONObject(payload)
            val stations = json.getJSONArray("stations")
            Log.d(TAG, "Meteo stations update: ${stations.length()} stations, finding nearest to ($currentLat, $currentLon)")

            var nearestData: MqttEventRouter.StationAssignmentData? = null
            var minDist = Double.MAX_VALUE

            for (i in 0 until stations.length()) {
                val s = stations.getJSONObject(i)
                val loc = s.optJSONObject("location") ?: continue
                val measurement = s.optJSONObject("measurement") ?: continue
                val sLat = loc.optDouble("latitude", 0.0)
                val sLon = loc.optDouble("longitude", 0.0)
                if (sLat == 0.0 && sLon == 0.0) continue

                val dist = vehicleTracker.haversineDistanceM(currentLat, currentLon, sLat, sLon)
                if (dist < minDist) {
                    minDist = dist
                    nearestData = MqttEventRouter.StationAssignmentData(
                        carId = "",
                        stationId = s.optInt("station_id", 0),
                        stationName = s.optString("location_name", ""),
                        stationLat = sLat,
                        stationLon = sLon,
                        temperature = measurement.optDouble("temperature", 0.0),
                        windIntensity = measurement.optDouble("wind_intensity", 0.0),
                        windDirection = measurement.optInt("wind_direction", 0),
                        humidity = measurement.optInt("humidity", 0),
                        pressure = measurement.optDouble("pressure", 0.0),
                        radiation = measurement.optDouble("radiation", 0.0),
                        accumulatedPrecipitation = measurement.optDouble("accumulated_precipitation", 0.0),
                        measurementTime = measurement.optString("time", "")
                    )
                }
            }

            if (nearestData != null) {
                Log.d(TAG, "Nearest station from meteo/updates: ${nearestData.stationName} (${minDist.toInt()}m away), temp=${nearestData.temperature}°C")
                lastStationAssignment = nearestData
                runOnUiThread { uiController.updateDittoWeatherData(nearestData) }
            } else {
                Log.w(TAG, "No valid stations found in meteo/updates payload")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing meteo stations update: ${e.message}")
        }
    }

    override fun onStationAssignment(data: MqttEventRouter.StationAssignmentData) {
        Log.d(TAG, "Station assignment received: car=${data.carId}, station=${data.stationId} (${data.stationName}), temp=${data.temperature}°C, wind=${data.windIntensity}km/h")
        lastStationAssignment = data
        hasRealStationAssignment = true
        runOnUiThread {
            uiController.updateDittoWeatherData(data)
        }
    }

    override fun onMqttConnected() {
        runOnUiThread {
            inAppNotificationManager.show(
                type = InAppNotificationManager.Type.INFO,
                title = "✔️ Connected",
                message = "Live data feed active",
                duration = InAppNotificationManager.SHORT_DURATION_MS
            )
        }
    }

    override fun onMqttError(error: String) {
        runOnUiThread {
            inAppNotificationManager.show(
                type = InAppNotificationManager.Type.ERROR,
                title = "❌ Connection Error",
                message = error
            )
        }
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
                    val distanceText = UiController.formatDistance(data.distanceMeters, getString(R.string.ahead))
                    inAppNotificationManager.show(
                        type = InAppNotificationManager.Type.ACCIDENT,
                        title = "⚠️ Accident Alert",
                        message = distanceText,
                        duration = 15_000L
                    )
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

    /** EV IDs for which we've already spoken the TTS warning. */
    private val evSpokenIds = mutableSetOf<String>()

    private fun handleEmergencyVehicleAlert(message: String) {
        try {
            val json = org.json.JSONObject(message)
            val evId = json.optString("emergency_vehicle_id", "unknown")
            val regularCarId = json.optString("regular_car_id", "")
            val evLat = json.optDouble("ev_latitude", 0.0)
            val evLon = json.optDouble("ev_longitude", 0.0)
            val evHeading = json.optDouble("ev_heading_deg", Double.NaN).toFloat()
            val direction = json.optString("direction", "nearby")
            val distanceM = json.optDouble("distance_m", Double.NaN)

            if (regularCarId.isNotEmpty() && regularCarId !in USER_CAR_IDS) return

            runOnUiThread {
                vehicleTracker.handleEVProximityAlert(evId, evLat, evLon, evHeading)

                val distanceText = if (!distanceM.isNaN()) {
                    UiController.formatDistance(distanceM, getString(R.string.away))
                } else {
                    UiController.formatDistance(
                        vehicleTracker.liveDistanceToUser(evLat, evLon), getString(R.string.away)
                    )
                }

                val evTag = "ev_$evId"
                val shown = inAppNotificationManager.showOrUpdate(
                    tag = evTag,
                    type = InAppNotificationManager.Type.EMERGENCY,
                    title = "🚨 Emergency Vehicle $direction",
                    message = distanceText,
                    duration = 8_000L,
                    onDismissed = { evSpokenIds.remove(evId) }
                )

                // Speak TTS only on the first alert for this EV
                if (shown && evId !in evSpokenIds) {
                    evSpokenIds.add(evId)
                    alertNotificationManager.speakForAlert(
                        AlertPreferenceManager.AlertType.EMERGENCY_VEHICLE,
                        getString(R.string.emergency_vehicle_warning)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing EV alert: ${e.message}")
        }
    }

    private fun handleHighwayEntryAlert(message: String) {
        try {
            val json = org.json.JSONObject(message)
            val status = json.optString("status", "unknown")
            val title = when (status) {
                "unsafe" -> "⚠️ Unsafe Highway Entry"
                "safe" -> "✅ Safe Highway Entry"
                else -> "Highway Entry Alert"
            }
            val messageText = "Highway entry detected: $status"
            val ttsText = when (status) {
                "unsafe" -> getString(R.string.highway_entry_warning_unsafe)
                "safe" -> getString(R.string.highway_entry_warning_safe)
                else -> getString(R.string.highway_entry_warning)
            }

            runOnUiThread {
                inAppNotificationManager.show(
                    type = if (status == "unsafe") InAppNotificationManager.Type.WARNING else InAppNotificationManager.Type.SUCCESS,
                    title = title,
                    message = messageText,
                    duration = InAppNotificationManager.DEFAULT_DURATION_MS
                )
                alertNotificationManager.speakForAlert(
                    AlertPreferenceManager.AlertType.HIGHWAY_ENTRY,
                    ttsText
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing highway entry alert: ${e.message}")
        }
    }

    private fun handleTrafficJamAlert(message: String) {
        try {
            val json = org.json.JSONObject(message)
            val distanceM = json.optDouble("distance_m", Double.NaN)
            val jam = json.optJSONObject("jam")
            val jamId = jam?.optString("jam_id")?.takeIf { it.isNotBlank() }
                ?: json.optString("jam_id", "")
            val jamLat = jam?.optDouble("center_latitude", Double.NaN)
                ?.takeUnless { it.isNaN() }
                ?: json.optDouble("center_latitude", Double.NaN)
            val jamLon = jam?.optDouble("center_longitude", Double.NaN)
                ?.takeUnless { it.isNaN() }
                ?: json.optDouble("center_longitude", Double.NaN)
            val jamActive = if (jam != null) {
                jam.optBoolean("active", true)
            } else {
                json.optBoolean("active", true)
            }

            val (messageText, ttsText) = if (!distanceM.isNaN() && distanceM >= 0.0) {
                val distanceText = UiController.formatDistance(distanceM, getString(R.string.ahead))
                Pair(
                    getString(R.string.traffic_jam_warning_short, distanceText),
                    getString(R.string.traffic_jam_warning, distanceText)
                )
            } else {
                Pair(
                    getString(R.string.traffic_jam_warning_generic),
                    getString(R.string.traffic_jam_warning_generic)
                )
            }

            runOnUiThread {
                if (!jamId.isNullOrBlank() && !jamLat.isNaN() && !jamLon.isNaN()) {
                    val markerId = "jam-$jamId"
                    if (jamActive) {
                        mapController.addAccidentMarker(markerId, jamLat, jamLon)
                    } else {
                        mapController.removeAccidentMarker(markerId)
                    }
                } else {
                    Log.d(
                        TAG,
                        "Traffic jam marker skipped (missing jam_id/coordinates): jamId=$jamId, lat=$jamLat, lon=$jamLon"
                    )
                }

                inAppNotificationManager.show(
                    type = InAppNotificationManager.Type.WARNING,
                    title = "🚦 Traffic Jam Alert",
                    message = messageText,
                    duration = InAppNotificationManager.DEFAULT_DURATION_MS
                )

                alertNotificationManager.speakForAlert(
                    AlertPreferenceManager.AlertType.TRAFFIC_JAM,
                    ttsText
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing traffic jam alert: ${e.message}")
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
        inAppNotificationManager.destroy()
        uiController.cleanup()
        mapController.onDestroy()
        super.onDestroy()
    }

    private fun setAppLocale() {
        val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
        val language = prefs.getString("language", "en") ?: "en"
        val locale = when (language) {
            "pt" -> Locale("pt", "PT")
            else -> Locale("en", "US")
        }
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun startTokenRefreshScheduler() {
        lifecycleScope.launch {
            while (isActive) {
                delay(60_000L) // check every minute
                if (!com.example.myapplication.auth.TokenStore.isAccessTokenValid(this@MainActivity)) {
                    val refreshToken = com.example.myapplication.auth.TokenStore.getRefreshToken(this@MainActivity)
                    if (refreshToken != null) {
                        val newTokens = com.example.myapplication.auth.KeycloakClient.refreshToken(refreshToken)
                        if (newTokens != null) {
                            com.example.myapplication.auth.TokenStore.save(
                                this@MainActivity,
                                newTokens.accessToken,
                                newTokens.refreshToken,
                                newTokens.expiresIn
                            )
                        } else {
                            // Refresh token also expired — force re-login
                            com.example.myapplication.auth.TokenStore.clear(this@MainActivity)
                            startActivity(Intent(this@MainActivity, com.example.myapplication.auth.LoginActivity::class.java))
                            finish()
                        }
                    }
                }
            }
        }
    }
}
