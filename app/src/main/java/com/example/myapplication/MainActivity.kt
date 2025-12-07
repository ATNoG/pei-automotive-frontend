package com.example.myapplication

import android.os.Bundle
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
        MapLibre.getInstance(this, "VpgyO2ogB4DeaiIKkKXE", WellKnownTileServer.MapTiler)

        setContentView(R.layout.activity_main)

        // create controllers
        mapController = MapController(this, findViewById(R.id.mapView))
        uiController = UiController(this)

        // Setup MQTT
        setupMqtt()

        // wire map ready callback
        mapController.init {
            // called when style & layers are ready
            // Example: center on a single location
            mapController.setSingleLocation(40.641528719599606, -8.654951875205159, 0f)

            // Example: optionally start a small route simulation
            mapController.simulateRoute(listOf(
                Pair(40.641528719599606, -8.654951875205159),
                Pair(40.64154907218252, -8.655010883800715),
                Pair(40.64156535424438, -8.655099396694052),
                Pair(40.64158163630226, -8.655147676454051),
                Pair(40.64159384784306, -8.655217413885165),
                Pair(40.64160605938165, -8.65525496480961),
                Pair(40.64162234142961, -8.655319337822945),
                Pair(40.641652870258824, -8.65540516850739),
                Pair(40.641654905513604, -8.65542930838739),
                Pair(40.64168136382016, -8.655493681400726),
                Pair(40.64170375160981, -8.655576829876281),
                Pair(40.641754632922016, -8.655700211485172),
                Pair(40.64178312643992, -8.655794088796286),
                Pair(40.64182586669396, -8.655941610285177),
                Pair(40.64185838028008, -8.656057192974982),
                Pair(40.64190011527218, -8.656170989465613),
                Pair(40.64196055900682, -8.656225991105142),
                Pair(40.642059859308105, -8.656225042801807),
                Pair(40.642145487710735, -8.656214611457555),
                Pair(40.64224478773659, -8.656211766545637),
            ))
        }
    }

    private fun setupMqtt() {
        // Create MQTT manager with your broker details
        mqttManager = MqttManager(this, "192.168.1.201", 1884)

        // Set callback for received messages
        mqttManager.setOnMessageReceived { topic, message ->
            runOnUiThread {
                uiController.showPopup("Topic: $topic", message)
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
