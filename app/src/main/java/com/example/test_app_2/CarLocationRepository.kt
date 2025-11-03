package com.example.test_app_2

import android.annotation.SuppressLint
import android.content.Context
import com.example.test_app_2.OverpassApiClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.tasks.await

class CarLocationRepository(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun getSpeedData(): Pair<Double, String> {
        val location = fusedClient.lastLocation.await()
        val speedKmh = location.speed * 3.6
        val speedLimit = OverpassApiClient.getSpeedLimit(location.latitude, location.longitude)
        return Pair(speedKmh, speedLimit)
    }
}