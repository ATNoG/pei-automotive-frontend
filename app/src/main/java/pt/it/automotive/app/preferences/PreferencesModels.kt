package pt.it.automotive.app.preferences

/** Domain model used by the app. */
data class UserPreferences(
    val userId: String,
    val appearance: AppearancePreferences,
    val alerts: AlertPreferences,
    val weather: WeatherPreferences,
    val updatedAt: String?
)

data class AppearancePreferences(
    val darkMode: Boolean,
    val colorblindEnabled: Boolean
)

data class AlertChannelPreference(
    val alert: Boolean,
    val audio: Boolean
)

data class AlertPreferences(
    val accident: AlertChannelPreference,
    val speeding: AlertChannelPreference,
    val weather: AlertChannelPreference,
    val overtaking: AlertChannelPreference,
    val emergencyVehicle: AlertChannelPreference,
    val navigation: AlertChannelPreference,
    val traffic: AlertChannelPreference,
    val maneuver: AlertChannelPreference
)

data class WeatherPreferences(
    val feelsLike: Boolean,
    val wind: Boolean,
    val humidity: Boolean,
    val pressure: Boolean,
    val visibility: Boolean,
    val uvIndex: Boolean
)

/** DTOs aligned with backend payload contract. */
data class PreferencesDto(
    val userId: String,
    val appearance: AppearanceDto,
    val alerts: AlertsDto,
    val weather: WeatherDto,
    val updatedAt: String?
)

data class AppearanceDto(
    val darkMode: Boolean,
    val colorblindEnabled: Boolean
)

data class AlertsDto(
    val accident: AlertChannelDto,
    val speeding: AlertChannelDto,
    val weather: AlertChannelDto,
    val overtaking: AlertChannelDto,
    val emergencyVehicle: AlertChannelDto,
    val navigation: AlertChannelDto,
    val traffic: AlertChannelDto,
    val maneuver: AlertChannelDto
)

data class AlertChannelDto(
    val alert: Boolean,
    val audio: Boolean
)

data class WeatherDto(
    val feelsLike: Boolean,
    val wind: Boolean,
    val humidity: Boolean,
    val pressure: Boolean,
    val visibility: Boolean,
    val uvIndex: Boolean
)

data class PreferencesPatchPayloadDto(
    val appearance: AppearanceDto? = null,
    val alerts: AlertsDto? = null,
    val weather: WeatherDto? = null
)

enum class PreferencesSectionType {
    APPEARANCE,
    ALERTS,
    WEATHER
}

enum class AlertCategory(val apiKey: String) {
    ACCIDENT("accident"),
    SPEEDING("speeding"),
    WEATHER("weather"),
    OVERTAKING("overtaking"),
    EMERGENCY_VEHICLE("emergency_vehicle"),
    NAVIGATION("navigation"),
    TRAFFIC("traffic"),
    MANEUVER("maneuver")
}

enum class WeatherField(val apiKey: String) {
    FEELS_LIKE("feels_like"),
    WIND("wind"),
    HUMIDITY("humidity"),
    PRESSURE("pressure"),
    VISIBILITY("visibility"),
    UV_INDEX("uv_index")
}

sealed interface PreferencesSectionUpdate {
    val sectionType: PreferencesSectionType

    data class AppearanceUpdate(
        val darkMode: Boolean? = null,
        val colorblindEnabled: Boolean? = null
    ) : PreferencesSectionUpdate {
        override val sectionType: PreferencesSectionType = PreferencesSectionType.APPEARANCE
    }

    data class AlertUpdate(
        val category: AlertCategory,
        val alert: Boolean? = null,
        val audio: Boolean? = null
    ) : PreferencesSectionUpdate {
        override val sectionType: PreferencesSectionType = PreferencesSectionType.ALERTS
    }

    data class WeatherUpdate(
        val field: WeatherField,
        val enabled: Boolean
    ) : PreferencesSectionUpdate {
        override val sectionType: PreferencesSectionType = PreferencesSectionType.WEATHER
    }
}

object PreferencesDefaults {
    fun create(): UserPreferences {
        return UserPreferences(
            userId = "",
            appearance = AppearancePreferences(
                darkMode = true,
                colorblindEnabled = false
            ),
            alerts = AlertPreferences(
                accident = AlertChannelPreference(alert = true, audio = true),
                speeding = AlertChannelPreference(alert = true, audio = false),
                weather = AlertChannelPreference(alert = true, audio = false),
                overtaking = AlertChannelPreference(alert = true, audio = false),
                emergencyVehicle = AlertChannelPreference(alert = true, audio = false),
                navigation = AlertChannelPreference(alert = true, audio = false),
                traffic = AlertChannelPreference(alert = true, audio = false),
                maneuver = AlertChannelPreference(alert = true, audio = false)
            ),
            weather = WeatherPreferences(
                feelsLike = false,
                wind = true,
                humidity = false,
                pressure = false,
                visibility = false,
                uvIndex = false
            ),
            updatedAt = null
        )
    }
}
