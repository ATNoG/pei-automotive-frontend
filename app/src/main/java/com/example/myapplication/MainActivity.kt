package com.example.myapplication

import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer

class MainActivity : AppCompatActivity() {

    private lateinit var mapController: MapController
    private lateinit var uiController: UiController
    private lateinit var mqttManager: MqttManager
    private lateinit var overtakingAnimation: OvertakingAnimationView
    
    private var currentLat: Double = 40.63807349301117
    private var currentLon: Double = -8.749651444529503
    
    // Track if we've seen both cars (to detect overtaking start)
    private var hasSeenUserCar = false
    private var hasSeenOtherCar = false
    private var overtakingAnimationStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // initialize MapLibre (only required once, before creating MapView)
        MapLibre.getInstance(this, BuildConfig.MAPTILER_API_KEY, WellKnownTileServer.MapTiler)

        setContentView(R.layout.activity_main)

        // create controllers (after setContentView so views exist)
        mapController = MapController(this, findViewById(R.id.mapView))
        uiController = UiController(this)
        overtakingAnimation = findViewById(R.id.overtakingAnimation)

        // Setup settings button click listener
        setupSettingsButton()
        
        // Setup weather updates
        setupWeatherUpdates()

        // Setup MQTT (after uiController is initialized)
        setupMqtt()

        // wire map ready callback
        mapController.init {
            // called when style & layers are ready
        }
    }
    
    private fun setupSettingsButton() {
        val settingsButton = findViewById<ImageView>(R.id.btnSettings)
        settingsButton?.apply {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Log.d("SETTINGS", "Settings button clicked")
                Toast.makeText(this@MainActivity, "Feature not done yet...", Toast.LENGTH_LONG).show()
            }
        }
        Log.d("SETTINGS", "Settings button setup complete: ${settingsButton != null}")
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
        val weatherData = OpenWeatherMapClient.getWeather(currentLat, currentLon, apiKey)
        if (weatherData != null) {
            runOnUiThread {
                uiController.updateTemperature(weatherData.temperature)
                uiController.updateWeatherIcon(weatherData.isRain)
                Log.d("WEATHER", "Updated: ${weatherData.temperature}°C, Condition: ${weatherData.weatherCondition}, IsRain/Clouds: ${weatherData.isRain}")
            }
        } else {
            Log.e("WEATHER", "Failed to fetch weather data - received null response")
        }
    }

    private fun setupMqtt() {
        // Create MQTT manager with your broker details
        mqttManager = MqttManager(this, BuildConfig.MQTT_BROKER_ADDRESS, BuildConfig.MQTT_BROKER_PORT.toInt())

        // Set callback for received messages
        mqttManager.setOnMessageReceived { topic, message ->
            Log.d("MQTT_MSG", "Received on topic: $topic")
            when {
                topic == "alerts/speed" -> {
                    Log.d("MQTT_MSG", "Speed alert received!")
                    runOnUiThread {
                        uiController.showSpeedAlert()
                    }
                }
                topic == "cars/updates" -> {
                    Log.d("MQTT_MSG", "Car update received: $message")
                    try {
                        val carData = org.json.JSONObject(message)
                        val carId = carData.optString("car_id", "Unknown")
                        val lat = carData.optDouble("latitude", 0.0)
                        val lon = carData.optDouble("longitude", 0.0)
                        val speedKmh = carData.optDouble("speed_kmh", 0.0)
                        val headingDeg = carData.optDouble("heading_deg", 0.0).toFloat()
                        
                        Log.d("MQTT_MSG", "Car: $carId, Speed: $speedKmh km/h, Heading: $headingDeg")
                        
                        runOnUiThread {
                            // Handle different cars based on car_id
                            when (carId) {
                                "overtaking-car-front" -> {
                                    // This is the user's car - update main position and UI
                                    Log.d("MQTT_MSG", "Updating user car (front)")
                                    mapController.updateUserCar(lat, lon, headingDeg)
                                    uiController.updateCurrentSpeed(speedKmh.toInt())
                                    
                                    // Update current location for weather
                                    currentLat = lat
                                    currentLon = lon
                                    
                                    // Mark that we've seen the user car
                                    if (!hasSeenUserCar) {
                                        hasSeenUserCar = true
                                        Log.d("ANIMATION", "User car detected")
                                    }
                                    
                                    // Trigger animation if both cars detected
                                    if (!overtakingAnimationStarted && hasSeenUserCar && hasSeenOtherCar) {
                                        Log.d("ANIMATION", "Triggering overtaking animation")
                                        overtakingAnimation.startOvertakingAnimation()
                                        overtakingAnimationStarted = true
                                    }
                                    
                                    // Check speed threshold for alerts
                                    if (speedKmh < 60) {
                                        uiController.hideSpeedAlert()
                                    } else {
                                        uiController.showSpeedAlert()
                                    }
                                }
                                "overtaking-car-behind" -> {
                                    // This is the other car - update secondary position only
                                    Log.d("MQTT_MSG", "Updating other car (behind)")
                                    mapController.updateOtherCar(lat, lon, headingDeg)
                                    
                                    // Mark that we've seen the other car
                                    if (!hasSeenOtherCar) {
                                        hasSeenOtherCar = true
                                        Log.d("ANIMATION", "Other car detected")
                                    }
                                    
                                    // Trigger animation if both cars detected
                                    if (!overtakingAnimationStarted && hasSeenUserCar && hasSeenOtherCar) {
                                        Log.d("ANIMATION", "Triggering overtaking animation")
                                        overtakingAnimation.startOvertakingAnimation()
                                        overtakingAnimationStarted = true
                                    }
                                }
                                "speed-car" -> {
                                    // This is a speed-measuring car - update its position
                                    Log.d("MQTT_MSG", "Updating speed measuring car")
                                    
                                    // Reset overtaking animation state (different test scenario)
                                    if (hasSeenUserCar || hasSeenOtherCar || overtakingAnimationStarted) {
                                        hasSeenUserCar = false
                                        hasSeenOtherCar = false
                                        overtakingAnimationStarted = false
                                        overtakingAnimation.stopAnimation()
                                        mapController.clearOtherCar()  // Clear other car from map
                                        Log.d("ANIMATION", "Reset overtaking state for speed test")
                                    }
                                    
                                    mapController.updateUserCar(lat, lon, headingDeg)
                                    uiController.updateCurrentSpeed(speedKmh.toInt())
                                    // Update current location for weather
                                    currentLat = lat
                                    currentLon = lon
                                    // Check speed threshold for alerts
                                    if (speedKmh < 60) {
                                        uiController.hideSpeedAlert()
                                    } else {
                                        uiController.showSpeedAlert()
                                    }
                                }
                                else -> {
                                    // Fallback for any other car_id (backward compatibility)
                                    Log.d("MQTT_MSG", "Unknown car_id, using single location mode")
                                    mapController.setSingleLocation(lat, lon, headingDeg)
                                    uiController.updateCurrentSpeed(speedKmh.toInt())
                                }
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
                    uiController.showConnectionStatus("✓ Connected to broker")
                }
                // Subscribe to topics after connection
                mqttManager.subscribe("alerts/#",
                    onSuccess = { 
                        runOnUiThread {
                            uiController.showConnectionStatus("✓ Subscribed to alerts/#")
                        }
                    },
                    onError = { error -> 
                        runOnUiThread {
                            uiController.showConnectionStatus("✗ Subscribe failed: $error")
                        }
                    }
                )
                
                // Also subscribe to car updates
                mqttManager.subscribe("cars/updates",
                    onSuccess = { 
                        Log.d("MQTT_SUBSCRIBE", "Successfully subscribed to cars/updates")
                        runOnUiThread {
                            uiController.showConnectionStatus("✓ Subscribed to cars/updates")
                        }
                    },
                    onError = { error -> 
                        runOnUiThread {
                            uiController.showConnectionStatus("✗ Subscribe failed: $error")
                        }
                    }
                )
            },
            onError = { error ->
                runOnUiThread {
                    uiController.showConnectionStatus("✗ Connection failed: $error")
                }
            }
        )
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
        mqttManager.disconnect()
        mapController.onDestroy()
        super.onDestroy()
    }
}
