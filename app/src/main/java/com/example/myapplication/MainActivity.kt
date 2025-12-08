package com.example.myapplication

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer

class MainActivity : AppCompatActivity() {

    private lateinit var mapController: MapController
    private lateinit var uiController: UiController
    private lateinit var mqttManager: MqttManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // initialize MapLibre (only required once, before creating MapView)
        MapLibre.getInstance(this, BuildConfig.MAPTILER_API_KEY, WellKnownTileServer.MapTiler)

        setContentView(R.layout.activity_main)

        // create controllers (after setContentView so views exist)
        mapController = MapController(this, findViewById(R.id.mapView))
        uiController = UiController(this)

        // Setup MQTT (after uiController is initialized)
        setupMqtt()

        // wire map ready callback
        mapController.init {
            // called when style & layers are ready
        }
    }

    private fun setupMqtt() {
        // Create MQTT manager with your broker details
        mqttManager = MqttManager(this, BuildConfig.MQTT_BROKER_ADDRESS, BuildConfig.MQTT_BROKER_PORT)

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
                            // Update map with car position and heading
                            mapController.setSingleLocation(lat, lon, headingDeg)
                            // Update speed on UI
                            uiController.updateCurrentSpeed(speedKmh.toInt())
                            // Hide alert if speed is back to normal
                            if (speedKmh < 60) { // Adjust threshold as needed
                                Log.d("MQTT_MSG", "Speed normal, hiding alert")
                                uiController.hideSpeedAlert()
                            } else {
                                Log.d("MQTT_MSG", "Speed exceeded, showing alert")
                                uiController.showSpeedAlert()
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
