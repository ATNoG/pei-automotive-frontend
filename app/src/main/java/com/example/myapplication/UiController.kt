package com.example.myapplication

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
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
    private val speedAlertIcon: ImageView? = activity.findViewById(R.id.speedAlertIcon)
    private val speedAlertBlur: View? = activity.findViewById(R.id.speedAlertBlur)

    fun updateSpeedLimit(limit: Int) {
        txtSpeedLimit?.text = limit.toString()
        // Also update right panel speed limit if it exists
        activity.findViewById<TextView>(R.id.txtSpeedLimitRight)?.text = limit.toString()
    }

    fun updateCurrentSpeed(speedKmh: Int) {
        txtCurrentSpeed?.text = speedKmh.toString()
        // Also update right panel speed if it exists
        activity.findViewById<TextView>(R.id.txtCurrentSpeedRight)?.text = "$speedKmh Km/h"
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
        // Also update phone layout navigation panel if it exists
        activity.findViewById<TextView>(R.id.txtNavDistance)?.text = distanceText
        activity.findViewById<TextView>(R.id.txtNavTime)?.text = etaText
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

    fun showSpeedAlert() {
        Log.d("UI_ALERT", "showSpeedAlert called")
        Log.d("UI_ALERT", "speedAlertIcon: $speedAlertIcon")
        Log.d("UI_ALERT", "speedAlertBlur: $speedAlertBlur")
        
        // Show the red blur background
        speedAlertBlur?.visibility = View.VISIBLE
        Log.d("UI_ALERT", "Blur visibility set to VISIBLE")
        
        // Start heartbeat animation on icon
        try {
            val heartbeat = AnimationUtils.loadAnimation(activity, R.anim.heartbeat)
            Log.d("UI_ALERT", "Heartbeat animation loaded: $heartbeat")
            heartbeat.repeatMode = android.view.animation.Animation.RESTART
            heartbeat.repeatCount = android.view.animation.Animation.INFINITE
            speedAlertIcon?.startAnimation(heartbeat)
            Log.d("UI_ALERT", "Heartbeat animation started")
        } catch (e: Exception) {
            Log.e("UI_ALERT", "Error starting heartbeat: ${e.message}")
        }
        
        // Start pulse animation on blur
        try {
            val pulse = AnimationUtils.loadAnimation(activity, R.anim.red_pulse)
            Log.d("UI_ALERT", "Pulse animation loaded: $pulse")
            pulse.repeatMode = android.view.animation.Animation.RESTART
            pulse.repeatCount = android.view.animation.Animation.INFINITE
            speedAlertBlur?.startAnimation(pulse)
            Log.d("UI_ALERT", "Pulse animation started")
        } catch (e: Exception) {
            Log.e("UI_ALERT", "Error starting pulse: ${e.message}")
        }
    }

    fun hideSpeedAlert() {
        speedAlertIcon?.clearAnimation()
        speedAlertBlur?.clearAnimation()
        speedAlertBlur?.visibility = View.GONE
    }
}
