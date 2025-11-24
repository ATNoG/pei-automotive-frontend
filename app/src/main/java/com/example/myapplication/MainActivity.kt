package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import org.maplibre.android.maps.*
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mapView: MapView
    private lateinit var mapLibreMap: MapLibreMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(
            context = applicationContext,
            apiKey = "VpgyO2ogB4DeaiIKkKXE",
            wellKnownTileServer = WellKnownTileServer.MapTiler
        )


        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onMapReady(map: MapLibreMap) {
        mapLibreMap = map

        // Simple default style
        mapLibreMap.setStyle(
            Style.Builder().fromUri(
                "https://api.maptiler.com/maps/streets/style.json?key=VpgyO2ogB4DeaiIKkKXE"
            )
        ) {
            enableLocationUpdates()
        }
    }

    private fun enableLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                100
            )
            return
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000
        ).build()

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback,
            mainLooper
        )
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return

            val latLng = LatLng(loc.latitude, loc.longitude)

            val cameraPos = CameraPosition.Builder()
                .target(latLng)
                .zoom(17.5)
                .tilt(60.0)           // ← tilt forward like TomTom
                .bearing(loc.bearing.toDouble()) // ← rotate according to heading
                .build()

            mapLibreMap.animateCamera(
                CameraUpdateFactory.newCameraPosition(cameraPos),
                1000
            )
        }
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onDestroy() { super.onDestroy(); mapView.onDestroy() }
}
