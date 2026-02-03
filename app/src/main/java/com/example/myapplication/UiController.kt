package com.example.myapplication

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.myapplication.navigation.models.ManeuverType
import com.example.myapplication.navigation.models.NavigationState
import com.example.myapplication.navigation.models.NavigationStep
import java.text.SimpleDateFormat
import java.util.*

/**
 * UiController - small helper to update right-panel widgets.
 *
 * Exposes clear methods: updateSpeedLimit, updateCurrentSpeed, updateTemperature, updateWeatherIcon, updateEtaAndDistance
 */
class UiController(private val activity: Activity) {

    private val speedLimitContainer: View? = activity.findViewById(R.id.speedLimitContainer)
    private val txtSpeedLimit: TextView? = activity.findViewById(R.id.txtSpeedLimit)
    private val txtCurrentSpeed: TextView? = activity.findViewById(R.id.txtCurrentSpeed)
    private val txtSpeedUnit: TextView? = activity.findViewById(R.id.txtSpeedUnit)
    private val txtTemperature: TextView? = activity.findViewById(R.id.txtTemperature)
    private val txtWindSpeed: TextView? = activity.findViewById(R.id.txtWindSpeed)
    private val weatherCard: View? = activity.findViewById(R.id.weatherCard) ?: activity.findViewById(R.id.weatherBadge)
    private val txtEta: TextView? = activity.findViewById(R.id.txtEta)
    private val txtDistance: TextView? = activity.findViewById(R.id.txtDistance)
    private val weatherIcon: ImageView? = activity.findViewById(R.id.weatherIcon)
    private val speedAlertIcon: ImageView? = activity.findViewById(R.id.speedAlertIcon)
    private val speedAlertBlur: View? = activity.findViewById(R.id.speedAlertBlur)
    
    // Navigation UI elements
    private val navigationBanner: LinearLayout? = activity.findViewById(R.id.navigationBanner)
    private val imgManeuverIcon: ImageView? = activity.findViewById(R.id.imgManeuverIcon)
    private val txtNextManeuverDistance: TextView? = activity.findViewById(R.id.txtNextManeuverDistance)
    private val txtManeuverInstruction: TextView? = activity.findViewById(R.id.txtManeuverInstruction)
    
    // Navigation panel (top right) elements
    private val startRouteContainer: LinearLayout? = activity.findViewById(R.id.startRouteContainer)
    private val navInfoContainer: LinearLayout? = activity.findViewById(R.id.navInfoContainer)
    private val stopNavContainer: View? = activity.findViewById(R.id.stopNavContainer)
    private val btnStopNavigation: ImageView? = activity.findViewById(R.id.btnStopNavigation)
    private val txtNavArrival: TextView? = activity.findViewById(R.id.txtNavArrival)
    private val txtNavDistance: TextView? = activity.findViewById(R.id.txtNavDistance)
    
    // Time formatter for arrival time
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    
    // Cached weather data for dialog
    private var cachedWeatherData: OpenWeatherMapClient.WeatherData? = null
    private var cachedAlerts: List<OpenWeatherMapClient.WeatherAlert> = emptyList()

    fun updateSpeedLimit(limit: Int?) {
        val displayText = limit?.toString() ?: "--"
        txtSpeedLimit?.text = displayText
        // Also update right panel speed limit if it exists
        activity.findViewById<TextView>(R.id.txtSpeedLimitRight)?.text = displayText
    }

    fun updateCurrentSpeed(speedKmh: Int, speedLimit: Int? = null) {
        txtCurrentSpeed?.text = speedKmh.toString()
        
        // Only check speeding if we have a valid speed limit
        val isSpeeding = speedLimit != null && speedKmh > speedLimit
        
        // Change current speed color to red if speeding, otherwise white
        val speedColor = if (isSpeeding) {
            android.graphics.Color.RED
        } else {
            android.graphics.Color.WHITE
        }
        txtCurrentSpeed?.setTextColor(speedColor)
        txtSpeedUnit?.setTextColor(speedColor)
        
        // Change speed limit sign appearance when speeding
        if (isSpeeding) {
            // Make speed limit text red
            txtSpeedLimit?.setTextColor(android.graphics.Color.RED)
            // Scale up the speed limit sign
            speedLimitContainer?.animate()
                ?.scaleX(1.2f)
                ?.scaleY(1.2f)
                ?.setDuration(300)
                ?.start()
        } else {
            // Reset speed limit text to black
            txtSpeedLimit?.setTextColor(android.graphics.Color.BLACK)
            // Scale back to normal
            speedLimitContainer?.animate()
                ?.scaleX(1.0f)
                ?.scaleY(1.0f)
                ?.setDuration(300)
                ?.start()
        }
        
        // Also update right panel speed if it exists
        activity.findViewById<TextView>(R.id.txtCurrentSpeedRight)?.text = "$speedKmh Km/h"
    }

    fun updateTemperature(tempC: Int) {
        txtTemperature?.text = "$tempC°"
    }

    fun updateWindSpeed(windKmh: Int) {
        txtWindSpeed?.text = "$windKmh"
    }
    

    fun updateWeatherIcon(isRain: Boolean) {
        weatherIcon?.setImageResource(if (isRain) R.drawable.ic_weather_rain else R.drawable.ic_weather_sun)
    }
    
    fun updateFullWeatherData(weatherData: OpenWeatherMapClient.WeatherData, alerts: List<OpenWeatherMapClient.WeatherAlert>) {
        cachedWeatherData = weatherData
        cachedAlerts = alerts
        
        updateTemperature(weatherData.temperature)
        updateWindSpeed(weatherData.windSpeed)
        updateWeatherIcon(weatherData.isRain)
    }
    
    fun setupWeatherCardClick() {
        weatherCard?.setOnClickListener {
            showWeatherDialog()
        }
    }
    
    private fun showWeatherDialog() {
        val weatherData = cachedWeatherData ?: return
        
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_weather, null)
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        rootView.addView(dialogView)
        
        // Populate weather data
        dialogView.findViewById<TextView>(R.id.txtWeatherDialogTemp)?.text = "${weatherData.temperature}°"
        dialogView.findViewById<TextView>(R.id.txtWeatherDialogCondition)?.text = weatherData.weatherDescription.replaceFirstChar { it.uppercase() }
        dialogView.findViewById<ImageView>(R.id.imgWeatherDialogIcon)?.setImageResource(
            if (weatherData.isRain) R.drawable.ic_weather_rain else R.drawable.ic_weather_sun
        )
        dialogView.findViewById<TextView>(R.id.txtFeelsLike)?.text = "${weatherData.feelsLike}°"
        dialogView.findViewById<TextView>(R.id.txtWindSpeed)?.text = "${weatherData.windSpeed} km/h"
        dialogView.findViewById<TextView>(R.id.txtHumidity)?.text = "${weatherData.humidity}%"
        dialogView.findViewById<TextView>(R.id.txtPressure)?.text = "${weatherData.pressure} hPa"
        dialogView.findViewById<TextView>(R.id.txtVisibility)?.text = "${weatherData.visibility} km"
        dialogView.findViewById<TextView>(R.id.txtUvIndex)?.text = String.format("%.1f", weatherData.uvIndex)
        
        // Populate alerts if any
        val alertsSection = dialogView.findViewById<LinearLayout>(R.id.alertsSection)
        val alertsContainer = dialogView.findViewById<LinearLayout>(R.id.alertsContainer)
        if (cachedAlerts.isNotEmpty()) {
            alertsSection?.visibility = View.VISIBLE
            alertsContainer?.removeAllViews()
            
            cachedAlerts.forEach { alert ->
                // Create alert card
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
                
                // Alert title with emoji
                val titleText = TextView(activity).apply {
                    text = "⚠️ ${alert.event}"
                    setTextColor(android.graphics.Color.parseColor("#FFD54F"))
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                alertCard.addView(titleText)
                
                // Time period
                val dateFormat = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                val startTime = dateFormat.format(java.util.Date(alert.start * 1000))
                val endTime = dateFormat.format(java.util.Date(alert.end * 1000))
                val timeText = TextView(activity).apply {
                    text = "🕐 $startTime - $endTime"
                    setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                    textSize = 13f
                    setPadding(0, 12, 0, 0)
                }
                alertCard.addView(timeText)
                
                // Sender/Source
                if (alert.senderName.isNotEmpty()) {
                    val senderText = TextView(activity).apply {
                        text = "📢 ${alert.senderName}"
                        setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                        textSize = 13f
                        setPadding(0, 8, 0, 0)
                    }
                    alertCard.addView(senderText)
                }
                
                // Description
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
        
        // Close button
        dialogView.findViewById<ImageButton>(R.id.btnCloseWeatherDialog)?.setOnClickListener {
            rootView.removeView(dialogView)
        }
        

        // Overlay click to close
        dialogView.findViewById<View>(R.id.weatherDialogOverlay)?.setOnClickListener {
            rootView.removeView(dialogView)
        }
        
        // Prevent clicks on card from closing
        dialogView.findViewById<View>(R.id.weatherDialogCard)?.setOnClickListener { }
    }


    fun updateEtaAndDistance(etaText: String, distanceText: String) {
        txtEta?.text = etaText
        txtDistance?.text = distanceText
        // Also update phone layout navigation panel if it exists
        activity.findViewById<TextView>(R.id.txtNavDistance)?.text = distanceText
        activity.findViewById<TextView>(R.id.txtNavArrival)?.text = etaText
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
    
    // ========== Navigation UI Methods ==========
    
    /**
     * Show navigation mode UI (banner, stop button, etc.)
     */
    fun showNavigationMode() {
        navigationBanner?.visibility = View.VISIBLE
        
        // Switch navigation panel from "Start Route" to navigation info
        startRouteContainer?.visibility = View.GONE
        navInfoContainer?.visibility = View.VISIBLE
        stopNavContainer?.visibility = View.VISIBLE
    }
    
    /**
     * Hide navigation mode UI and return to normal state.
     */
    fun hideNavigationMode() {
        navigationBanner?.visibility = View.GONE
        
        // Switch navigation panel back to "Start Route" button
        startRouteContainer?.visibility = View.VISIBLE
        navInfoContainer?.visibility = View.GONE
        stopNavContainer?.visibility = View.GONE
        
        // Reset navigation info display
        txtNavArrival?.text = "--:--"
        txtNavDistance?.text = "--"
    }
    
    /**
     * Update navigation state UI (distance, ETA, current instruction).
     */
    fun updateNavigationState(state: NavigationState) {
        // Update distance in km
        val distanceKm = state.remainingDistance / 1000.0
        val distanceText = if (distanceKm >= 1.0) {
            String.format("%.1f", distanceKm)
        } else {
            String.format("%.0f m", state.remainingDistance)
        }
        txtNavDistance?.text = distanceText
        
        // Calculate arrival time (current time + remaining duration)
        val arrivalTimeMillis = System.currentTimeMillis() + (state.remainingDuration * 1000).toLong()
        val arrivalTime = timeFormat.format(Date(arrivalTimeMillis))
        txtNavArrival?.text = arrivalTime
        
        // Update next maneuver distance
        txtNextManeuverDistance?.text = state.formatDistanceToNextStep()
        
        Log.d("UiController", "Navigation: $distanceText km, arrival: $arrivalTime")
    }
    
    /**
     * Update the current navigation instruction/step.
     */
    fun updateNavigationStep(step: NavigationStep) {
        txtManeuverInstruction?.text = step.instruction
        
        // Update maneuver icon based on type
        val iconRes = getManeuverIcon(step.maneuverType)
        imgManeuverIcon?.setImageResource(iconRes)
    }
    
    /**
     * Show a loading indicator while calculating route.
     */
    fun showRouteCalculating() {
        Toast.makeText(activity, "Calculating route...", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Show destination reached message.
     */
    fun showDestinationReached() {
        // Inflate popup layout
        val popupView = LayoutInflater.from(activity).inflate(R.layout.popup_arrival, null)
        
        // Add popup to root layout with specific positioning
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        
        // Get weather card position for alignment
        val weatherCard = activity.findViewById<View>(R.id.weatherCard)
        val weatherCardLocation = IntArray(2)
        weatherCard?.getLocationOnScreen(weatherCardLocation)
        
        val layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            // Position at same height as weather card, to its right
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            topMargin = weatherCardLocation[1] - activity.window.decorView.top
            leftMargin = (weatherCard?.width ?: 0) + 32 + 16  // weather card width + margin
        }
        rootView.addView(popupView, layoutParams)
        
        // Function to clean up and stop navigation
        val closeAndStopNavigation = {
            if (popupView.parent != null) {
                rootView.removeView(popupView)
            }
            // Call MainActivity to stop navigation
            if (activity is MainActivity) {
                activity.runOnUiThread {
                    (activity as MainActivity).stopNavigationAfterArrival()
                }
            }
        }
        
        // Close button stops navigation
        popupView.findViewById<ImageButton>(R.id.btnCloseArrival)?.setOnClickListener {
            closeAndStopNavigation()
        }
        
        // Auto-dismiss after 5 seconds and stop navigation
        popupView.postDelayed({
            closeAndStopNavigation()
        }, 5000)
    }
    
    /**
     * Show navigation error.
     */
    fun showNavigationError(error: String) {
        Toast.makeText(activity, "Error: $error", Toast.LENGTH_LONG).show()
    }
    
    /**
     * Get the drawable resource for a maneuver type.
     * Comprehensive mapping for all OSRM maneuver types.
     */
    private fun getManeuverIcon(type: ManeuverType): Int {
        return when (type) {
            // Left turns
            ManeuverType.TURN_LEFT -> R.drawable.ic_turn_left
            ManeuverType.TURN_SLIGHT_LEFT -> R.drawable.ic_turn_slight_left
            ManeuverType.TURN_SHARP_LEFT -> R.drawable.ic_turn_left
            ManeuverType.FORK_LEFT -> R.drawable.ic_fork_left
            ManeuverType.FORK_SLIGHT_LEFT -> R.drawable.ic_fork_left
            ManeuverType.END_OF_ROAD_LEFT -> R.drawable.ic_turn_left
            ManeuverType.CONTINUE_LEFT -> R.drawable.ic_turn_slight_left
            ManeuverType.CONTINUE_SLIGHT_LEFT -> R.drawable.ic_turn_slight_left
            ManeuverType.MERGE_LEFT -> R.drawable.ic_merge
            ManeuverType.MERGE_SLIGHT_LEFT -> R.drawable.ic_merge
            ManeuverType.NEW_NAME_LEFT -> R.drawable.ic_turn_slight_left
            ManeuverType.NOTIFICATION_LEFT -> R.drawable.ic_turn_left
            
            // Right turns
            ManeuverType.TURN_RIGHT -> R.drawable.ic_turn_right
            ManeuverType.TURN_SLIGHT_RIGHT -> R.drawable.ic_turn_slight_right
            ManeuverType.TURN_SHARP_RIGHT -> R.drawable.ic_turn_right
            ManeuverType.FORK_RIGHT -> R.drawable.ic_fork_right
            ManeuverType.FORK_SLIGHT_RIGHT -> R.drawable.ic_fork_right
            ManeuverType.END_OF_ROAD_RIGHT -> R.drawable.ic_turn_right
            ManeuverType.CONTINUE_RIGHT -> R.drawable.ic_turn_slight_right
            ManeuverType.CONTINUE_SLIGHT_RIGHT -> R.drawable.ic_turn_slight_right
            ManeuverType.MERGE_RIGHT -> R.drawable.ic_merge
            ManeuverType.MERGE_SLIGHT_RIGHT -> R.drawable.ic_merge
            ManeuverType.NEW_NAME_RIGHT -> R.drawable.ic_turn_slight_right
            ManeuverType.NOTIFICATION_RIGHT -> R.drawable.ic_turn_right
            
            // U-turn
            ManeuverType.UTURN -> R.drawable.ic_uturn
            ManeuverType.CONTINUE_UTURN -> R.drawable.ic_uturn
            
            // Roundabouts
            ManeuverType.ROUNDABOUT -> R.drawable.ic_roundabout
            ManeuverType.ROUNDABOUT_LEFT -> R.drawable.ic_roundabout
            ManeuverType.ROUNDABOUT_RIGHT -> R.drawable.ic_roundabout
            ManeuverType.ROUNDABOUT_STRAIGHT -> R.drawable.ic_roundabout
            ManeuverType.ROUNDABOUT_SHARP_LEFT -> R.drawable.ic_roundabout
            ManeuverType.ROUNDABOUT_SHARP_RIGHT -> R.drawable.ic_roundabout
            ManeuverType.ROUNDABOUT_SLIGHT_LEFT -> R.drawable.ic_roundabout
            ManeuverType.ROUNDABOUT_SLIGHT_RIGHT -> R.drawable.ic_roundabout
            ManeuverType.ROUNDABOUT_EXIT -> R.drawable.ic_roundabout
            
            // Merge
            ManeuverType.MERGE -> R.drawable.ic_merge
            
            // Ramps
            ManeuverType.ON_RAMP -> R.drawable.ic_ramp
            ManeuverType.OFF_RAMP -> R.drawable.ic_ramp
            
            // Straight/Continue
            ManeuverType.STRAIGHT -> R.drawable.ic_straight
            ManeuverType.CONTINUE -> R.drawable.ic_continue
            ManeuverType.CONTINUE_STRAIGHT -> R.drawable.ic_straight
            ManeuverType.NEW_NAME_STRAIGHT -> R.drawable.ic_straight
            ManeuverType.NOTIFICATION_STRAIGHT -> R.drawable.ic_straight
            
            // Start/End
            ManeuverType.DEPART -> R.drawable.ic_straight
            ManeuverType.ARRIVE -> R.drawable.check_flag
            
            // Unknown
            ManeuverType.UNKNOWN -> R.drawable.ic_straight
        }
    }
}
