package pt.it.automotive.app.preferences

import android.content.Context

interface PreferencesLocalStore {
    fun readSnapshot(): UserPreferences?
    fun readSnapshotForUser(currentUserId: String): UserPreferences?
    fun saveSnapshot(preferences: UserPreferences)
    fun clearSnapshot()
    fun readUiPreferences(): UserPreferences
    fun applyToUiPreferences(preferences: UserPreferences): LocalApplyResult
}

data class LocalApplyResult(
    val appearanceChanged: Boolean
)

class PreferencesLocalDataSource(context: Context) : PreferencesLocalStore {

    private companion object {
        const val APP_SETTINGS_PREFS = "AppSettings"
        const val ALERT_PREFS = "AlertPreferences"
        const val WEATHER_CARD_PREFS = "WeatherCardPrefs"
        const val SNAPSHOT_PREFS = "BackendPreferencesCache"
        const val WEATHER_SOURCE_PREFS = "WeatherSourcePrefs"

        const val SNAPSHOT_KEY = "snapshot_json"

        const val KEY_LIGHT_MODE = "lightMode"
        const val KEY_COLORBLIND = "colorBlindMode"

        const val ALERT_SUFFIX_ENABLED = "_enabled"
        const val ALERT_SUFFIX_AUDIO = "_audio"

        const val WEATHER_FEELS_LIKE = "weather_card_feels_like"
        const val WEATHER_WIND = "weather_card_wind"
        const val WEATHER_HUMIDITY = "weather_card_humidity"
        const val WEATHER_PRESSURE = "weather_card_pressure"
        const val WEATHER_VISIBILITY = "weather_card_visibility"
        const val WEATHER_UV = "weather_card_uv"
    }

    private val appSettingsPrefs = context.getSharedPreferences(APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
    private val alertPrefs = context.getSharedPreferences(ALERT_PREFS, Context.MODE_PRIVATE)
    private val weatherPrefs = context.getSharedPreferences(WEATHER_CARD_PREFS, Context.MODE_PRIVATE)
    private val snapshotPrefs = context.getSharedPreferences(SNAPSHOT_PREFS, Context.MODE_PRIVATE)
    private val weatherSourcePrefs = context.getSharedPreferences(WEATHER_SOURCE_PREFS, Context.MODE_PRIVATE)

    override fun readSnapshot(): UserPreferences? {
        val json = snapshotPrefs.getString(SNAPSHOT_KEY, null) ?: return null
        return PreferencesJsonMapper.parsePreferencesOrNull(json)
    }

    override fun saveSnapshot(preferences: UserPreferences) {
        snapshotPrefs.edit().putString(SNAPSHOT_KEY, PreferencesJsonMapper.serializePreferences(preferences)).apply()
    }

    override fun clearSnapshot() {
        snapshotPrefs.edit().clear().apply()
        appSettingsPrefs.edit().clear().apply()
        alertPrefs.edit().clear().apply()
        weatherPrefs.edit().clear().apply()
        weatherSourcePrefs.edit().clear().apply()
    }

    /**
     * Returns the cached snapshot only if it belongs to [currentUserId].
     * Discards silently (returns null) if the cached data belongs to a different user,
     * preventing preference bleed when a network/server error occurs on first load.
     */
    override fun readSnapshotForUser(currentUserId: String): UserPreferences? {
        val snapshot = readSnapshot() ?: return null
        return if (snapshot.userId == currentUserId) snapshot else null
    }

    override fun readUiPreferences(): UserPreferences {
        val fromSnapshot = readSnapshot()

        return UserPreferences(
            userId = fromSnapshot?.userId.orEmpty(),
            appearance = readAppearance(),
            alerts = readAlerts(),
            weather = readWeather(),
            updatedAt = fromSnapshot?.updatedAt
        )
    }

    override fun applyToUiPreferences(preferences: UserPreferences): LocalApplyResult {
        val previousAppearance = readAppearance()

        appSettingsPrefs.edit()
            .putBoolean(KEY_LIGHT_MODE, !preferences.appearance.darkMode)
            .putBoolean(KEY_COLORBLIND, preferences.appearance.colorblindEnabled)
            .apply()

        writeAlerts(preferences.alerts)
        writeWeather(preferences.weather)

        return LocalApplyResult(appearanceChanged = previousAppearance != preferences.appearance)
    }

    private fun readAppearance(): AppearancePreferences {
        val lightMode = appSettingsPrefs.getBoolean(KEY_LIGHT_MODE, false)
        return AppearancePreferences(
            darkMode = !lightMode,
            colorblindEnabled = appSettingsPrefs.getBoolean(KEY_COLORBLIND, false)
        )
    }

    private fun readAlerts(): AlertPreferences {
        return AlertPreferences(
            accident = readAlertChannel("accident", defaultAudio = true),
            speeding = readAlertChannel("speeding", defaultAudio = false),
            weather = readAlertChannel("weather", defaultAudio = false),
            overtaking = readAlertChannel("overtaking", defaultAudio = false),
            emergencyVehicle = readAlertChannel("emergency_vehicle", defaultAudio = false),
            navigation = readAlertChannel("navigation", defaultAudio = false),
            traffic = readAlertChannel("traffic_jam", defaultAudio = false),
            maneuver = readAlertChannel("highway_entry", defaultAudio = false)
        )
    }

    private fun writeAlerts(alerts: AlertPreferences) {
        alertPrefs.edit()
            .putBoolean("accident$ALERT_SUFFIX_ENABLED", alerts.accident.alert)
            .putBoolean("accident$ALERT_SUFFIX_AUDIO", alerts.accident.audio)
            .putBoolean("speeding$ALERT_SUFFIX_ENABLED", alerts.speeding.alert)
            .putBoolean("speeding$ALERT_SUFFIX_AUDIO", alerts.speeding.audio)
            .putBoolean("weather$ALERT_SUFFIX_ENABLED", alerts.weather.alert)
            .putBoolean("weather$ALERT_SUFFIX_AUDIO", alerts.weather.audio)
            .putBoolean("overtaking$ALERT_SUFFIX_ENABLED", alerts.overtaking.alert)
            .putBoolean("overtaking$ALERT_SUFFIX_AUDIO", alerts.overtaking.audio)
            .putBoolean("emergency_vehicle$ALERT_SUFFIX_ENABLED", alerts.emergencyVehicle.alert)
            .putBoolean("emergency_vehicle$ALERT_SUFFIX_AUDIO", alerts.emergencyVehicle.audio)
            .putBoolean("navigation$ALERT_SUFFIX_ENABLED", alerts.navigation.alert)
            .putBoolean("navigation$ALERT_SUFFIX_AUDIO", alerts.navigation.audio)
            .putBoolean("traffic_jam$ALERT_SUFFIX_ENABLED", alerts.traffic.alert)
            .putBoolean("traffic_jam$ALERT_SUFFIX_AUDIO", alerts.traffic.audio)
            .putBoolean("highway_entry$ALERT_SUFFIX_ENABLED", alerts.maneuver.alert)
            .putBoolean("highway_entry$ALERT_SUFFIX_AUDIO", alerts.maneuver.audio)
            .apply()
    }

    private fun readAlertChannel(baseKey: String, defaultAudio: Boolean): AlertChannelPreference {
        return AlertChannelPreference(
            alert = alertPrefs.getBoolean("$baseKey$ALERT_SUFFIX_ENABLED", true),
            audio = alertPrefs.getBoolean("$baseKey$ALERT_SUFFIX_AUDIO", defaultAudio)
        )
    }

    private fun readWeather(): WeatherPreferences {
        return WeatherPreferences(
            feelsLike = weatherPrefs.getBoolean(WEATHER_FEELS_LIKE, false),
            wind = weatherPrefs.getBoolean(WEATHER_WIND, true),
            humidity = weatherPrefs.getBoolean(WEATHER_HUMIDITY, false),
            pressure = weatherPrefs.getBoolean(WEATHER_PRESSURE, false),
            visibility = weatherPrefs.getBoolean(WEATHER_VISIBILITY, false),
            uvIndex = weatherPrefs.getBoolean(WEATHER_UV, false)
        )
    }

    private fun writeWeather(weather: WeatherPreferences) {
        weatherPrefs.edit()
            .putBoolean(WEATHER_FEELS_LIKE, weather.feelsLike)
            .putBoolean(WEATHER_WIND, weather.wind)
            .putBoolean(WEATHER_HUMIDITY, weather.humidity)
            .putBoolean(WEATHER_PRESSURE, weather.pressure)
            .putBoolean(WEATHER_VISIBILITY, weather.visibility)
            .putBoolean(WEATHER_UV, weather.uvIndex)
            .apply()
    }

}
