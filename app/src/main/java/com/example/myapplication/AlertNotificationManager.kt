package com.example.myapplication

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.LinkedList
import java.util.Queue

class AlertNotificationManager(private val activity: Activity) {

    companion object {
        private const val TAG = "AlertNotificationManager"
        private const val CHANNEL_ID = "weather_alerts"
        private const val CHANNEL_NAME = "Weather Alerts"
        private const val CHANNEL_DESCRIPTION = "Notifications for weather alerts and warnings"
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
        
        // Delay between notifications (heads-up notifications auto-dismiss after ~8 seconds)
        private const val NOTIFICATION_DELAY_MS = 9000L
    }

    private val displayedAlerts = mutableSetOf<String>()
    private var notificationId = 1000
    private val alertQueue: Queue<OpenWeatherMapClient.WeatherAlert> = LinkedList()
    private var isProcessingQueue = false
    private val handler = Handler(Looper.getMainLooper())

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESCRIPTION
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }

            val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: $CHANNEL_ID")
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
            val intent = Intent(activity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                activity,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val iconRes = getAlertIcon(alert.event)

            val builder = NotificationCompat.Builder(activity, CHANNEL_ID)
                .setSmallIcon(iconRes)
                .setContentTitle(alert.event)
                .setContentText(alert.description.take(100))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
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

    fun clearQueue() {
        alertQueue.clear()
        handler.removeCallbacksAndMessages(null)
        isProcessingQueue = false
        Log.d(TAG, "Cleared alert queue and pending callbacks")
    }

    fun cancelAllNotifications() {
        val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
        Log.d(TAG, "Cancelled all notifications")
    }

    fun cancelNotification(notificationId: Int) {
        val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)
        Log.d(TAG, "Cancelled notification with ID: $notificationId")
    }

    fun getQueueSize(): Int {
        return alertQueue.size
    }
}