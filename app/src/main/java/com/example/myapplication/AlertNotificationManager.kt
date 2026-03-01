package com.example.myapplication

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.config.AlertPreferenceManager
import java.util.Locale

class AlertNotificationManager(
    private val activity: Activity,
    private val alertPreferenceManager: AlertPreferenceManager,
    private val inAppNotificationManager: InAppNotificationManager
) {

    companion object {
        private const val TAG = "AlertNotificationManager"

        // Notification channels are kept so that any previously granted
        // permissions / channel settings remain valid, but we no longer
        // post native heads-up notifications — everything goes through
        // InAppNotificationManager instead.
        private const val CHANNEL_ID = "weather_alerts"
        private const val CHANNEL_NAME = "Weather Alerts"
        private const val CHANNEL_DESCRIPTION = "Notifications for weather alerts and warnings"

        private const val ACCIDENT_CHANNEL_ID = "accident_alerts"
        private const val ACCIDENT_CHANNEL_NAME = "Accident Alerts"
        private const val ACCIDENT_CHANNEL_DESCRIPTION =
            "Critical notifications for accident alerts ahead"

        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001

        /** Cooldown between repeated TTS for the same alert type (prevents spam). */
        private const val SPEAK_COOLDOWN_MS = 10_000L
    }

    // ── Deduplication sets ───────────────────────────────────────────────

    private val displayedAlerts = mutableSetOf<String>()
    private val displayedAccidentAlerts = mutableSetOf<String>()

    // ── TTS ──────────────────────────────────────────────────────────────

    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    private val handler = Handler(Looper.getMainLooper())

    /** Cooldown tracking per alert type to prevent TTS spam. */
    private val lastSpokenTimestamps = mutableMapOf<AlertPreferenceManager.AlertType, Long>()

    init {
        createNotificationChannels()
        initTextToSpeech()
    }

    // ── TTS lifecycle ────────────────────────────────────────────────────

    private fun initTextToSpeech() {
        tts = TextToSpeech(activity) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                ttsInitialized =
                    result != TextToSpeech.LANG_MISSING_DATA &&
                            result != TextToSpeech.LANG_NOT_SUPPORTED
                if (ttsInitialized) {
                    Log.d(TAG, "TTS initialised successfully")
                } else {
                    Log.w(TAG, "TTS language not supported")
                }
            } else {
                Log.e(TAG, "TTS initialisation failed")
            }
        }
    }

    private fun speakText(text: String, flush: Boolean = true) {
        if (ttsInitialized) {
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(text, mode, null, "alert_${System.currentTimeMillis()}")
            Log.d(TAG, "TTS: $text")
        } else {
            Log.w(TAG, "TTS not ready: $text")
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsInitialized = false
    }

    // ── Public preference helpers ────────────────────────────────────────

    /**
     * Check if a given alert type should be processed (enabled by the user).
     */
    fun shouldProcessAlert(alertType: AlertPreferenceManager.AlertType): Boolean =
        alertPreferenceManager.isEnabled(alertType)

    /**
     * Speak an alert message if the event type is enabled, audio is on,
     * and the cooldown period has elapsed.
     */
    fun speakForAlert(
        alertType: AlertPreferenceManager.AlertType,
        text: String,
        flush: Boolean = true
    ) {
        handler.post {
            if (!alertPreferenceManager.isEnabled(alertType) ||
                !alertPreferenceManager.isAudioEnabled(alertType)
            ) return@post

            val now = System.currentTimeMillis()
            val lastSpoken = lastSpokenTimestamps[alertType] ?: 0L
            if (now - lastSpoken < SPEAK_COOLDOWN_MS) return@post
            lastSpokenTimestamps[alertType] = now

            speakText(text, flush)
        }
    }

    // ── Notification channels (kept for backwards compatibility) ─────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm =
                activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = CHANNEL_DESCRIPTION
                        enableVibration(false)
                        setShowBadge(true)
                    }
            )

            nm.createNotificationChannel(
                NotificationChannel(
                    ACCIDENT_CHANNEL_ID,
                    ACCIDENT_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = ACCIDENT_CHANNEL_DESCRIPTION
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 200, 500)
                    setShowBadge(true)
                }
            )

            Log.d(TAG, "Notification channels ensured")
        }
    }

    // ── Permission helpers ───────────────────────────────────────────────

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    activity, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                activity, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    // ── Weather alerts ───────────────────────────────────────────────────

    /**
     * Show in-app banner notifications for new weather alerts.
     * Already-shown alerts (keyed by event + start time) are skipped.
     * TTS is spoken for the first new alert (subject to cooldown).
     */
    fun showWeatherAlerts(alerts: List<OpenWeatherMapClient.WeatherAlert>) {
        if (alerts.isEmpty()) return
        Log.d(TAG, "Processing ${alerts.size} weather alert(s)")

        var firstNewAlert = true
        alerts.forEach { alert ->
            val key = "${alert.event}_${alert.start}"
            if (displayedAlerts.contains(key)) return@forEach

            displayedAlerts.add(key)
            Log.d(TAG, "Queuing weather alert: ${alert.event}")

            val emoji = getAlertEmoji(alert.event)
            val message = alert.description.take(120).let {
                if (it.length == 120) "$it…" else it
            }

            inAppNotificationManager.show(
                type = InAppNotificationManager.Type.WEATHER,
                title = "$emoji ${alert.event}",
                message = message,
                duration = InAppNotificationManager.LONG_DURATION_MS
            )

            // Speak only the first new alert to avoid TTS overload
            if (firstNewAlert) {
                firstNewAlert = false
                speakForAlert(
                    AlertPreferenceManager.AlertType.WEATHER,
                    "Weather alert: ${alert.event}"
                )
            }
        }
    }

    fun isAlertActive(alert: OpenWeatherMapClient.WeatherAlert): Boolean {
        val currentTime = System.currentTimeMillis() / 1000
        return currentTime >= alert.start && currentTime <= alert.end
    }

    fun getActiveAlerts(
        alerts: List<OpenWeatherMapClient.WeatherAlert>
    ): List<OpenWeatherMapClient.WeatherAlert> = alerts.filter { isAlertActive(it) }

    fun clearDisplayedAlerts() {
        displayedAlerts.clear()
        Log.d(TAG, "Cleared displayed weather alert keys")
    }

    // ── Accident alerts ──────────────────────────────────────────────────

    /**
     * Data class representing an accident alert event.
     */
    data class AccidentAlertData(
        val eventId: String,
        val latitude: Double,
        val longitude: Double,
        val distanceMeters: Double,
        val timestamp: Long
    )

    /**
     * Process an accident alert: deduplicate, speak via TTS, and invoke
     * [onAccidentDisplayed] so the caller can update the map and show
     * the in-app notification with the distance.
     */
    fun showAccidentAlert(
        accidentData: AccidentAlertData,
        onAccidentDisplayed: ((AccidentAlertData) -> Unit)? = null
    ) {
        if (displayedAccidentAlerts.contains(accidentData.eventId)) {
            Log.d(TAG, "Accident ${accidentData.eventId} already shown, skipping")
            return
        }

        displayedAccidentAlerts.add(accidentData.eventId)
        Log.d(TAG, "Processing accident alert: ${accidentData.eventId}")

        val distanceText = UiController.formatDistance(accidentData.distanceMeters)

        speakForAlert(
            AlertPreferenceManager.AlertType.ACCIDENT,
            "Warning! Accident ahead in $distanceText."
        )

        onAccidentDisplayed?.invoke(accidentData)
    }

    fun isAccidentAlertDisplayed(eventId: String): Boolean =
        displayedAccidentAlerts.contains(eventId)

    fun clearDisplayedAccidentAlerts() {
        displayedAccidentAlerts.clear()
        Log.d(TAG, "Cleared displayed accident alert keys")
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private fun getAlertEmoji(event: String): String = when {
        event.contains("Tornado", ignoreCase = true) -> "🌪️"
        event.contains("Thunderstorm", ignoreCase = true) -> "⛈️"
        event.contains("Rain", ignoreCase = true) -> "🌧️"
        event.contains("Snow", ignoreCase = true) -> "❄️"
        event.contains("Wind", ignoreCase = true) -> "💨"
        event.contains("Fog", ignoreCase = true) -> "🌫️"
        event.contains("Heat", ignoreCase = true) -> "🔥"
        event.contains("Cold", ignoreCase = true) -> "🥶"
        event.contains("Flood", ignoreCase = true) -> "🌊"
        event.contains("Hurricane", ignoreCase = true) -> "🌀"
        event.contains("Hail", ignoreCase = true) -> "🧊"
        event.contains("Dust", ignoreCase = true) -> "🏜️"
        event.contains("Ice", ignoreCase = true) -> "🧊"
        event.contains("Frost", ignoreCase = true) -> "❄️"
        event.contains("Fire", ignoreCase = true) -> "🔥"
        event.contains("Smoke", ignoreCase = true) -> "💨"
        event.contains("Storm", ignoreCase = true) -> "🌩️"
        else -> "⚠️"
    }
}
