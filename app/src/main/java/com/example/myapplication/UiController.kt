package com.example.myapplication

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.myapplication.navigation.models.ManeuverType
import com.example.myapplication.navigation.models.NavigationState
import com.example.myapplication.navigation.models.NavigationStep
import java.text.SimpleDateFormat
import java.util.*

/**
 * UiController - manages persistent HUD widgets in the main activity.
 *
 * Responsibilities:
 *   - Speed limit / current speed display and speeding indicator
 *   - Weather card data (temperature, wind, icon) and weather detail dialog
 *   - Navigation banner (maneuver icon + instruction + distance)
 *   - Navigation panel state switching (start route ↔ nav info ↔ stop button)
 *
 * Transient notifications (accidents, connection status, weather alerts, etc.) are
 * handled exclusively by [InAppNotificationManager] and are NOT part of this class.
 */
class UiController(private val activity: Activity) {

    companion object {
        /**
         * Format a distance in meters to a human-readable string.
         * @param distanceMeters Distance in metres.
         * @param suffix         Optional suffix appended after the value (e.g. "ahead").
         */
        fun formatDistance(distanceMeters: Double, suffix: String = ""): String {
            val text = if (distanceMeters >= 1000) {
                String.format("%.1f km", distanceMeters / 1000)
            } else {
                String.format("%.0f m", distanceMeters)
            }
            return if (suffix.isNotEmpty()) "$text $suffix" else text
        }
    }

    // ── Speed widgets ────────────────────────────────────────────────────

    private val speedLimitContainer: View? = activity.findViewById(R.id.speedLimitContainer)
    private val txtSpeedLimit: TextView? = activity.findViewById(R.id.txtSpeedLimit)
    private val txtCurrentSpeed: TextView? = activity.findViewById(R.id.txtCurrentSpeed)

    @Suppress("unused")
    private val txtSpeedUnit: TextView? = activity.findViewById(R.id.txtSpeedUnit)
    private val speedAlertIcon: ImageView? = activity.findViewById(R.id.speedAlertIcon)
    private val speedAlertBlur: View? = activity.findViewById(R.id.speedAlertBlur)

    // ── Weather widgets ──────────────────────────────────────────────────

    private val txtTemperature: TextView? = activity.findViewById(R.id.txtTemperature)
    private val txtWindSpeed: TextView? = activity.findViewById(R.id.txtWindSpeed)
    private val weatherCard: View? =
        activity.findViewById(R.id.weatherCard) ?: activity.findViewById(R.id.weatherBadge)
    private val txtEta: TextView? = activity.findViewById(R.id.txtEta)
    private val txtDistance: TextView? = activity.findViewById(R.id.txtDistance)
    private val weatherIcon: ImageView? = activity.findViewById(R.id.weatherIcon)

    // ── Navigation banner ────────────────────────────────────────────────

    private val navigationBanner: LinearLayout? = activity.findViewById(R.id.navigationBanner)
    private val imgManeuverIcon: ImageView? = activity.findViewById(R.id.imgManeuverIcon)
    private val txtNextManeuverDistance: TextView? =
        activity.findViewById(R.id.txtNextManeuverDistance)
    private val txtManeuverInstruction: TextView? =
        activity.findViewById(R.id.txtManeuverInstruction)

    // ── Navigation panel (top-right) ─────────────────────────────────────

    private val startRouteContainer: LinearLayout? =
        activity.findViewById(R.id.startRouteContainer)
    private val navInfoContainer: LinearLayout? = activity.findViewById(R.id.navInfoContainer)
    private val stopNavContainer: View? = activity.findViewById(R.id.stopNavContainer)

    @Suppress("unused")
    private val btnStopNavigation: ImageView? = activity.findViewById(R.id.btnStopNavigation)
    private val txtNavArrival: TextView? = activity.findViewById(R.id.txtNavArrival)
    private val txtNavDistance: TextView? = activity.findViewById(R.id.txtNavDistance)

    // ── Misc ─────────────────────────────────────────────────────────────

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // Cached for the weather detail dialog
    private var cachedWeatherData: OpenWeatherMapClient.WeatherData? = null
    private var cachedAlerts: List<OpenWeatherMapClient.WeatherAlert> = emptyList()

    // ====================================================================
    //  Speed
    // ====================================================================

    fun updateSpeedLimit(limit: Int?) {
        val displayText = limit?.toString() ?: "--"
        txtSpeedLimit?.text = displayText
        activity.findViewById<TextView>(R.id.txtSpeedLimitRight)?.text = displayText
    }

    fun updateCurrentSpeed(speedKmh: Int, speedLimit: Int? = null) {
        txtCurrentSpeed?.text = speedKmh.toString()
        activity.findViewById<TextView>(R.id.txtCurrentSpeedRight)?.text = "$speedKmh Km/h"

        val isSpeeding = speedLimit != null && speedKmh > speedLimit
        val speedColor = if (isSpeeding) {
            android.graphics.Color.parseColor("#FF4444")
        } else {
            android.graphics.Color.WHITE
        }
        txtCurrentSpeed?.setTextColor(speedColor)
        activity.findViewById<TextView>(R.id.txtCurrentSpeedRight)?.setTextColor(speedColor)
    }

    fun showSpeedAlert() {
        Log.d("UI_ALERT", "showSpeedAlert called")

        speedAlertBlur?.visibility = View.VISIBLE

        try {
            val heartbeat = AnimationUtils.loadAnimation(activity, R.anim.heartbeat)
            heartbeat.repeatMode = android.view.animation.Animation.RESTART
            heartbeat.repeatCount = android.view.animation.Animation.INFINITE
            speedAlertIcon?.startAnimation(heartbeat)
        } catch (e: Exception) {
            Log.e("UI_ALERT", "Error starting heartbeat: ${e.message}")
        }

        try {
            val pulse = AnimationUtils.loadAnimation(activity, R.anim.red_pulse)
            pulse.repeatMode = android.view.animation.Animation.RESTART
            pulse.repeatCount = android.view.animation.Animation.INFINITE
            speedAlertBlur?.startAnimation(pulse)
        } catch (e: Exception) {
            Log.e("UI_ALERT", "Error starting pulse: ${e.message}")
        }
    }

    fun hideSpeedAlert() {
        speedAlertIcon?.clearAnimation()
        speedAlertBlur?.clearAnimation()
        speedAlertBlur?.visibility = View.GONE
    }

    // ====================================================================
    //  Weather
    // ====================================================================

    fun updateTemperature(tempC: Int) {
        txtTemperature?.text = "$tempC°"
    }

    fun updateWindSpeed(windKmh: Int) {
        txtWindSpeed?.text = "$windKmh"
    }

    fun updateWeatherIcon(isRain: Boolean) {
        weatherIcon?.setImageResource(
            if (isRain) R.drawable.ic_weather_rain else R.drawable.ic_weather_sun
        )
    }

    fun updateFullWeatherData(
        weatherData: OpenWeatherMapClient.WeatherData,
        alerts: List<OpenWeatherMapClient.WeatherAlert>
    ) {
        cachedWeatherData = weatherData
        cachedAlerts = alerts
        updateTemperature(weatherData.temperature)
        updateWindSpeed(weatherData.windSpeed)
        updateWeatherIcon(weatherData.isRain)
    }

    fun setupWeatherCardClick() {
        weatherCard?.setOnClickListener { showWeatherDialog() }
    }

    private fun showWeatherDialog() {
        val weatherData = cachedWeatherData ?: return

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_weather, null)
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        rootView.addView(dialogView)

        // Populate header
        dialogView.findViewById<TextView>(R.id.txtWeatherDialogTemp)?.text =
            "${weatherData.temperature}°"
        dialogView.findViewById<TextView>(R.id.txtWeatherDialogCondition)?.text =
            weatherData.weatherDescription.replaceFirstChar { it.uppercase() }
        dialogView.findViewById<ImageView>(R.id.imgWeatherDialogIcon)?.setImageResource(
            if (weatherData.isRain) R.drawable.ic_weather_rain else R.drawable.ic_weather_sun
        )

        // Populate detail rows
        dialogView.findViewById<TextView>(R.id.txtFeelsLike)?.text = "${weatherData.feelsLike}°"
        dialogView.findViewById<TextView>(R.id.txtWindSpeed)?.text =
            "${weatherData.windSpeed} km/h"
        dialogView.findViewById<TextView>(R.id.txtHumidity)?.text = "${weatherData.humidity}%"
        dialogView.findViewById<TextView>(R.id.txtPressure)?.text =
            "${weatherData.pressure} hPa"
        dialogView.findViewById<TextView>(R.id.txtVisibility)?.text =
            "${weatherData.visibility} km"
        dialogView.findViewById<TextView>(R.id.txtUvIndex)?.text =
            String.format("%.1f", weatherData.uvIndex)

        // Populate alerts
        val alertsSection = dialogView.findViewById<LinearLayout>(R.id.alertsSection)
        val alertsContainer = dialogView.findViewById<LinearLayout>(R.id.alertsContainer)
        if (cachedAlerts.isNotEmpty()) {
            alertsSection?.visibility = View.VISIBLE
            alertsContainer?.removeAllViews()

            cachedAlerts.forEach { alert ->
                val alertCard = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.card_background_with_stroke)
                    setPadding(32, 24, 32, 24)
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.bottomMargin = 16
                    layoutParams = params
                }

                val titleText = TextView(activity).apply {
                    text = "⚠️ ${alert.event}"
                    setTextColor(android.graphics.Color.parseColor("#FFD54F"))
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                alertCard.addView(titleText)

                val dateFormat =
                    java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                val startTime = dateFormat.format(java.util.Date(alert.start * 1000))
                val endTime = dateFormat.format(java.util.Date(alert.end * 1000))
                val timeText = TextView(activity).apply {
                    text = "🕐 $startTime – $endTime"
                    setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                    textSize = 13f
                    setPadding(0, 12, 0, 0)
                }
                alertCard.addView(timeText)

                if (alert.senderName.isNotEmpty()) {
                    val senderText = TextView(activity).apply {
                        text = "📢 ${alert.senderName}"
                        setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                        textSize = 13f
                        setPadding(0, 8, 0, 0)
                    }
                    alertCard.addView(senderText)
                }

                val descText = TextView(activity).apply {
                    text = alert.description
                    setTextColor(android.graphics.Color.parseColor("#DDDDDD"))
                    textSize = 14f
                    setPadding(0, 16, 0, 0)
                }
                alertCard.addView(descText)

                alertsContainer?.addView(alertCard)
            }
        } else {
            alertsSection?.visibility = View.GONE
        }

        // Close / dismiss
        dialogView.findViewById<ImageButton>(R.id.btnCloseWeatherDialog)?.setOnClickListener {
            rootView.removeView(dialogView)
        }
        dialogView.findViewById<View>(R.id.weatherDialogOverlay)?.setOnClickListener {
            rootView.removeView(dialogView)
        }
        dialogView.findViewById<View>(R.id.weatherDialogCard)?.setOnClickListener { /* consume */ }
    }

    // ====================================================================
    //  ETA / Distance (bottom dashboard)
    // ====================================================================

    fun updateEtaAndDistance(etaText: String, distanceText: String) {
        txtEta?.text = etaText
        txtDistance?.text = distanceText
        activity.findViewById<TextView>(R.id.txtNavDistance)?.text = distanceText
        activity.findViewById<TextView>(R.id.txtNavArrival)?.text = etaText
    }

    // ====================================================================
    //  Generic popup (AlertDialog — for exceptional cases only)
    // ====================================================================

    fun showPopup(title: String, message: String) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ====================================================================
    //  Navigation mode
    // ====================================================================

    fun showNavigationMode() {
        navigationBanner?.visibility = View.VISIBLE
        startRouteContainer?.visibility = View.GONE
        navInfoContainer?.visibility = View.VISIBLE
        stopNavContainer?.visibility = View.VISIBLE
    }

    fun hideNavigationMode() {
        navigationBanner?.visibility = View.GONE
        startRouteContainer?.visibility = View.VISIBLE
        navInfoContainer?.visibility = View.GONE
        stopNavContainer?.visibility = View.GONE
        txtNavArrival?.text = "--:--"
        txtNavDistance?.text = "--"
    }

    fun updateNavigationState(state: NavigationState) {
        val distanceKm = state.remainingDistance / 1000.0
        val distanceText = if (distanceKm >= 1.0) {
            String.format("%.1f", distanceKm)
        } else {
            String.format("%.0f m", state.remainingDistance)
        }
        txtNavDistance?.text = distanceText

        val arrivalTimeMillis =
            System.currentTimeMillis() + (state.remainingDuration * 1000).toLong()
        val arrivalTime = timeFormat.format(Date(arrivalTimeMillis))
        txtNavArrival?.text = arrivalTime

        txtNextManeuverDistance?.text = state.formatDistanceToNextStep()

        Log.d("UiController", "Navigation: $distanceText km, arrival: $arrivalTime")
    }

    fun updateNavigationStep(step: NavigationStep) {
        txtManeuverInstruction?.text = step.instruction
        imgManeuverIcon?.setImageResource(getManeuverIcon(step.maneuverType))
    }

    /** Show a loading indicator while calculating route. */
    fun showRouteCalculating() {
        android.widget.Toast.makeText(activity, "Calculating route...", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Show a navigation error. */
    fun showNavigationError(error: String) {
        android.widget.Toast.makeText(activity, "Error: $error", android.widget.Toast.LENGTH_LONG).show()
    }

    // ====================================================================
    //  Lifecycle
    // ====================================================================

    /** Release pending callbacks. Call from Activity.onDestroy. */
    fun cleanup() {
        // No pending runnables left after accident-banner removal.
        Log.d("UiController", "cleanup()")
    }

    // ====================================================================
    //  Maneuver icon mapping
    // ====================================================================

    private fun getManeuverIcon(type: ManeuverType): Int = when (type) {
        // Left
        ManeuverType.TURN_LEFT,
        ManeuverType.TURN_SHARP_LEFT,
        ManeuverType.END_OF_ROAD_LEFT,
        ManeuverType.NOTIFICATION_LEFT -> R.drawable.ic_turn_left

        ManeuverType.TURN_SLIGHT_LEFT,
        ManeuverType.CONTINUE_LEFT,
        ManeuverType.CONTINUE_SLIGHT_LEFT,
        ManeuverType.NEW_NAME_LEFT -> R.drawable.ic_turn_slight_left

        ManeuverType.FORK_LEFT,
        ManeuverType.FORK_SLIGHT_LEFT -> R.drawable.ic_fork_left

        ManeuverType.MERGE_LEFT,
        ManeuverType.MERGE_SLIGHT_LEFT -> R.drawable.ic_merge

        // Right
        ManeuverType.TURN_RIGHT,
        ManeuverType.TURN_SHARP_RIGHT,
        ManeuverType.END_OF_ROAD_RIGHT,
        ManeuverType.NOTIFICATION_RIGHT -> R.drawable.ic_turn_right

        ManeuverType.TURN_SLIGHT_RIGHT,
        ManeuverType.CONTINUE_RIGHT,
        ManeuverType.CONTINUE_SLIGHT_RIGHT,
        ManeuverType.NEW_NAME_RIGHT -> R.drawable.ic_turn_slight_right

        ManeuverType.FORK_RIGHT,
        ManeuverType.FORK_SLIGHT_RIGHT -> R.drawable.ic_fork_right

        ManeuverType.MERGE_RIGHT,
        ManeuverType.MERGE_SLIGHT_RIGHT -> R.drawable.ic_merge

        // U-turn
        ManeuverType.UTURN,
        ManeuverType.CONTINUE_UTURN -> R.drawable.ic_uturn

        // Roundabout
        ManeuverType.ROUNDABOUT,
        ManeuverType.ROUNDABOUT_LEFT,
        ManeuverType.ROUNDABOUT_RIGHT,
        ManeuverType.ROUNDABOUT_STRAIGHT,
        ManeuverType.ROUNDABOUT_SHARP_LEFT,
        ManeuverType.ROUNDABOUT_SHARP_RIGHT,
        ManeuverType.ROUNDABOUT_SLIGHT_LEFT,
        ManeuverType.ROUNDABOUT_SLIGHT_RIGHT,
        ManeuverType.ROUNDABOUT_EXIT -> R.drawable.ic_roundabout

        // Merge
        ManeuverType.MERGE -> R.drawable.ic_merge

        // Ramps
        ManeuverType.ON_RAMP,
        ManeuverType.OFF_RAMP -> R.drawable.ic_ramp

        // Straight / continue
        ManeuverType.STRAIGHT,
        ManeuverType.CONTINUE_STRAIGHT,
        ManeuverType.NEW_NAME_STRAIGHT,
        ManeuverType.NOTIFICATION_STRAIGHT -> R.drawable.ic_straight

        ManeuverType.CONTINUE -> R.drawable.ic_continue

        // Start / end
        ManeuverType.DEPART -> R.drawable.ic_straight
        ManeuverType.ARRIVE -> R.drawable.check_flag

        ManeuverType.UNKNOWN -> R.drawable.ic_straight
    }
}
