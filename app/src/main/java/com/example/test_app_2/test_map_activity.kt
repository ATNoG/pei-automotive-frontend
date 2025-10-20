package com.example.test_app_2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.test_app_2.databinding.ActivityTestMapBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class TestMapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestMapBinding
    private lateinit var map: MapView
    private lateinit var locationOverlay: MyLocationNewOverlay
    private var isFollowing = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        binding = ActivityTestMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        map = binding.map
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        // Check permissions before starting location updates
        checkPermissions()

        // Setup location overlay (marker that follows your location)
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        locationOverlay.enableMyLocation()
        locationOverlay.enableFollowLocation() // keeps map centered
        locationOverlay.isDrawAccuracyEnabled = true

        // Track speed
        val updateThread = Thread {
            while (true) {
                val lastFix = locationOverlay.lastFix
                if (lastFix != null) {
                    val speedMps = lastFix.speed // meters per second
                    val speedKmh = speedMps * 3.6
                    runOnUiThread {
                        binding.speedText.text = "Speed: %.2f km/h".format(speedKmh)
                    }
                }
                Thread.sleep(1000) // update every second
            }
        }
        updateThread.start()

        map.overlays.add(locationOverlay)
        map.controller.setZoom(17.0) // nice zoom for location view

        // Handles follow toggle button
        binding.btnFollow.setOnClickListener {
            isFollowing = !isFollowing
            if (isFollowing) {
                locationOverlay.enableFollowLocation()
                binding.btnFollow.text = "Follow: ON"
                binding.btnFollow.setBackgroundColor(getColor(android.R.color.holo_blue_dark))
            } else {
                locationOverlay.disableFollowLocation()
                binding.btnFollow.text = "Follow: OFF"
                binding.btnFollow.setBackgroundColor(getColor(android.R.color.darker_gray))
            }
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }
}