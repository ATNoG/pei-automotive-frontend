package com.example.myapplication

import android.app.Activity
import android.app.AlertDialog
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/**
 * UiController - small helper to update right-panel widgets.
 *
 * Exposes clear methods: updateSpeedLimit, updateCurrentSpeed, updateTemperature, updateWeatherIcon, updateEtaAndDistance
 */
class UiController(private val activity: Activity) {

    private val txtSpeedLimit: TextView? = activity.findViewById(R.id.txtSpeedLimit)
    private val txtCurrentSpeed: TextView? = activity.findViewById(R.id.txtCurrentSpeed)
    private val txtTemperature: TextView? = activity.findViewById(R.id.txtTemperature)
    private val txtEta: TextView? = activity.findViewById(R.id.txtEta)
    private val txtDistance: TextView? = activity.findViewById(R.id.txtDistance)
    private val weatherIcon: ImageView? = activity.findViewById(R.id.weatherIcon)

    fun updateSpeedLimit(limit: Int) {
        txtSpeedLimit?.text = limit.toString()
    }

    fun updateCurrentSpeed(speedKmh: Int) {
        txtCurrentSpeed?.text = "$speedKmh Km/h"
    }

    fun updateTemperature(tempC: Int) {
        txtTemperature?.text = "$tempC°"
    }

    fun updateWeatherIcon(isRain: Boolean) {
        weatherIcon?.setImageResource(if (isRain) R.drawable.ic_weather_rain else R.drawable.ic_weather_sun)
    }

    fun updateEtaAndDistance(etaText: String, distanceText: String) {
        txtEta?.text = etaText
        txtDistance?.text = distanceText
    }

    fun showPopup(title: String, message: String) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    fun showConnectionStatus(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
