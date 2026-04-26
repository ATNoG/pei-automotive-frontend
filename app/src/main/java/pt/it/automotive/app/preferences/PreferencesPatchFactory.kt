package pt.it.automotive.app.preferences

object PreferencesPatchFactory {

    fun merge(base: UserPreferences, update: PreferencesSectionUpdate): UserPreferences {
        return when (update) {
            is PreferencesSectionUpdate.AppearanceUpdate -> {
                base.copy(
                    appearance = base.appearance.copy(
                        darkMode = update.darkMode ?: base.appearance.darkMode,
                        colorblindEnabled = update.colorblindEnabled ?: base.appearance.colorblindEnabled,
                        language = normalizeLanguage(update.language ?: base.appearance.language)
                    )
                )
            }

            is PreferencesSectionUpdate.AlertUpdate -> {
                val current = base.alerts.channelFor(update.category)
                val mergedChannel = current.copy(
                    alert = update.alert ?: current.alert,
                    audio = update.audio ?: current.audio
                )
                base.copy(alerts = base.alerts.withChannel(update.category, mergedChannel))
            }

            is PreferencesSectionUpdate.WeatherUpdate -> {
                base.copy(
                    weather = when (update.field) {
                        WeatherField.FEELS_LIKE -> base.weather.copy(feelsLike = update.enabled)
                        WeatherField.WIND -> base.weather.copy(wind = update.enabled)
                        WeatherField.HUMIDITY -> base.weather.copy(humidity = update.enabled)
                        WeatherField.PRESSURE -> base.weather.copy(pressure = update.enabled)
                        WeatherField.VISIBILITY -> base.weather.copy(visibility = update.enabled)
                        WeatherField.UV_INDEX -> base.weather.copy(uvIndex = update.enabled)
                    }
                )
            }
        }
    }

    fun buildPatchPayload(
        mergedPreferences: UserPreferences,
        sectionTypes: Set<PreferencesSectionType>
    ): PreferencesPatchPayloadDto {
        return PreferencesPatchPayloadDto(
            appearance = if (sectionTypes.contains(PreferencesSectionType.APPEARANCE)) {
                AppearanceDto(
                    darkMode = mergedPreferences.appearance.darkMode,
                    colorblindEnabled = mergedPreferences.appearance.colorblindEnabled,
                    language = normalizeLanguage(mergedPreferences.appearance.language)
                )
            } else null,

            alerts = if (sectionTypes.contains(PreferencesSectionType.ALERTS)) {
                val alerts = mergedPreferences.alerts
                AlertsDto(
                    accident = alerts.accident.toDto(),
                    speeding = alerts.speeding.toDto(),
                    weather = alerts.weather.toDto(),
                    overtaking = alerts.overtaking.toDto(),
                    emergencyVehicle = alerts.emergencyVehicle.toDto(),
                    navigation = alerts.navigation.toDto(),
                    traffic = alerts.traffic.toDto(),
                    maneuver = alerts.maneuver.toDto()
                )
            } else null,

            weather = if (sectionTypes.contains(PreferencesSectionType.WEATHER)) {
                val weather = mergedPreferences.weather
                WeatherDto(
                    feelsLike = weather.feelsLike,
                    wind = weather.wind,
                    humidity = weather.humidity,
                    pressure = weather.pressure,
                    visibility = weather.visibility,
                    uvIndex = weather.uvIndex
                )
            } else null
        )
    }

    private fun normalizeLanguage(language: String): String {
        return if (language.equals("pt", ignoreCase = true)) "pt" else "en"
    }

    private fun AlertChannelPreference.toDto(): AlertChannelDto {
        return AlertChannelDto(alert = alert, audio = audio)
    }

    private fun AlertPreferences.channelFor(category: AlertCategory): AlertChannelPreference {
        return when (category) {
            AlertCategory.ACCIDENT -> accident
            AlertCategory.SPEEDING -> speeding
            AlertCategory.WEATHER -> weather
            AlertCategory.OVERTAKING -> overtaking
            AlertCategory.EMERGENCY_VEHICLE -> emergencyVehicle
            AlertCategory.NAVIGATION -> navigation
            AlertCategory.TRAFFIC -> traffic
            AlertCategory.MANEUVER -> maneuver
        }
    }

    private fun AlertPreferences.withChannel(
        category: AlertCategory,
        value: AlertChannelPreference
    ): AlertPreferences {
        return when (category) {
            AlertCategory.ACCIDENT -> copy(accident = value)
            AlertCategory.SPEEDING -> copy(speeding = value)
            AlertCategory.WEATHER -> copy(weather = value)
            AlertCategory.OVERTAKING -> copy(overtaking = value)
            AlertCategory.EMERGENCY_VEHICLE -> copy(emergencyVehicle = value)
            AlertCategory.NAVIGATION -> copy(navigation = value)
            AlertCategory.TRAFFIC -> copy(traffic = value)
            AlertCategory.MANEUVER -> copy(maneuver = value)
        }
    }
}
