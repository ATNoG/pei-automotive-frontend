package pt.it.automotive.app.config

import android.content.Context

/**
 * Manages the user's preferred weather data source.
 *
 * Two sources are supported:
 * - [Source.OPEN_WEATHER_MAP]: fetched via HTTP from the OpenWeatherMap OneCall API.
 * - [Source.DITTO]: received over MQTT from the station_assigner backend service,
 *   which assigns the nearest IPMA weather station to the vehicle.
 */
class WeatherSourcePreferenceManager(context: Context) {

    enum class Source(val key: String, val label: String) {
        OPEN_WEATHER_MAP("openweathermap", "OpenWeatherMap"),
        DITTO("ditto", "Weather Station (Ditto)")
    }

    private val prefs = context.getSharedPreferences("WeatherSourcePrefs", Context.MODE_PRIVATE)

    fun getSource(): Source {
        val key = prefs.getString(PREF_KEY, Source.OPEN_WEATHER_MAP.key)
        return Source.entries.firstOrNull { it.key == key } ?: Source.OPEN_WEATHER_MAP
    }

    fun setSource(source: Source) {
        prefs.edit().putString(PREF_KEY, source.key).apply()
    }

    private companion object {
        const val PREF_KEY = "weather_data_source"
    }
}
