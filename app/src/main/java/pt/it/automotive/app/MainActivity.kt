package pt.it.automotive.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.content.pm.PackageManager
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import pt.it.automotive.app.auth.KeycloakClient
import pt.it.automotive.app.auth.LoginActivity
import pt.it.automotive.app.auth.TokenStore
import pt.it.automotive.app.config.AlertPreferenceManager
import pt.it.automotive.app.config.AlertSettingsDialog
import pt.it.automotive.app.config.AppConfig
import pt.it.automotive.app.config.WeatherSourcePreferenceManager
import pt.it.automotive.app.mqtt.MqttEventListener
import pt.it.automotive.app.mqtt.MqttEventRouter
import pt.it.automotive.app.navigation.NavigationListener
import pt.it.automotive.app.navigation.geocoding.GeocodeApiClient
import pt.it.automotive.app.navigation.geocoding.GeoCodeResult
import pt.it.automotive.app.navigation.geocoding.GeoCodeResultAdapter
import pt.it.automotive.app.notifications.AlertNotificationManager
import pt.it.automotive.app.notifications.InAppNotificationManager
import pt.it.automotive.app.navigation.NavigationManager
import pt.it.automotive.app.navigation.models.*
import pt.it.automotive.app.navigation.routing.OsrmApiClient
import pt.it.automotive.app.preferences.AppearancePreferences
import pt.it.automotive.app.preferences.PreferencesRepository
import pt.it.automotive.app.preferences.PreferencesSectionType
import pt.it.automotive.app.preferences.PreferencesSectionUpdate
import pt.it.automotive.app.preferences.PreferencesSyncError
import pt.it.automotive.app.preferences.UserPreferences
import pt.it.automotive.app.preferences.WeatherField
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import java.util.Locale
import android.view.Gravity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), NavigationListener, MqttEventListener {

    companion object {
        private const val TAG = "MainActivity"

        // Car IDs configuration - delegate to AppConfig for centralized management
        val USER_CAR_IDS get() = AppConfig.USER_CAR_IDS
        val OTHER_CAR_IDS get() = AppConfig.OTHER_CAR_IDS
        const val ALPHA_LOCKED = 0.80f
        const val ALPHA_UNLOCKED = 1.0f
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

    // Note: both managers live in pt.it.automotive.app.notifications
    private lateinit var alertPreferenceManager: AlertPreferenceManager
    private lateinit var alertSettingsDialog: AlertSettingsDialog
    private lateinit var preferencesRepository: PreferencesRepository

    private var hasHandledSessionExpiry = false
    private var lastPreferencesErrorMessage: String? = null
    private var lastAppliedPreferences: UserPreferences? = null
    private var initialAppearanceAtLaunch: AppearancePreferences? = null
    private var hadPreferencesLoading = false
    private var initialAppearanceSyncHandled = false

    // Initial position from config
    private val initialPosition = AppConfig.DEFAULT_INITIAL_POSITION

    // Destination from config
    private val mercadoSantiago = AppConfig.Destinations.MERCADO_SANTIAGO

    private var currentLat: Double = AppConfig.DEFAULT_INITIAL_POSITION.latitude
    private var currentLon: Double = AppConfig.DEFAULT_INITIAL_POSITION.longitude
    private var currentSpeed: Double = 0.0
    private var currentBearing: Float = 0f
    
    // Track the current gear purely to enable/disable UI menus
    private var currentGearString: String = "P"

    // Last Navigation Dialog view reference
    private var navigationDialogView: View? = null

    // Pending route for navigation dialog
    private var pendingRoute: NavigationRoute? = null

    // Cached station assignment for Ditto weather source
    private var lastStationAssignment: MqttEventRouter.StationAssignmentData? = null
    private var hasRealStationAssignment = false // true when data came from station_assigner, not client-side fallback

    // Car API
    private var car: Car? = null
    private var carPropertyManager: CarPropertyManager? = null
    private val CAR_PERMISSION_REQUEST_CODE = 1001

    // Track state of Day/Night mode to prevent redundant map style updates
    private var isNightMode: Boolean = false

    private val carPropertyListener = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.propertyId == VehiclePropertyIds.GEAR_SELECTION) {
                val gearValue = value.value as? Int ?: return
                val gearLabel = when (gearValue) {
                    0x0001 -> "N"
                    0x0002 -> "R"
                    0x0004 -> "P"
                    0x0008 -> "D"
                    0x0010 -> "1"
                    0x0020 -> "2"
                    0x0040 -> "3"
                    0x0080 -> "4"
                    0x0100 -> "5"
                    0x0200 -> "6"
                    0x0400 -> "7"
                    0x0800 -> "8"
                    else -> "-"
                }
                currentGearString = gearLabel
                runOnUiThread {
                    // Automatically re-evaluate button states when gear changes
                    updateDrivingModeButtons()
                    if (isDrivingMode()) {
                        closeOpenMenus()
                    }
                }
            } else if (value.propertyId == VehiclePropertyIds.NIGHT_MODE) {
                val nightModeActive = value.value as? Boolean ?: return
                
                // Track physical sensor bounds to prevent recreating loops every time setupCarApi() binds 
                val appPrefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
                val hasStoredState = appPrefs.contains("lastCarNightMode")
                val lastCarNightMode = appPrefs.getBoolean("lastCarNightMode", false)
                
                // Overwrite ONLY when the car's actual physical environment changes (crossing boundaries)
                if (!hasStoredState || lastCarNightMode != nightModeActive) {
                    appPrefs.edit().putBoolean("lastCarNightMode", nightModeActive).apply()
                    runOnUiThread {
                        applyTheme(nightModeActive)
                    }
                }
            }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(TAG, "Car property error: propertyId=$propertyId, zone=$zone")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val appPrefs = getSharedPreferences("AppSettings", MODE_PRIVATE)

        // Apply explicit app light/dark preference before view inflation.
        val isLightMode = appPrefs.getBoolean("lightMode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isLightMode) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )

        // Apply colorblind theme before inflating any views
        if (appPrefs.getBoolean("colorBlindMode", false)) {
            setTheme(R.style.Theme_AutomotiveApp_ColorBlind)
        }

        initialAppearanceAtLaunch = AppearancePreferences(
            darkMode = !isLightMode,
            colorblindEnabled = appPrefs.getBoolean("colorBlindMode", false),
            language = appPrefs.getString("language", "en") ?: "en"
        )

        super.onCreate(savedInstanceState)

        // initialize MapLibre (only required once, before creating MapView)
        MapLibre.getInstance(this, BuildConfig.MAPTILER_API_KEY, WellKnownTileServer.MapTiler)

        setContentView(R.layout.activity_main)

        // Initialize OsrmApiClient with OpenRouteService API key
        OsrmApiClient.initialize(BuildConfig.OPENROUTESERVICE_API_KEY)
        
        // Initialize GeocodeApiClient with MapTiler API key for location search
        GeocodeApiClient.initialize(BuildConfig.MAPTILER_API_KEY)

        preferencesRepository = PreferencesRepository.create(this) {
            runOnUiThread { handleSessionExpired() }
        }

        // create controllers (after setContentView so views exist)
        mapController = MapController(this, findViewById(R.id.mapView))
        inAppNotificationManager = InAppNotificationManager(this)
        uiController = UiController(
            this,
            inAppNotificationManager = inAppNotificationManager,
            onWeatherSourceChanged = { onWeatherSourceChanged() },
            onWeatherFieldPreferenceChanged = ::onWeatherPreferenceFieldChanged,
            onWeatherDialogClosed = ::onWeatherDialogClosed
        )
        overtakingEdgeLightView = attachOvertakingEdgeLight()
        alertPreferenceManager = AlertPreferenceManager(this)
        alertNotificationManager = AlertNotificationManager(this, alertPreferenceManager, inAppNotificationManager)
        alertNotificationManager.requestNotificationPermission()
        alertSettingsDialog = AlertSettingsDialog(
            this,
            alertPreferenceManager,
            mapController,
            onPreferenceSectionChanged = ::onPreferenceSectionChanged,
            onDialogClosed = ::onSettingsDialogClosed,
            onLogout = { preferencesRepository.clearLocalData() }
        )

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
        val savedLat = try {
            appPrefs.getString("lastLat", AppConfig.DEFAULT_INITIAL_POSITION.latitude.toString())?.toDoubleOrNull() ?: AppConfig.DEFAULT_INITIAL_POSITION.latitude
        } catch (e: ClassCastException) {
            appPrefs.getFloat("lastLat", AppConfig.DEFAULT_INITIAL_POSITION.latitude.toFloat()).toDouble()
        }
        val savedLon = try {
            appPrefs.getString("lastLon", AppConfig.DEFAULT_INITIAL_POSITION.longitude.toString())?.toDoubleOrNull() ?: AppConfig.DEFAULT_INITIAL_POSITION.longitude
        } catch (e: ClassCastException) {
            appPrefs.getFloat("lastLon", AppConfig.DEFAULT_INITIAL_POSITION.longitude.toFloat()).toDouble()
        }
        currentLat = savedLat
        currentLon = savedLon

        observePreferencesState()
        preferencesRepository.loadPreferences()

        // wire map ready callback
        mapController.init {
            // set initial position from saved state, config if no saved state
            mapController.setSingleLocation(
                currentLat,
                currentLon,
                0f
            )
        }
        startTokenRefreshScheduler()
        if (checkSelfPermission("android.car.permission.CAR_EXTERIOR_ENVIRONMENT") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf("android.car.permission.CAR_EXTERIOR_ENVIRONMENT"), 
                CAR_PERMISSION_REQUEST_CODE
            )
        } else {
            setupCarApi()
        }
    }

    fun applyTheme(isNight: Boolean) {
        isNightMode = isNight

        val appPrefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
        appPrefs.edit().putBoolean("lightMode", !isNight).apply()

        // 1. Immediately switch map
        mapController.setMapStyle(!isNight)

        // 2. Set default night mode to update Configuration. Do NOT recreate.
        val mode = if (isNight) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.setDefaultNightMode(mode)

        // 3. Force the Activity's Theme to flush
        val styleRes = if (appPrefs.getBoolean("colorBlindMode", false)) {
            R.style.Theme_AutomotiveApp_ColorBlind
        } else {
            R.style.Theme_AutomotiveApp
        }
        theme.applyStyle(styleRes, true)

        // 4. Manually update key UI views that must reflect the change instantly
        uiController.refreshTheme()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAR_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, safe to connect to the sensor!
                setupCarApi()
            } else {
                Log.w(TAG, "Car exterior environment permission denied. Auto-night mode will not work.")
            }
        }
    }
    
    private fun setupCarApi() {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            car = Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER) { carObj, ready ->
                if (ready) {
                    carPropertyManager = carObj.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                    carPropertyManager?.registerCallback(
                        carPropertyListener,
                        VehiclePropertyIds.GEAR_SELECTION,
                        CarPropertyManager.SENSOR_RATE_ONCHANGE
                    )
                    carPropertyManager?.registerCallback(
                        carPropertyListener,
                        VehiclePropertyIds.NIGHT_MODE,
                        CarPropertyManager.SENSOR_RATE_ONCHANGE
                    )
                } else {
                    carPropertyManager?.unregisterCallback(carPropertyListener)
                    carPropertyManager = null
                }
            }
        }
    }

    private fun observePreferencesState() {
        lifecycleScope.launch {
            preferencesRepository.state.collect { state ->
                when (val error = state.error) {
                    is PreferencesSyncError.SessionExpired -> handleSessionExpired()
                    null -> lastPreferencesErrorMessage = null
                    else -> {
                        if (error.userMessage != lastPreferencesErrorMessage) {
                            lastPreferencesErrorMessage = error.userMessage
                            Toast.makeText(this@MainActivity, error.userMessage, Toast.LENGTH_LONG).show()
                            Log.w(TAG, "Preferences sync warning: ${error.userMessage}")
                        }
                    }
                }

                if (state.preferences != lastAppliedPreferences) {
                    lastAppliedPreferences = state.preferences
                    uiController.rebuildWeatherCardExtras()
                }

                val loadFinished = hadPreferencesLoading && !state.isLoading
                hadPreferencesLoading = state.isLoading

                // Only recreate the activity when the backend has confirmed data.
                // Without this guard, a 500/network error would trigger recreate() in a loop
                // because defaults != the appearance stored in AppSettings.
                if (loadFinished && !initialAppearanceSyncHandled && state.loadedFromBackend) {
                    initialAppearanceSyncHandled = true
                    val launchAppearance = initialAppearanceAtLaunch
                    if (launchAppearance != null && state.preferences.appearance != launchAppearance) {
                        recreate()
                        return@collect
                    }
                }
            }
        }
    }

    private fun onPreferenceSectionChanged(update: PreferencesSectionUpdate) {
        preferencesRepository.stagePreferencesUpdate(update)
    }

    private fun onSettingsDialogClosed(changedSections: Set<PreferencesSectionType>) {
        if (changedSections.isEmpty()) return

        lifecycleScope.launch {
            val success = preferencesRepository.flushDirtySectionsAwait()

            if (success && changedSections.contains(PreferencesSectionType.APPEARANCE)) {
                recreate()
            }
        }
    }

    private fun onWeatherDialogClosed(hasChanges: Boolean) {
        if (hasChanges) {
            preferencesRepository.flushDirtySections()
        }
    }

    private fun onWeatherPreferenceFieldChanged(field: WeatherField, enabled: Boolean) {
        onPreferenceSectionChanged(
            PreferencesSectionUpdate.WeatherUpdate(
                field = field,
                enabled = enabled
            )
        )
    }

    private fun handleSessionExpired() {
        if (hasHandledSessionExpiry) return
        hasHandledSessionExpiry = true

        preferencesRepository.clearLocalData()
        TokenStore.clear(this)
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun setupNavigation() {
        navigationManager = NavigationManager()
        navigationManager.setNavigationListener(this)
    }

    private fun setupNavigationButton() {
        // Start Route button (top right panel)
        findViewById<TextView>(R.id.btnStartRoute)?.apply {
            applyPressAnimation(this@MainActivity) {
                if (isDrivingMode()) {
                    inAppNotificationManager.showOrUpdate(
                        tag = "driving_mode",
                        type = InAppNotificationManager.Type.ERROR,
                        title = getString(R.string.notification_title_driving_mode),
                        message = getString(R.string.notification_message_driving_mode),
                        duration = 3_000L
                    )
                } else {
                    showNavigationDialog()
                }
            }
        }

        // Stop navigation button (below nav panel when active)
        findViewById<ImageView>(R.id.btnStopNavigation)?.setOnClickListener {
            stopNavigation()
        }
    }

    @SuppressLint("SetTextI18n", "DefaultLocale")
    private fun showNavigationDialog() {
        if (navigationDialogView != null) return // Already showing

        // Inflate overlay layout
        val overlayView = layoutInflater.inflate(R.layout.dialog_navigation, null)
        
        configureNavigationModalBounds(overlayView)

        // Add overlay to root layout
        val rootView = findViewById<ViewGroup>(android.R.id.content)
        rootView.addView(overlayView)
        navigationDialogView = overlayView

        val searchEdit = overlayView.findViewById<EditText>(R.id.edtSearchDestination)
        val searchResultsContainer = overlayView.findViewById<FrameLayout>(R.id.searchResultsContainer)
        val rvSearchResults = overlayView.findViewById<RecyclerView>(R.id.rvSearchResults)
        val txtSearchEmpty = overlayView.findViewById<TextView>(R.id.txtSearchEmpty)
        val routeInfoPreview = overlayView.findViewById<LinearLayout>(R.id.routeInfoPreview)
        val txtRouteInfo = overlayView.findViewById<TextView>(R.id.txtRouteInfo)
        val txtRouteDestination = overlayView.findViewById<TextView>(R.id.txtRouteDestination)
        val btnStartNavigation = overlayView.findViewById<Button>(R.id.btnStartNavigation)

        // Setup RecyclerView for search results
        val resultsAdapter = GeoCodeResultAdapter { selectedResult ->
            onDestinationSelected(selectedResult, overlayView, routeInfoPreview, 
                txtRouteInfo, txtRouteDestination, btnStartNavigation, searchEdit, rootView)
        }
        rvSearchResults?.apply {
            adapter = resultsAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        // Setup search input with debouncing
        val searchHandler = Handler(Looper.getMainLooper())
        var searchRunnable: Runnable? = null

        searchEdit?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Cancel previous search
                searchRunnable?.let { searchHandler.removeCallbacks(it) }

                val query = s.toString().trim()
                
                if (query.isEmpty()) {
                    // Hide results if search is empty
                    searchResultsContainer?.visibility = View.GONE
                    rvSearchResults?.visibility = View.VISIBLE
                    txtSearchEmpty?.visibility = View.GONE
                    return
                }

                // Debounce search - wait 500ms before searching
                searchRunnable = Runnable {
                    lifecycleScope.launch {
                        val proximity = LatLng(currentLat, currentLon)
                        val results = GeocodeApiClient.searchLocations(query, proximity, limit = 8)
                        
                        runOnUiThread {
                            if (results.isEmpty()) {
                                rvSearchResults?.visibility = View.GONE
                                txtSearchEmpty?.visibility = View.VISIBLE
                                txtSearchEmpty?.text = getString(R.string.no_results)
                            } else {
                                rvSearchResults?.visibility = View.VISIBLE
                                txtSearchEmpty?.visibility = View.GONE
                                resultsAdapter.updateResults(results)
                            }
                            searchResultsContainer?.visibility = View.VISIBLE
                        }
                    }
                }
                searchHandler.postDelayed(searchRunnable!!, 500)
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // Close button (X)
        overlayView.findViewById<ImageButton>(R.id.btnClose)?.setOnClickListener {
            closeNavigationDialog()
        }

        // Start navigation button
        btnStartNavigation?.setOnClickListener {
            pendingRoute?.let { route ->
                closeNavigationDialog()
                startNavigation(route)
            }
        }

        // Cancel button
        overlayView.findViewById<Button>(R.id.btnCancel)?.setOnClickListener {
            closeNavigationDialog()
        }

        // Close on background click
        overlayView.setOnClickListener {
            closeNavigationDialog()
        }

        // Prevent clicks on card from closing overlay
        overlayView.findViewById<View>(R.id.dialogCard)?.setOnClickListener {
            // Do nothing - prevent propagation
        }
        
        // Auto-focus search field
        searchEdit?.requestFocus()
    }

    private fun closeNavigationDialog() {
        navigationDialogView?.let { view ->
            val rootView = findViewById<ViewGroup>(android.R.id.content)
            rootView.removeView(view)
            pendingRoute = null
            navigationDialogView = null
        }
    }

    private fun closeOpenMenus() {
        alertSettingsDialog.dismiss()
        closeNavigationDialog()
    }

    private fun onDestinationSelected(
        destination: GeoCodeResult,
        overlayView: View,
        routeInfoPreview: LinearLayout?,
        txtRouteInfo: TextView?,
        txtRouteDestination: TextView?,
        btnStartNavigation: Button?,
        searchEdit: EditText?,
        rootView: ViewGroup
    ) {
        // Hide search results and show route info
        overlayView.findViewById<FrameLayout>(R.id.searchResultsContainer)?.visibility = View.GONE
        routeInfoPreview?.visibility = View.VISIBLE
        
        txtRouteInfo?.text = getString(R.string.calculating_route)
        txtRouteDestination?.text = destination.name
        btnStartNavigation?.isEnabled = false
        btnStartNavigation?.text = getString(R.string.calculating)
        
        // Clear search input
        searchEdit?.text?.clear()

        // Calculate route to selected destination
        val destinationLatLng = LatLng(destination.latitude, destination.longitude)
        calculateRouteForDialog(destinationLatLng) { route ->
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
                        closeNavigationDialog()
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

    private fun calculateRouteForDialog(destination: LatLng, callback: (NavigationRoute?) -> Unit) {
        lifecycleScope.launch {
            val origin = LatLng(currentLat, currentLon)
            val route = pt.it.automotive.app.navigation.routing.OsrmApiClient.calculateRoute(origin, destination)
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
                title = getString(R.string.navigation_arrival_title),
                message = getString(R.string.navigation_arrival_message),
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

    fun isDrivingMode(): Boolean {
        // App blocks menus if speed >= 5 km/h OR if the car is actively in a driving gear
        val gearDriving = currentGearString == "D" || currentGearString == "R" || currentGearString.matches(Regex("[1-8]"))
        return currentSpeed >= 5.0 || gearDriving
    }

    private fun setupSettingsButton() {
        val settingsButton = findViewById<View>(R.id.btnSettings)
        settingsButton?.apply {
            isClickable = true
            isFocusable = true
            applyPressAnimation(this@MainActivity) {
                Log.d("SETTINGS", "Settings button clicked")
                if (isDrivingMode()) {
                    inAppNotificationManager.showOrUpdate(
                        tag = "driving_mode",
                        type = InAppNotificationManager.Type.ERROR,
                        title = getString(R.string.notification_title_driving_mode),
                        message = getString(R.string.notification_message_driving_mode),
                        duration = 3_000L
                    )
                } else {
                    showSettingsDialog()
                }
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

    /**
     * Update button visual states based on driving mode.
     * Disables buttons visually (reduced opacity) when driving but keeps them clickable
     * so users get the warning notification on tap attempts.
     */
    private fun updateDrivingModeButtons() {
        val isDriving = isDrivingMode()
        
        // Settings button
        val btnSettings = findViewById<View>(R.id.btnSettings)
        val imgLockSettings = findViewById<ImageView>(R.id.imgLockSettings)
        btnSettings?.apply {
            alpha = if (isDriving) ALPHA_LOCKED else ALPHA_UNLOCKED
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.weather_bg)
        }
        imgLockSettings?.visibility = if (isDriving) View.VISIBLE else View.GONE

        // Navigation button
        val navPanelBox = findViewById<View>(R.id.navPanelBox)
        val imgLockNav = findViewById<ImageView>(R.id.imgLockNav)
        navPanelBox?.apply {
            alpha = if (isDriving) ALPHA_LOCKED else ALPHA_UNLOCKED
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.panel_top_box)
        }
        findViewById<TextView>(R.id.btnStartRoute)?.alpha = 1.0f
        imgLockNav?.visibility = if (isDriving) View.VISIBLE else View.GONE
        
        // Weather card
        uiController.updateWeatherCardDriving(isDriving)
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
        val token = pt.it.automotive.app.auth.TokenStore.getAccessToken(this)
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
                // SUMO-generated vehicles: any sumo-N other than the user car is shown on the map.
                normalizedCarId.startsWith(AppConfig.SUMO_CAR_PREFIX) -> handleOtherCarUpdate(data.copy(carId = normalizedCarId))
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
            Log.d(
                TAG,
                "Meteo stations update: ${stations.length()} stations, finding nearest to ($currentLat, $currentLon)"
            )

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
                Log.d(
                    TAG,
                    "Nearest station from meteo/updates: ${nearestData.stationName} (${minDist.toInt()}m away), temp=${nearestData.temperature}°C"
                )
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
        Log.d(
            TAG,
            "Station assignment received: car=${data.carId}, station=${data.stationId} (${data.stationName}), temp=${data.temperature}°C, wind=${data.windIntensity}km/h"
        )
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
                title = getString(R.string.notification_title_mqtt),
                message = getString(R.string.notification_message_mqtt),
                duration = InAppNotificationManager.SHORT_DURATION_MS
            )
        }
    }

    override fun onMqttError(error: String) {
        runOnUiThread {
            inAppNotificationManager.show(
                type = InAppNotificationManager.Type.ERROR,
                title = getString(R.string.notification_title_mqtt_error),
                message = getString(R.string.notification_message_mqtt_error, error),
                duration = InAppNotificationManager.LONG_DURATION_MS
            )
        }
    }

    // ========== Car Update Handlers ==========

    private fun handleUserCarUpdate(data: MqttEventRouter.CarUpdateData) {
        currentLat = data.latitude
        currentLon = data.longitude
        currentBearing = data.headingDeg
        currentSpeed = data.speedKmh

        getSharedPreferences("AppSettings", MODE_PRIVATE).edit().apply {
            putString("lastLat", currentLat.toString())
            putString("lastLon", currentLon.toString())
            apply()
        }
        
        if (isDrivingMode()) {
            closeOpenMenus()
        }

        // Update button states based on driving mode
        updateDrivingModeButtons()

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
                        title = getString(R.string.notification_title_accident_alert),
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

                val localizedDirection = when (direction.lowercase()) {
                    "ahead" -> getString(R.string.ahead)
                    "behind" -> getString(R.string.behind)
                    "nearby" -> getString(R.string.nearby)
                    else -> direction
                }

                val evTag = "ev_$evId"
                val shown = inAppNotificationManager.showOrUpdate(
                    tag = evTag,
                    type = InAppNotificationManager.Type.EMERGENCY,
                    title = getString(R.string.notification_title_ev, localizedDirection),
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
                "unsafe" -> getString(R.string.notification_title_highway_unsafe)
                "safe" -> getString(R.string.notification_title_highway_safe)
                else -> getString(R.string.notification_title_highway)
            }
            val messageText = getString(R.string.highway_entry_message, status)
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
            val notificationType = json.optString("notification_type", "")
            val alertType = json.optString("alert_type", "")
            if (notificationType == "traffic_jam_clear" || alertType == "traffic_jam_cleared") {
                val jamId = json.optString("jam_id", "")
                if (jamId.isNotBlank()) {
                    runOnUiThread { mapController.removeAccidentMarker("jam-$jamId") }
                }
                return
            }

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
                    title = getString(R.string.notification_title_traffic_jam),
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
        preferencesRepository.retryPendingUpdate()
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
        carPropertyManager?.unregisterCallback(carPropertyListener)
        car?.disconnect()
        vehicleTracker.destroy()
        mqttEventRouter.disconnect()
        navigationManager.destroy()
        alertNotificationManager.shutdown()
        inAppNotificationManager.destroy()
        preferencesRepository.clear()
        uiController.cleanup()
        mapController.onDestroy()
        super.onDestroy()
    }

    private fun startTokenRefreshScheduler() {
        lifecycleScope.launch {
            while (isActive) {
                delay(60_000L) // check every minute
                if (!TokenStore.isAccessTokenValid(this@MainActivity)) {
                    val refreshToken = TokenStore.getRefreshToken(this@MainActivity) ?: continue
                    val newTokens = KeycloakClient.refreshToken(refreshToken)
                    if (newTokens != null) {
                        TokenStore.save(
                            this@MainActivity,
                            newTokens.accessToken,
                            newTokens.refreshToken,
                            newTokens.expiresIn
                        )
                    }
                }
            }
        }
    }
    private fun configureNavigationModalBounds(overlayView: View) {
        val card = overlayView.findViewById<View>(R.id.dialogCard) ?: return
        val metrics = resources.displayMetrics
        val density = metrics.density

        val targetWidthPx = (600 * density).toInt()
        val sideMarginPx = (24 * density).toInt()
        val verticalMarginPx = (36 * density).toInt()

        val maxWidth = (metrics.widthPixels - (sideMarginPx * 2)).coerceAtLeast(sideMarginPx)
        
        val maxHeightByRatio = (metrics.heightPixels * 0.48f).toInt()
        val maxHeightByMargins = (metrics.heightPixels - (verticalMarginPx * 2)).coerceAtLeast(verticalMarginPx)
        val modalHeight = minOf(maxHeightByRatio, maxHeightByMargins)

        (card.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.width = minOf(targetWidthPx, maxWidth)
            lp.height = modalHeight
            lp.gravity = Gravity.CENTER
            card.layoutParams = lp
        }
    }
}
