package com.example.myapplication

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import com.example.myapplication.config.WeatherCardPreferenceManager
import com.example.myapplication.config.WeatherSourcePreferenceManager
import com.example.myapplication.mqtt.MqttEventRouter
import com.example.myapplication.navigation.models.ManeuverType
import com.example.myapplication.navigation.models.NavigationState
import com.example.myapplication.navigation.models.NavigationStep
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

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
class UiController(
    private val activity: Activity,
    private val onWeatherSourceChanged: (() -> Unit)? = null
) {

    companion object {
        private const val TAG = "UiController"

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

    private val txtSpeedUnit: TextView? = activity.findViewById(R.id.txtSpeedUnit)
    private val speedAlertIcon: ImageView? = activity.findViewById(R.id.speedAlertIcon)
    private val speedAlertBlur: View? = activity.findViewById(R.id.speedAlertBlur)

    // ── Weather widgets ──────────────────────────────────────────────────

    private val txtTemperature: TextView? = activity.findViewById(R.id.txtTemperature)
    private val weatherCard: View? = activity.findViewById(R.id.weatherBadge)
    private val weatherCardExtras: LinearLayout? = activity.findViewById(R.id.weatherCardExtras)
    private val txtWeatherEmoji: TextView? = activity.findViewById(R.id.txtWeatherEmoji)
    private val weatherCardPrefs = WeatherCardPreferenceManager(activity)

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
    private var cachedDittoData: MqttEventRouter.StationAssignmentData? = null
    private val weatherSourcePrefs = WeatherSourcePreferenceManager(activity)

    // ── Current speed tracking ───────────────────────────────────────────
    private var currentSpeed: Double = 0.0

    // ====================================================================
    //  Speed
    // ====================================================================

    fun updateSpeedLimit(limit: Int?) {
        val displayText = limit?.toString() ?: "--"
        txtSpeedLimit?.text = displayText
    }

    fun updateCurrentSpeed(speedKmh: Int, speedLimit: Int? = null) {
        currentSpeed = speedKmh.toDouble()
        txtCurrentSpeed?.text = speedKmh.toString()

        val isSpeeding = speedLimit != null && speedKmh > speedLimit
        val speedColor = if (isSpeeding) {
            resolveThemeColor(R.attr.colorSpeedWarning)
        } else {
            resolveThemeColor(R.attr.colorTextPrimary)
        }
        txtCurrentSpeed?.setTextColor(speedColor)
        txtSpeedUnit?.setTextColor(speedColor)

        // Speed limit sign: turn red and scale up when speeding, reset when not
        if (isSpeeding) {
            txtSpeedLimit?.setTextColor(resolveThemeColor(R.attr.colorStatusDanger))
            speedLimitContainer?.animate()
                ?.scaleX(1.2f)
                ?.scaleY(1.2f)
                ?.setDuration(300)
                ?.start()
        } else {
            txtSpeedLimit?.setTextColor(resolveThemeColor(R.attr.colorTextOnLight))
            speedLimitContainer?.animate()
                ?.scaleX(1.0f)
                ?.scaleY(1.0f)
                ?.setDuration(300)
                ?.start()
        }
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

    fun updateFullWeatherData(
        weatherData: OpenWeatherMapClient.WeatherData,
        alerts: List<OpenWeatherMapClient.WeatherAlert>
    ) {
        cachedWeatherData = weatherData
        cachedAlerts = alerts
        if (weatherSourcePrefs.getSource() == WeatherSourcePreferenceManager.Source.OPEN_WEATHER_MAP) {
            txtTemperature?.text = "${weatherData.temperature}°C"
            txtWeatherEmoji?.text = getWeatherEmoji(weatherData.weatherCondition, weatherData.weatherDescription)
            rebuildWeatherCardExtras(weatherData)
        }
    }

    fun updateDittoWeatherData(data: MqttEventRouter.StationAssignmentData) {
        Log.d(TAG, "Ditto weather received: station=${data.stationName}, temp=${data.temperature}, wind=${data.windIntensity}")
        cachedDittoData = data
        if (weatherSourcePrefs.getSource() == WeatherSourcePreferenceManager.Source.DITTO) {
            txtTemperature?.text = if (isValidIpma(data.temperature)) "${data.temperature.toInt()}°C" else "--°C"
            txtWeatherEmoji?.text = getDittoWeatherEmoji(data)
            rebuildWeatherCardExtrasFromDitto(data)
        }
    }

    /**
     * Show a placeholder on the weather card when Ditto is selected but no data yet.
     */
    fun showDittoWaitingState() {
        Log.d(TAG, "Ditto source selected, no data yet — showing waiting state")
        txtTemperature?.text = "--°C"
        txtWeatherEmoji?.text = "📡"
        weatherCardExtras?.removeAllViews()
        weatherCardExtras?.visibility = View.GONE
    }

    fun hasDittoData(): Boolean = cachedDittoData != null

    fun getWeatherSource(): WeatherSourcePreferenceManager.Source = weatherSourcePrefs.getSource()

    /**
     * Rebuild the dynamic extras section of the weather card based on saved preferences.
     * Called after data is updated and also after the user changes field selections.
     */
    fun rebuildWeatherCardExtras(weatherData: OpenWeatherMapClient.WeatherData? = cachedWeatherData) {
        val container = weatherCardExtras ?: return
        container.removeAllViews()

        val enabledFields = weatherCardPrefs.enabledFields()
        if (enabledFields.isEmpty() || weatherData == null) {
            container.visibility = View.GONE
            return
        }

        val density = activity.resources.displayMetrics.density
        val dividerMarginPx = (10 * density).toInt()

        enabledFields.forEach { field ->
            // Vertical divider
            val divider = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (1 * density).toInt(), (24 * density).toInt()
                ).also { it.marginStart = dividerMarginPx; it.marginEnd = dividerMarginPx }
                setBackgroundColor(ContextCompat.getColor(activity, R.color.card_stroke_light))
            }
            container.addView(divider)

            // Emoji label
            val emoji = TextView(activity).apply {
                text = field.emoji
                textSize = 20f
            }
            container.addView(emoji)

            // Value
            val value = getFieldValue(field, weatherData)
            val valueTv = TextView(activity).apply {
                text = if (field.unit.isNotEmpty()) "$value${field.unit}" else value
                textSize = 20f
                setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = (4 * density).toInt() }
            }
            container.addView(valueTv)
        }

        container.visibility = View.VISIBLE
    }

    private fun getFieldValue(
        field: WeatherCardPreferenceManager.Field,
        data: OpenWeatherMapClient.WeatherData
    ): String = when (field) {
        WeatherCardPreferenceManager.Field.WIND -> "${data.windSpeed} km/h ${windDegreesToDirection(data.windDeg)}"
        WeatherCardPreferenceManager.Field.HUMIDITY -> "${data.humidity}"
        WeatherCardPreferenceManager.Field.FEELS_LIKE -> "${data.feelsLike}"
        WeatherCardPreferenceManager.Field.PRESSURE -> "${data.pressure}"
        WeatherCardPreferenceManager.Field.VISIBILITY -> "${data.visibility}"
        WeatherCardPreferenceManager.Field.UV_INDEX -> String.format("%.1f", data.uvIndex)
    }

    // ── Ditto weather card extras ──────────────────────────────────────

    private fun rebuildWeatherCardExtrasFromDitto(data: MqttEventRouter.StationAssignmentData) {
        val container = weatherCardExtras ?: return
        container.removeAllViews()

        val enabledFields = weatherCardPrefs.enabledFields()
        if (enabledFields.isEmpty()) {
            container.visibility = View.GONE
            return
        }

        val density = activity.resources.displayMetrics.density
        val dividerMarginPx = (10 * density).toInt()

        enabledFields.forEach { field ->
            val value = getDittoFieldValue(field, data) ?: return@forEach

            val divider = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (1 * density).toInt(), (24 * density).toInt()
                ).also { it.marginStart = dividerMarginPx; it.marginEnd = dividerMarginPx }
                setBackgroundColor(ContextCompat.getColor(activity, R.color.card_stroke_light))
            }
            container.addView(divider)

            val emoji = TextView(activity).apply {
                text = field.emoji
                textSize = 20f
            }
            container.addView(emoji)

            val valueTv = TextView(activity).apply {
                text = if (field.unit.isNotEmpty()) "$value${field.unit}" else value
                textSize = 20f
                setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = (4 * density).toInt() }
            }
            container.addView(valueTv)
        }

        container.visibility = View.VISIBLE
    }

    /**
     * Map WeatherCardPreferenceManager fields to Ditto station data.
     * Returns null for fields not available from Ditto (e.g. UV Index, Visibility, Feels Like).
     */
    private fun getDittoFieldValue(
        field: WeatherCardPreferenceManager.Field,
        data: MqttEventRouter.StationAssignmentData
    ): String? = when (field) {
        WeatherCardPreferenceManager.Field.WIND -> {
            if (isValidIpma(data.windIntensity)) {
                val dir = windDirectionEnumToString(data.windDirection)
                if (dir.isNotEmpty()) "${data.windIntensity.toInt()} km/h $dir"
                else "${data.windIntensity.toInt()} km/h"
            } else null
        }
        WeatherCardPreferenceManager.Field.HUMIDITY ->
            if (data.humidity >= 0) "${data.humidity}" else null
        WeatherCardPreferenceManager.Field.PRESSURE ->
            if (isValidIpma(data.pressure)) "${data.pressure.toInt()}" else null
        WeatherCardPreferenceManager.Field.FEELS_LIKE -> null
        WeatherCardPreferenceManager.Field.VISIBILITY -> null
        WeatherCardPreferenceManager.Field.UV_INDEX -> null
    }

    /**
     * Convert the Ditto WindDirection enum (0-8) to a compass string.
     */
    private fun windDirectionEnumToString(direction: Int): String = when (direction) {
        1 -> "N"
        2 -> "NE"
        3 -> "E"
        4 -> "SE"
        5 -> "S"
        6 -> "SW"
        7 -> "W"
        8 -> "NW"
        else -> ""
    }

    /**
     * Infer a weather emoji from Ditto station data (no condition string available).
     * Uses precipitation, wind, and radiation as heuristics.
     */
    /**
     * Infer a weather emoji from Ditto station data.
     * Since IPMA doesn't provide a condition string, this uses available
     * measurements as heuristics. It cannot distinguish e.g. fog from overcast,
     * so the result is approximate — but there's no better alternative with this data.
     */
    private fun getDittoWeatherEmoji(data: MqttEventRouter.StationAssignmentData): String {
        val hasPrecip = isValidIpma(data.accumulatedPrecipitation) && data.accumulatedPrecipitation > 0
        val hasWind = isValidIpma(data.windIntensity)
        val hasRadiation = isValidIpma(data.radiation)
        val hasTemp = isValidIpma(data.temperature)

        return when {
            hasPrecip && data.accumulatedPrecipitation > 5.0 ->
                if (hasTemp && data.temperature <= 1.0) "❄️" else "🌧️"
            hasPrecip ->
                if (hasTemp && data.temperature <= 1.0) "🌨️" else "🌦️"
            hasWind && data.windIntensity > 50.0 -> "💨"
            hasRadiation && data.radiation > 600.0 -> "☀️"
            hasRadiation && data.radiation > 200.0 -> "🌤️"
            hasRadiation && data.radiation > 50.0 -> "⛅"
            hasRadiation -> "☁️"
            else -> "🌡️" // not enough data to determine conditions
        }
    }

    private fun windDegreesToDirection(deg: Int): String {
        val directions = arrayOf(
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
        )
        val index = ((deg + 11.25) / 22.5).toInt() % 16
        return directions[index]
    }

    /**
     * Returns an emoji that best represents the current weather condition.
     * Uses the OpenWeatherMap "main" condition group and the description for fine-grained mapping.
     */
    private fun getWeatherEmoji(condition: String, description: String = ""): String = when {
        condition.equals("Thunderstorm", ignoreCase = true) -> "⛈️"
        condition.equals("Drizzle", ignoreCase = true) -> "🌦️"
        condition.equals("Rain", ignoreCase = true) -> when {
            description.contains("heavy", ignoreCase = true) -> "🌧️"
            description.contains("light", ignoreCase = true) -> "🌦️"
            else -> "🌧️"
        }

        condition.equals("Snow", ignoreCase = true) -> when {
            description.contains("sleet", ignoreCase = true) -> "🌨️"
            else -> "❄️"
        }

        condition.equals("Clear", ignoreCase = true) -> "☀️"
        condition.equals("Clouds", ignoreCase = true) -> when {
            description.contains("few", ignoreCase = true) -> "🌤️"
            description.contains("scattered", ignoreCase = true) -> "⛅"
            else -> "☁️"
        }
        // Atmosphere group: Mist, Smoke, Haze, Dust, Fog, Sand, Ash, Squall, Tornado
        condition.equals("Mist", ignoreCase = true) -> "🌫️"
        condition.equals("Fog", ignoreCase = true) -> "🌫️"
        condition.equals("Haze", ignoreCase = true) -> "🌫️"
        condition.equals("Smoke", ignoreCase = true) -> "💨"
        condition.equals("Dust", ignoreCase = true) -> "💨"
        condition.equals("Sand", ignoreCase = true) -> "💨"
        condition.equals("Ash", ignoreCase = true) -> "🌋"
        condition.equals("Squall", ignoreCase = true) -> "💨"
        condition.equals("Tornado", ignoreCase = true) -> "🌪️"
        else -> "🌡️"
    }

    /**
     * Returns true if an IPMA value is valid (not the -99 sentinel for "no data").
     */
    private fun isValidIpma(value: Double): Boolean = value > -90

    private fun configureWeatherModalBounds(dialogView: View) {
        val card = dialogView.findViewById<View>(R.id.weatherDialogCard) ?: return
        val metrics = activity.resources.displayMetrics
        val density = metrics.density
        val sideMarginPx = activity.resources.getDimensionPixelSize(R.dimen.dialog_margin)
        val verticalMarginPx = (36 * density).toInt()
        val targetWidthPx = activity.resources.getDimensionPixelSize(R.dimen.dialog_width)

        val maxWidth = (metrics.widthPixels - (sideMarginPx * 2)).coerceAtLeast(sideMarginPx)
        val maxHeightByRatio = (metrics.heightPixels * 0.70f).toInt()
        val maxHeightByMargins = (metrics.heightPixels - (verticalMarginPx * 2)).coerceAtLeast(verticalMarginPx)
        val modalHeight = minOf(maxHeightByRatio, maxHeightByMargins)

        (card.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
            lp.width = minOf(targetWidthPx, maxWidth)
            lp.height = modalHeight
            lp.gravity = android.view.Gravity.CENTER
            lp.topMargin = verticalMarginPx
            lp.bottomMargin = verticalMarginPx
            card.layoutParams = lp
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showDittoWeatherDialog() {
        val data = cachedDittoData ?: run {
            Log.w(TAG, "showDittoWeatherDialog called but cachedDittoData is null")
            return
        }

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_weather, null)
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        rootView.addView(dialogView)
        configureWeatherModalBounds(dialogView)

        // ── Header ────────────────────────────────────────────────────────
        dialogView.findViewById<TextView>(R.id.txtWeatherDialogTemp)?.text =
            if (isValidIpma(data.temperature)) "${data.temperature.toInt()}°C" else "N/A"
        dialogView.findViewById<TextView>(R.id.txtWeatherDialogCondition)?.text =
            data.stationName.ifEmpty { "Station ${data.stationId}" }
        dialogView.findViewById<TextView>(R.id.txtWeatherDialogEmoji)?.text =
            getDittoWeatherEmoji(data)

        // ── Detail rows — fill what Ditto provides, "N/A" for unavailable ─
        val windText = buildString {
            if (isValidIpma(data.windIntensity)) {
                append("${data.windIntensity.toInt()} km/h")
                val dir = windDirectionEnumToString(data.windDirection)
                if (dir.isNotEmpty()) append("  $dir")
            } else {
                append("N/A")
            }
        }
        dialogView.findViewById<TextView>(R.id.txtFeelsLike)?.text = "N/A"
        dialogView.findViewById<TextView>(R.id.txtWindSpeed)?.text = windText
        dialogView.findViewById<TextView>(R.id.txtHumidity)?.text =
            if (data.humidity >= 0) "${data.humidity}%" else "N/A"
        dialogView.findViewById<TextView>(R.id.txtPressure)?.text =
            if (isValidIpma(data.pressure)) "${data.pressure.toInt()} hPa" else "N/A"
        dialogView.findViewById<TextView>(R.id.txtVisibility)?.text = "N/A"
        dialogView.findViewById<TextView>(R.id.txtUvIndex)?.text = "N/A"

        // ── Determine which fields are unavailable from Ditto ─────────────
        val unavailableFields = mutableSetOf(
            WeatherCardPreferenceManager.Field.FEELS_LIKE,
            WeatherCardPreferenceManager.Field.VISIBILITY,
            WeatherCardPreferenceManager.Field.UV_INDEX
        )
        if (!isValidIpma(data.windIntensity)) unavailableFields.add(WeatherCardPreferenceManager.Field.WIND)
        if (data.humidity < 0) unavailableFields.add(WeatherCardPreferenceManager.Field.HUMIDITY)
        if (!isValidIpma(data.pressure)) unavailableFields.add(WeatherCardPreferenceManager.Field.PRESSURE)

        // ── In-card toggles — disable unavailable fields ──────────────────
        val cardSwitches = listOf(
            R.id.switchCardFeelsLike to WeatherCardPreferenceManager.Field.FEELS_LIKE,
            R.id.switchCardWind to WeatherCardPreferenceManager.Field.WIND,
            R.id.switchCardHumidity to WeatherCardPreferenceManager.Field.HUMIDITY,
            R.id.switchCardPressure to WeatherCardPreferenceManager.Field.PRESSURE,
            R.id.switchCardVisibility to WeatherCardPreferenceManager.Field.VISIBILITY,
            R.id.switchCardUvIndex to WeatherCardPreferenceManager.Field.UV_INDEX
        )
        cardSwitches.forEach { (viewId, field) ->
            val isAvailable = field !in unavailableFields
            dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(viewId)?.apply {
                if (isAvailable) {
                    isChecked = weatherCardPrefs.isEnabled(field)
                    setOnCheckedChangeListener { _, checked ->
                        weatherCardPrefs.setEnabled(field, checked)
                        rebuildWeatherCardExtrasFromDitto(data)
                    }
                } else {
                    isChecked = false
                    isEnabled = false
                }
            }
            // Dim the parent card for unavailable fields
            if (!isAvailable) {
                val switchView = dialogView.findViewById<View>(viewId)
                (switchView?.parent?.parent as? View)?.alpha = 0.4f
            }
        }

        // ── Data source toggle ────────────────────────────────────────────
        setupWeatherSourceToggle(dialogView, rootView)

        // ── Alerts section — not available from Ditto ─────────────────────
        dialogView.findViewById<LinearLayout>(R.id.alertsSection)?.visibility = View.GONE

        // ── Dismiss ───────────────────────────────────────────────────────
        dialogView.findViewById<ImageButton>(R.id.btnCloseWeatherDialog)?.setOnClickListener {
            rootView.removeView(dialogView)
        }
        dialogView.findViewById<View>(R.id.weatherDialogOverlay)?.setOnClickListener {
            rootView.removeView(dialogView)
        }
        dialogView.findViewById<View>(R.id.weatherDialogCard)?.setOnClickListener { /* consume */ }
    }

    private fun setupWeatherSourceToggle(dialogView: View, rootView: ViewGroup) {
        val switchSource = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchWeatherSource)
        switchSource?.apply {
            isChecked = weatherSourcePrefs.getSource() == WeatherSourcePreferenceManager.Source.DITTO
            setOnCheckedChangeListener { _, isChecked ->
                val source = if (isChecked) WeatherSourcePreferenceManager.Source.DITTO
                             else WeatherSourcePreferenceManager.Source.OPEN_WEATHER_MAP
                weatherSourcePrefs.setSource(source)
                rootView.removeView(dialogView)
                onWeatherSourceChanged?.invoke()
            }
        }
    }

    fun setupWeatherCardClick() {
        weatherCard?.apply {
            applyPressAnimation(activity) {
                if (currentSpeed >= 20.0) {
                    android.widget.Toast.makeText(
                        activity,
                        "Cannot open weather while driving (speed >= 20 km/h)",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    showWeatherDialog()
                }
            }
        }
    }

    private fun showWeatherDialog() {
        val source = weatherSourcePrefs.getSource()
        Log.d(TAG, "showWeatherDialog: source=$source, hasDitto=${cachedDittoData != null}, hasOWM=${cachedWeatherData != null}")

        if (source == WeatherSourcePreferenceManager.Source.DITTO) {
            if (cachedDittoData != null) {
                showDittoWeatherDialog()
            } else {
                Log.w(TAG, "Ditto selected but no station data received yet")
                android.widget.Toast.makeText(
                    activity,
                    "Waiting for weather station data...",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        val weatherData = cachedWeatherData ?: return

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_weather, null)
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        rootView.addView(dialogView)
        configureWeatherModalBounds(dialogView)

        // ── Header ────────────────────────────────────────────────────────
        dialogView.findViewById<TextView>(R.id.txtWeatherDialogTemp)?.text =
            "${weatherData.temperature}°C"
        dialogView.findViewById<TextView>(R.id.txtWeatherDialogCondition)?.text =
            weatherData.weatherDescription.replaceFirstChar { it.uppercase() }
        dialogView.findViewById<TextView>(R.id.txtWeatherDialogEmoji)?.text =
            getWeatherEmoji(weatherData.weatherCondition, weatherData.weatherDescription)

        // ── Detail rows ───────────────────────────────────────────────────
        dialogView.findViewById<TextView>(R.id.txtFeelsLike)?.text = "${weatherData.feelsLike}°C"
        dialogView.findViewById<TextView>(R.id.txtWindSpeed)?.text =
            "${weatherData.windSpeed} km/h  ${windDegreesToDirection(weatherData.windDeg)}"
        dialogView.findViewById<TextView>(R.id.txtHumidity)?.text = "${weatherData.humidity}%"
        dialogView.findViewById<TextView>(R.id.txtPressure)?.text = "${weatherData.pressure} hPa"
        dialogView.findViewById<TextView>(R.id.txtVisibility)?.text = "${weatherData.visibility} km"
        dialogView.findViewById<TextView>(R.id.txtUvIndex)?.text =
            String.format("%.1f", weatherData.uvIndex)

        // ── In-card toggles ───────────────────────────────────────────────
        // Map each card's SwitchCompat to its WeatherCardPreferenceManager.Field.
        // Toggling saves the pref and immediately rebuilds the weather card HUD.
        val cardSwitches = listOf(
            R.id.switchCardFeelsLike to WeatherCardPreferenceManager.Field.FEELS_LIKE,
            R.id.switchCardWind to WeatherCardPreferenceManager.Field.WIND,
            R.id.switchCardHumidity to WeatherCardPreferenceManager.Field.HUMIDITY,
            R.id.switchCardPressure to WeatherCardPreferenceManager.Field.PRESSURE,
            R.id.switchCardVisibility to WeatherCardPreferenceManager.Field.VISIBILITY,
            R.id.switchCardUvIndex to WeatherCardPreferenceManager.Field.UV_INDEX
        )
        cardSwitches.forEach { (viewId, field) ->
            dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(viewId)?.apply {
                isChecked = weatherCardPrefs.isEnabled(field)
                setOnCheckedChangeListener { _, checked ->
                    weatherCardPrefs.setEnabled(field, checked)
                    rebuildWeatherCardExtras(weatherData)
                }
            }
        }

        // ── Data source toggle ────────────────────────────────────────────
        setupWeatherSourceToggle(dialogView, rootView)

        // ── Alerts ────────────────────────────────────────────────────────
        val alertsSection = dialogView.findViewById<LinearLayout>(R.id.alertsSection)
        val alertsContainer = dialogView.findViewById<LinearLayout>(R.id.alertsContainer)
        if (cachedAlerts.isNotEmpty()) {
            alertsSection?.visibility = View.VISIBLE
            alertsContainer?.removeAllViews()
            cachedAlerts.forEach { alert ->
                val alertCard = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = createWeatherAlertBackground(alert.event)
                    setPadding(32, 24, 32, 24)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = 16 }
                }
                alertCard.addView(TextView(activity).apply {
                    text = "⚠️ ${alert.event}"
                    setTextColor(ContextCompat.getColor(activity, R.color.status_warning))
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                val fmt = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                alertCard.addView(TextView(activity).apply {
                    text = "🕐 ${fmt.format(java.util.Date(alert.start * 1000))} – ${fmt.format(java.util.Date(alert.end * 1000))}"
                    setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                    textSize = 13f
                    setPadding(0, 12, 0, 0)
                })
                if (alert.senderName.isNotEmpty()) {
                    alertCard.addView(TextView(activity).apply {
                        text = "📢 ${alert.senderName}"
                        setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                        textSize = 13f
                        setPadding(0, 8, 0, 0)
                    })
                }
                alertCard.addView(TextView(activity).apply {
                    text = alert.description
                    setTextColor(ContextCompat.getColor(activity, R.color.text_description))
                    textSize = 14f
                    setPadding(0, 16, 0, 0)
                })
                alertsContainer?.addView(alertCard)
            }
        } else {
            alertsSection?.visibility = View.GONE
        }

        // ── Dismiss ───────────────────────────────────────────────────────
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
        android.widget.Toast.makeText(activity, activity.getString(R.string.calculating_route), android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Show a navigation error. */
    fun showNavigationError(error: String) {
        android.widget.Toast.makeText(activity, activity.getString(R.string.navigation_error, error), android.widget.Toast.LENGTH_LONG).show()
    }

    // ====================================================================
    //  Lifecycle
    // ====================================================================

    /** Release pending callbacks. Call from Activity.onDestroy. */
    fun cleanup() {
        // No pending runnables left after accident-banner removal.
        Log.d("UiController", "cleanup()")
    }

    private fun resolveThemeColor(@AttrRes attrRes: Int): Int {
        val typedValue = TypedValue()
        if (!activity.theme.resolveAttribute(attrRes, typedValue, true)) {
            Log.w("UiController", "Theme color attribute not found: $attrRes")
            return 0
        }
        return if (typedValue.resourceId != 0) {
            activity.getColor(typedValue.resourceId)
        } else {
            typedValue.data
        }
    }
    
    private fun createWeatherAlertBackground(title: String): GradientDrawable {
        val firstWord = title
            .trim()
            .replace(Regex("^[^A-Za-z]+"), "")
            .substringBefore(" ")
            .lowercase(Locale.getDefault())

        val strokeColor = when (firstWord) {
            "yellow" -> Color.parseColor("#FFD54F")
            "orange" -> Color.parseColor("#FB8C00")
            "red"    -> Color.parseColor("#E53935")
            else     -> Color.parseColor("#33FFFFFF")
        }

        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f * activity.resources.displayMetrics.density
            setColor(Color.parseColor("#F0121212"))
            setStroke((1f * activity.resources.displayMetrics.density).roundToInt(), strokeColor)
        }
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
