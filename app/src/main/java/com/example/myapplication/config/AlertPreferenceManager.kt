package com.example.myapplication.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages per-event-type alert preferences (enabled/disabled + audio on/off).
 *
 * Each alert type has two independent toggles:
 * - **Enabled**: Controls whether the alert is shown in the UI at all.
 * - **Audio**: Controls whether TTS (text-to-speech) plays for the alert.
 *
 * Audio is only relevant when the alert is enabled.
 * Preferences are persisted via SharedPreferences.
 */
class AlertPreferenceManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "AlertPreferences"
        private const val SUFFIX_ENABLED = "_enabled"
        private const val SUFFIX_AUDIO = "_audio"
    }

    /**
     * Supported alert event types with storage keys and display labels.
     * Ordering here determines the display order in the settings dialog.
     *
     * @property key             SharedPreferences key prefix.
     * @property displayName     Human-readable label for the settings UI.
     * @property defaultAudioEnabled  Whether audio (TTS) defaults to ON for this type.
     */
    enum class AlertType(
        val key: String,
        val displayName: String,
        val defaultAudioEnabled: Boolean = false
    ) {
        ACCIDENT("accident", "Accident", defaultAudioEnabled = true),
        SPEEDING("speeding", "Speeding"),
        WEATHER("weather", "Weather"),
        OVERTAKING("overtaking", "Overtaking"),
        EMERGENCY_VEHICLE("emergency_vehicle", "Emergency Vehicle"),
        NAVIGATION("navigation", "Navigation")
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Check if a specific alert type is enabled (visible in UI). Default: true. */
    fun isEnabled(type: AlertType): Boolean =
        prefs.getBoolean(type.key + SUFFIX_ENABLED, true)

    /** Set whether a specific alert type is enabled. */
    fun setEnabled(type: AlertType, enabled: Boolean) {
        prefs.edit().putBoolean(type.key + SUFFIX_ENABLED, enabled).apply()
    }

    /** Check if audio (TTS) is enabled for a specific alert type. Uses per-type default. */
    fun isAudioEnabled(type: AlertType): Boolean =
        prefs.getBoolean(type.key + SUFFIX_AUDIO, type.defaultAudioEnabled)

    /** Set whether audio (TTS) is enabled for a specific alert type. */
    fun setAudioEnabled(type: AlertType, enabled: Boolean) {
        prefs.edit().putBoolean(type.key + SUFFIX_AUDIO, enabled).apply()
    }
}
