package pt.it.automotive.app.config

import android.content.Context
import pt.it.automotive.app.R

/**
 * Persists which weather detail fields the user wants shown in the weather card HUD.
 * Temperature is always shown and is not managed here.
 */
class WeatherCardPreferenceManager(context: Context) {

    enum class Field(
        val key: String,
        val labelResId: Int,
        val emoji: String,
        val unit: String,
        val defaultEnabled: Boolean
    ) {
        WIND(
            key = "weather_card_wind",
            labelResId = R.string.weather_wind,
            emoji = "💨",
            unit = "",
            defaultEnabled = true
        ),
        HUMIDITY(
            key = "weather_card_humidity",
            labelResId = R.string.weather_humidity,
            emoji = "💧",
            unit = "%",
            defaultEnabled = false
        ),
        FEELS_LIKE(
            key = "weather_card_feels_like",
            labelResId = R.string.weather_feels_like,
            emoji = "🌡️",
            unit = "°",
            defaultEnabled = false
        ),
        PRESSURE(
            key = "weather_card_pressure",
            labelResId = R.string.weather_pressure,
            emoji = "🔵",
            unit = "hPa",
            defaultEnabled = false
        ),
        VISIBILITY(
            key = "weather_card_visibility",
            labelResId = R.string.weather_visibility,
            emoji = "👁️",
            unit = "km",
            defaultEnabled = false
        ),
        UV_INDEX(
            key = "weather_card_uv",
            labelResId = R.string.weather_uv_index,
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

    fun getLabel(context: Context, field: Field): String {
        return context.getString(field.labelResId)
    }
}