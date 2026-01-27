package com.example.myapplication

import android.app.Activity
import android.util.Log
import android.view.LayoutInflater
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Queue
import java.util.LinkedList

class AlertNotificationManager(private val activity: Activity) {

    companion object {
        private const val TAG = "AlertNotificationManager"
        private const val BASE_DISPLAY_TIME_MS = 5000L
        private const val MS_PER_CHARACTER = 50L
        private const val MIN_DISPLAY_TIME_MS = 8000L
        private const val MAX_DISPLAY_TIME_MS = 30000L
    }

    private val alertQueue: Queue<OpenWeatherMapClient.WeatherAlert> = LinkedList()
    private val displayedAlerts = mutableSetOf<String>()
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var currentAlertView: android.view.View? = null
    private var isShowingAlert = false

    fun showWeatherAlerts(alerts: List<OpenWeatherMapClient.WeatherAlert>) {
        if (alerts.isEmpty()) {
            Log.d(TAG, "No alerts to display")
            return
        }

        Log.d(TAG, "Queuing ${alerts.size} weather alerts")

        // Filter out already displayed alerts and add new ones to queue
        alerts.forEach { alert ->
            val alertKey = "${alert.event}_${alert.start}"
            if (!displayedAlerts.contains(alertKey)) {
                alertQueue.add(alert)
                displayedAlerts.add(alertKey)
            }
        }

        // Start processing queue if not already showing
        if (!isShowingAlert && alertQueue.isNotEmpty()) {
            processNextAlert()
        }
    }

    private fun processNextAlert() {
        if (alertQueue.isEmpty()) {
            isShowingAlert = false
            Log.d(TAG, "Alert queue empty")
            return
        }

        isShowingAlert = true
        val alert = alertQueue.poll()
        if (alert != null) {
            showAlert(alert)
        }
    }

    private fun showAlert(alert: OpenWeatherMapClient.WeatherAlert) {
        activity.runOnUiThread {
            try {
                // Remove previous alert if exists
                currentAlertView?.let { view ->
                    val parent = view.parent as? android.view.ViewGroup
                    parent?.removeView(view)
                }

                // Inflate new alert view
                val popupView = LayoutInflater.from(activity)
                    .inflate(R.layout.popup_weather_alert, null)

                val rootView = activity.findViewById<android.view.ViewGroup>(android.R.id.content)

                // Set up views
                val txtEvent = popupView.findViewById<TextView>(R.id.txtAlertEvent)
                val txtDescription = popupView.findViewById<TextView>(R.id.txtAlertDescription)
                val imgIcon = popupView.findViewById<ImageView>(R.id.imgAlertIcon)

                txtEvent.text = alert.event
                txtDescription.text = alert.description
                imgIcon.setImageResource(getAlertIcon(alert.event))

                // Position below the weather card
                val weatherCard = activity.findViewById<android.view.View>(R.id.weatherCard)
                    ?: activity.findViewById<android.view.View>(R.id.weatherBadge)
                
                val layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    if (weatherCard != null) {
                        val location = IntArray(2)
                        weatherCard.getLocationInWindow(location)
                        topMargin = location[1] + weatherCard.height + 8
                        marginStart = location[0]
                    } else {
                        topMargin = 80
                        marginStart = 16
                    }
                    marginEnd = 16
                }

                rootView.addView(popupView, layoutParams)
                currentAlertView = popupView

                // Slide in animation
                val slideIn = AnimationUtils.loadAnimation(activity, R.anim.alert_slide_in)
                popupView.startAnimation(slideIn)

                // Calculate display time based on text length
                val displayTime = calculateDisplayTime(alert)

                Log.d(TAG, "Showing alert: ${alert.event} for ${displayTime}ms")

                // Auto dismiss after duration
                popupView.postDelayed({
                    dismissCurrentAlert()
                }, displayTime)

            } catch (e: Exception) {
                Log.e(TAG, "Error showing alert: ${e.message}", e)
                processNextAlert()
            }
        }
    }

    private fun dismissCurrentAlert() {
        currentAlertView?.let { view ->
            try {
                val slideOut = AnimationUtils.loadAnimation(activity, R.anim.alert_slide_out)
                slideOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                    override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                        val parent = view.parent as? android.view.ViewGroup
                        parent?.removeView(view)
                        currentAlertView = null
                        Log.d(TAG, "Alert dismissed")
                        processNextAlert()
                    }
                    override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                })
                view.startAnimation(slideOut)
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing alert: ${e.message}", e)
                val parent = view.parent as? android.view.ViewGroup
                parent?.removeView(view)
                currentAlertView = null
                processNextAlert()
            }
        }
    }

    private fun getAlertIcon(event: String): Int {
        return when {
            event.contains("Tornado", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Thunderstorm", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Rain", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Snow", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Wind", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Fog", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Heat", ignoreCase = true) -> R.drawable.ic_weather_sun
            event.contains("Cold", ignoreCase = true) -> R.drawable.ic_weather_sun
            event.contains("Flood", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Hurricane", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Hail", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Dust", ignoreCase = true) -> R.drawable.ic_weather_sun
            else -> R.drawable.ic_weather_rain
        }
    }

    fun isAlertActive(alert: OpenWeatherMapClient.WeatherAlert): Boolean {
        val currentTime = System.currentTimeMillis() / 1000
        return currentTime >= alert.start && currentTime <= alert.end
    }

    private fun calculateDisplayTime(alert: OpenWeatherMapClient.WeatherAlert): Long {
        val textLength = alert.event.length + alert.description.length
        return (BASE_DISPLAY_TIME_MS + (textLength * MS_PER_CHARACTER))
            .coerceIn(MIN_DISPLAY_TIME_MS, MAX_DISPLAY_TIME_MS)
    }

    fun getActiveAlerts(alerts: List<OpenWeatherMapClient.WeatherAlert>): List<OpenWeatherMapClient.WeatherAlert> {
        return alerts.filter { isAlertActive(it) }
    }

    fun clearDisplayedAlerts() {
        displayedAlerts.clear()
        Log.d(TAG, "Cleared all displayed alerts")
    }

    fun getQueueSize(): Int {
        return alertQueue.size
    }
}
