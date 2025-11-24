package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

import com.google.android.gms.location.*

import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mapView: MapView
    private lateinit var mapLibreMap: MapLibreMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val handler = Handler(Looper.getMainLooper())

    // Mock location starting parameters
    private var mockLat = 37.7749        // San Francisco latitude
    private var mockLon = -122.4194      // San Francisco longitude
    private var mockBearing = 90f        // moving east
    private var mockSpeed = 0.0003       // ~30m per update

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // required before inflating MapView
        MapLibre.getInstance(
            this,
            "VpgyO2ogB4DeaiIKkKXE",
            WellKnownTileServer.MapTiler
        )

        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Enable mock mode for emulator
        fusedLocationClient.setMockMode(true)
    }

    override fun onMapReady(map: MapLibreMap) {
        mapLibreMap = map

        mapLibreMap.setStyle(
            Style.Builder().fromUri(
                "https://api.maptiler.com/maps/streets/style.json?key=VpgyO2ogB4DeaiIKkKXE"
            )
        ) {
            // Start mock driving simulation
            startMockLocationFeed()
        }
    }

    // -------------------------
    // MOCK LOCATION FEED
    // -------------------------

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startMockLocationFeed() {
        val mockLocation = Location("mock").apply {
            latitude = mockLat
            longitude = mockLon
            bearing = mockBearing
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = System.nanoTime()
            accuracy = 1f
        }

        fusedLocationClient.setMockLocation(mockLocation)
        updateCamera(mockLocation)
    }


    private fun updateCamera(loc: Location) {
        val pos = CameraPosition.Builder()
            .target(LatLng(loc.latitude, loc.longitude))
            .zoom(17.5)
            .tilt(60.0)
            .bearing(loc.bearing.toDouble())
            .build()

        mapLibreMap.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 800)
    }

    // Required lifecycle methods
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onDestroy() { super.onDestroy(); mapView.onDestroy() }
}
