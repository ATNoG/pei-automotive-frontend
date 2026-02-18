package com.example.myapplication

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.config.AlertPreferenceManager
import java.util.LinkedList
import java.util.Locale
import java.util.Queue

class AlertNotificationManager(
    private val activity: Activity,
    private val alertPreferenceManager: AlertPreferenceManager
) {

    companion object {
        private const val TAG = "AlertNotificationManager"
        private const val CHANNEL_ID = "weather_alerts"
        private const val CHANNEL_NAME = "Weather Alerts"
        private const val CHANNEL_DESCRIPTION = "Notifications for weather alerts and warnings"
        
        // Accident alerts channel
        private const val ACCIDENT_CHANNEL_ID = "accident_alerts"
        private const val ACCIDENT_CHANNEL_NAME = "Accident Alerts"
        private const val ACCIDENT_CHANNEL_DESCRIPTION = "Critical notifications for accident alerts ahead"
        
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001

        // Delay between notifications (heads-up notifications auto-dismiss after ~8 seconds)
        private const val NOTIFICATION_DELAY_MS = 9000L

        // Cooldown between repeated TTS for the same alert type (prevents spam)
        private const val SPEAK_COOLDOWN_MS = 10_000L
    }

    private val displayedAlerts = mutableSetOf<String>()
    private val displayedAccidentAlerts = mutableSetOf<String>()
    private var notificationId = 1000
    private val alertQueue: Queue<OpenWeatherMapClient.WeatherAlert> = LinkedList()
    private var isProcessingQueue = false
    private val handler = Handler(Looper.getMainLooper())
    
    // Text-to-Speech for alerts
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false

    // Cooldown tracking per alert type to prevent TTS spam
    private val lastSpokenTimestamps = mutableMapOf<AlertPreferenceManager.AlertType, Long>()

    init {
        createNotificationChannel()
        createAccidentNotificationChannel()
        initTextToSpeech()
    }
    
    private fun initTextToSpeech() {
        tts = TextToSpeech(activity) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                ttsInitialized = result != TextToSpeech.LANG_MISSING_DATA && 
                                 result != TextToSpeech.LANG_NOT_SUPPORTED
                if (ttsInitialized) {
                    Log.d(TAG, "Text-to-Speech initialized successfully")
                } else {
                    Log.w(TAG, "Text-to-Speech language not supported")
                }
            } else {
                Log.e(TAG, "Text-to-Speech initialization failed")
            }
        }
    }
    
    private fun speakText(text: String, flush: Boolean = true) {
        if (ttsInitialized) {
            val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(text, queueMode, null, "alert_${System.currentTimeMillis()}")
            Log.d(TAG, "TTS speaking: $text")
        } else {
            Log.w(TAG, "TTS not initialized, cannot speak: $text")
        }
    }
    
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsInitialized = false
    }

    /**
     * Check if a given alert type should be processed (enabled by the user).
     * Centralizes the preference gating so callers don't need direct access
     * to AlertPreferenceManager.
     */
    fun shouldProcessAlert(alertType: AlertPreferenceManager.AlertType): Boolean =
        alertPreferenceManager.isEnabled(alertType)

    /**
     * Speak an alert message if the event type is enabled, audio is on,
     * and the cooldown period has elapsed.
     * Thread-safe: all work is posted to the main looper.
     *
     * @param alertType The alert category.
     * @param text      The text to speak.
     * @param flush     If true, interrupts current speech (for critical alerts).
     *                  If false, queues after current speech.
     */
    fun speakForAlert(
        alertType: AlertPreferenceManager.AlertType,
        text: String,
        flush: Boolean = true
    ) {
        handler.post {
            if (!alertPreferenceManager.isEnabled(alertType) ||
                !alertPreferenceManager.isAudioEnabled(alertType)) return@post

            val now = System.currentTimeMillis()
            val lastSpoken = lastSpokenTimestamps[alertType] ?: 0L
            if (now - lastSpoken < SPEAK_COOLDOWN_MS) return@post
            lastSpokenTimestamps[alertType] = now

            speakText(text, flush)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESCRIPTION
                enableVibration(false)
                setShowBadge(true)
            }

            val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: $CHANNEL_ID")
        }
    }
    
    private fun createAccidentNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(ACCIDENT_CHANNEL_ID, ACCIDENT_CHANNEL_NAME, importance).apply {
                description = ACCIDENT_CHANNEL_DESCRIPTION
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setShowBadge(true)
            }

            val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Accident notification channel created: $ACCIDENT_CHANNEL_ID")
        }
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun showWeatherAlerts(alerts: List<OpenWeatherMapClient.WeatherAlert>) {
        if (alerts.isEmpty()) {
            Log.d(TAG, "No alerts to display")
            return
        }

        Log.d(TAG, "Processing ${alerts.size} weather alerts")

        // Filter out already displayed alerts and add new ones to queue
        alerts.forEach { alert ->
            val alertKey = "${alert.event}_${alert.start}"
            if (!displayedAlerts.contains(alertKey)) {
                alertQueue.add(alert)
                displayedAlerts.add(alertKey)
                Log.d(TAG, "Added alert to queue: ${alert.event}")
                // Speak first new weather alert (cooldown prevents spam)
                speakForAlert(AlertPreferenceManager.AlertType.WEATHER, "Weather alert: ${alert.event}")
            }
        }

        // Start processing queue if not already processing
        if (!isProcessingQueue && alertQueue.isNotEmpty()) {
            processNextAlert()
        }
    }

    private fun processNextAlert() {
        if (alertQueue.isEmpty()) {
            isProcessingQueue = false
            Log.d(TAG, "Alert queue empty, finished processing")
            return
        }

        isProcessingQueue = true
        val alert = alertQueue.poll()

        if (alert != null) {
            showNotification(alert)

            // Schedule next alert after delay (to allow current heads-up to auto-dismiss)
            if (alertQueue.isNotEmpty()) {
                handler.postDelayed({
                    processNextAlert()
                }, NOTIFICATION_DELAY_MS)
            } else {
                isProcessingQueue = false
            }
        } else {
            isProcessingQueue = false
        }
    }

    private fun showNotification(alert: OpenWeatherMapClient.WeatherAlert) {
        if (!hasNotificationPermission()) {
            Log.w(TAG, "Notification permission not granted")
            requestNotificationPermission()
            return
        }

        try {
            val iconRes = getAlertIcon(alert.event)
            val emoji = getAlertEmoji(alert.event)
            val title = "$emoji ${alert.event}"
            
            // Create large icon bitmap for right side of notification
            val largeIcon = BitmapFactory.decodeResource(activity.resources, iconRes)

            val builder = NotificationCompat.Builder(activity, CHANNEL_ID)
                .setSmallIcon(iconRes)
                .setLargeIcon(largeIcon)
                .setContentTitle(title)
                .setContentText(alert.description.take(150))
                .setSubText("Weather Alert")
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(false)

            val currentNotificationId = notificationId++

            with(NotificationManagerCompat.from(activity)) {
                if (ActivityCompat.checkSelfPermission(
                        activity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                ) {
                    notify(currentNotificationId, builder.build())
                    Log.d(TAG, "Notification shown: ${alert.event} with ID: $currentNotificationId (${alertQueue.size} remaining in queue)")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification: ${e.message}", e)
        }
    }

    private fun getAlertEmoji(event: String): String {
        return when {
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

    private fun getAlertIcon(event: String): Int {
        return when {
            event.contains("Tornado", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Thunderstorm", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Rain", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Snow", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Wind", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Fog", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Heat", ignoreCase = true) -> R.drawable.ic_weather_sun
            event.contains("Cold", ignoreCase = true) -> R.drawable.ic_weather_sun
            event.contains("Flood", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Hurricane", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Hail", ignoreCase = true) -> R.drawable.ic_weather_rain
            event.contains("Dust", ignoreCase = true) -> R.drawable.ic_weather_sun
            else -> R.drawable.ic_weather_rain
        }
    }

    fun isAlertActive(alert: OpenWeatherMapClient.WeatherAlert): Boolean {
        val currentTime = System.currentTimeMillis() / 1000
        return currentTime >= alert.start && currentTime <= alert.end
    }

    fun getActiveAlerts(alerts: List<OpenWeatherMapClient.WeatherAlert>): List<OpenWeatherMapClient.WeatherAlert> {
        return alerts.filter { isAlertActive(it) }
    }

    fun clearDisplayedAlerts() {
        displayedAlerts.clear()
        Log.d(TAG, "Cleared all displayed alerts tracking")
    }
    
    // ========== Accident Alert Methods ==========
    
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
     * Show an accident alert notification with text-to-speech.
     */
    fun showAccidentAlert(
        accidentData: AccidentAlertData,
        onAccidentDisplayed: ((AccidentAlertData) -> Unit)? = null
    ) {
        // Check if we already displayed this accident
        if (displayedAccidentAlerts.contains(accidentData.eventId)) {
            Log.d(TAG, "Accident ${accidentData.eventId} already displayed, skipping")
            return
        }
        
        displayedAccidentAlerts.add(accidentData.eventId)
        Log.d(TAG, "Showing accident alert: ${accidentData.eventId}")
        
        // Use shared distance formatting utility
        val distanceText = UiController.formatDistance(accidentData.distanceMeters)
        
        // Speak the alert using TTS (respects audio preference)
        speakForAlert(
            AlertPreferenceManager.AlertType.ACCIDENT,
            "Warning! Accident ahead in $distanceText."
        )
        
        // Invoke callback for UI updates
        onAccidentDisplayed?.invoke(accidentData)
    }
    
    
    /**
     * Check if an accident alert has been displayed.
     */
    fun isAccidentAlertDisplayed(eventId: String): Boolean {
        return displayedAccidentAlerts.contains(eventId)
    }
    
    /**
     * Clear displayed accident alerts.
     */
    fun clearDisplayedAccidentAlerts() {
        displayedAccidentAlerts.clear()
        Log.d(TAG, "Cleared all displayed accident alerts tracking")
    }
}