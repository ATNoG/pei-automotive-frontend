package pt.it.automotive.app.preferences

import org.json.JSONObject

object PreferencesJsonMapper {

    fun parsePreferences(json: String): UserPreferences {
        val root = JSONObject(json)
        return fromDto(
            PreferencesDto(
                userId = root.optString("user_id", ""),
                appearance = parseAppearance(root.getJSONObject("appearance")),
                alerts = parseAlerts(root.getJSONObject("alerts")),
                weather = parseWeather(root.getJSONObject("weather")),
                updatedAt = root.optString("updated_at", null)
            )
        )
    }

    fun parsePreferencesOrNull(json: String): UserPreferences? {
        return try {
            parsePreferences(json)
        } catch (_: Exception) {
            null
        }
    }

    fun serializePreferences(preferences: UserPreferences): String {
        val dto = toDto(preferences)
        val root = JSONObject().apply {
            put("user_id", dto.userId)
            put("appearance", appearanceToJson(dto.appearance))
            put("alerts", alertsToJson(dto.alerts))
            put("weather", weatherToJson(dto.weather))
            put("updated_at", dto.updatedAt)
        }
        return root.toString()
    }

    fun serializePatchPayload(payload: PreferencesPatchPayloadDto): String {
        val root = JSONObject()
        payload.appearance?.let { root.put("appearance", appearanceToJson(it)) }
        payload.alerts?.let { root.put("alerts", alertsToJson(it)) }
        payload.weather?.let { root.put("weather", weatherToJson(it)) }
        return root.toString()
    }

    fun toDto(domain: UserPreferences): PreferencesDto {
        return PreferencesDto(
            userId = domain.userId,
            appearance = AppearanceDto(
                darkMode = domain.appearance.darkMode,
                colorblindEnabled = domain.appearance.colorblindEnabled,
                language = domain.appearance.language
            ),
            alerts = AlertsDto(
                accident = domain.alerts.accident.toDto(),
                speeding = domain.alerts.speeding.toDto(),
                weather = domain.alerts.weather.toDto(),
                overtaking = domain.alerts.overtaking.toDto(),
                emergencyVehicle = domain.alerts.emergencyVehicle.toDto(),
                navigation = domain.alerts.navigation.toDto(),
                traffic = domain.alerts.traffic.toDto(),
                maneuver = domain.alerts.maneuver.toDto()
            ),
            weather = WeatherDto(
                feelsLike = domain.weather.feelsLike,
                wind = domain.weather.wind,
                humidity = domain.weather.humidity,
                pressure = domain.weather.pressure,
                visibility = domain.weather.visibility,
                uvIndex = domain.weather.uvIndex
            ),
            updatedAt = domain.updatedAt
        )
    }

    fun fromDto(dto: PreferencesDto): UserPreferences {
        return UserPreferences(
            userId = dto.userId,
            appearance = AppearancePreferences(
                darkMode = dto.appearance.darkMode,
                colorblindEnabled = dto.appearance.colorblindEnabled,
                language = dto.appearance.language
            ),
            alerts = AlertPreferences(
                accident = dto.alerts.accident.toDomain(),
                speeding = dto.alerts.speeding.toDomain(),
                weather = dto.alerts.weather.toDomain(),
                overtaking = dto.alerts.overtaking.toDomain(),
                emergencyVehicle = dto.alerts.emergencyVehicle.toDomain(),
                navigation = dto.alerts.navigation.toDomain(),
                traffic = dto.alerts.traffic.toDomain(),
                maneuver = dto.alerts.maneuver.toDomain()
            ),
            weather = WeatherPreferences(
                feelsLike = dto.weather.feelsLike,
                wind = dto.weather.wind,
                humidity = dto.weather.humidity,
                pressure = dto.weather.pressure,
                visibility = dto.weather.visibility,
                uvIndex = dto.weather.uvIndex
            ),
            updatedAt = dto.updatedAt
        )
    }

    private fun parseAppearance(json: JSONObject): AppearanceDto {
        return AppearanceDto(
            darkMode = json.optBoolean("dark_mode", true),
            colorblindEnabled = json.optBoolean("colorblind_enabled", false),
            language = json.optString("language", "en")
        )
    }

    private fun parseAlerts(json: JSONObject): AlertsDto {
        return AlertsDto(
            accident = parseAlertChannel(json.getJSONObject("accident")),
            speeding = parseAlertChannel(json.getJSONObject("speeding")),
            weather = parseAlertChannel(json.getJSONObject("weather")),
            overtaking = parseAlertChannel(json.getJSONObject("overtaking")),
            emergencyVehicle = parseAlertChannel(json.getJSONObject("emergency_vehicle")),
            navigation = parseAlertChannel(json.getJSONObject("navigation")),
            traffic = parseAlertChannel(json.getJSONObject("traffic")),
            maneuver = parseAlertChannel(json.getJSONObject("maneuver"))
        )
    }

    private fun parseAlertChannel(json: JSONObject): AlertChannelDto {
        return AlertChannelDto(
            alert = json.optBoolean("alert", true),
            audio = json.optBoolean("audio", false)
        )
    }

    private fun parseWeather(json: JSONObject): WeatherDto {
        return WeatherDto(
            feelsLike = json.optBoolean("feels_like", false),
            wind = json.optBoolean("wind", false),
            humidity = json.optBoolean("humidity", false),
            pressure = json.optBoolean("pressure", false),
            visibility = json.optBoolean("visibility", false),
            uvIndex = json.optBoolean("uv_index", false)
        )
    }

    private fun appearanceToJson(dto: AppearanceDto): JSONObject {
        return JSONObject().apply {
            put("dark_mode", dto.darkMode)
            put("colorblind_enabled", dto.colorblindEnabled)
            put("language", dto.language)
        }
    }

    private fun alertsToJson(dto: AlertsDto): JSONObject {
        return JSONObject().apply {
            put("accident", alertChannelToJson(dto.accident))
            put("speeding", alertChannelToJson(dto.speeding))
            put("weather", alertChannelToJson(dto.weather))
            put("overtaking", alertChannelToJson(dto.overtaking))
            put("emergency_vehicle", alertChannelToJson(dto.emergencyVehicle))
            put("navigation", alertChannelToJson(dto.navigation))
            put("traffic", alertChannelToJson(dto.traffic))
            put("maneuver", alertChannelToJson(dto.maneuver))
        }
    }

    private fun weatherToJson(dto: WeatherDto): JSONObject {
        return JSONObject().apply {
            put("feels_like", dto.feelsLike)
            put("wind", dto.wind)
            put("humidity", dto.humidity)
            put("pressure", dto.pressure)
            put("visibility", dto.visibility)
            put("uv_index", dto.uvIndex)
        }
    }

    private fun alertChannelToJson(dto: AlertChannelDto): JSONObject {
        return JSONObject().apply {
            put("alert", dto.alert)
            put("audio", dto.audio)
        }
    }

    private fun AlertChannelPreference.toDto(): AlertChannelDto {
        return AlertChannelDto(alert = alert, audio = audio)
    }

    private fun AlertChannelDto.toDomain(): AlertChannelPreference {
        return AlertChannelPreference(alert = alert, audio = audio)
    }
}
