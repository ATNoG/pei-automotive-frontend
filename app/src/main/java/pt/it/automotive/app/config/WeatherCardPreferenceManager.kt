package pt.it.automotive.app.config

import android.content.Context

/**
 * Persists which weather detail fields the user wants shown in the weather card HUD.
 * Temperature is always shown and is not managed here.
 */
class WeatherCardPreferenceManager(context: Context) {

    enum class Field(
        val key: String,
        val label: String,
        val emoji: String,
        val unit: String,
        val defaultEnabled: Boolean
    ) {
        WIND(
            key = "weather_card_wind",
            label = "Wind",
            emoji = "💨",
            unit = "",
            defaultEnabled = true
        ),
        HUMIDITY(
            key = "weather_card_humidity",
            label = "Humidity",
            emoji = "💧",
            unit = "%",
            defaultEnabled = false
        ),
        FEELS_LIKE(
            key = "weather_card_feels_like",
            label = "Feels Like",
            emoji = "🌡️",
            unit = "°",
            defaultEnabled = false
        ),
        PRESSURE(
            key = "weather_card_pressure",
            label = "Pressure",
            emoji = "🔵",
            unit = "hPa",
            defaultEnabled = false
        ),
        VISIBILITY(
            key = "weather_card_visibility",
            label = "Visibility",
            emoji = "👁️",
            unit = "km",
            defaultEnabled = false
        ),
        UV_INDEX(
            key = "weather_card_uv",
            label = "UV Index",
            emoji = "☀️",
            unit = "",
            defaultEnabled = false
        )
    }

    private val prefs = context.getSharedPreferences("WeatherCardPrefs", Context.MODE_PRIVATE)

    fun isEnabled(field: Field): Boolean =
        prefs.getBoolean(field.key, field.defaultEnabled)

    fun setEnabled(field: Field, enabled: Boolean) {
        prefs.edit().putBoolean(field.key, enabled).apply()
    }

    fun enabledFields(): List<Field> = Field.entries.filter { isEnabled(it) }
}