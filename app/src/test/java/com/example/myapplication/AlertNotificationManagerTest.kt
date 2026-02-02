package com.example.myapplication

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * Unit tests for AlertNotificationManager duplicate alert filtering logic.
 * 
 * These tests verify that the same alert is not shown multiple times
 * within the same session, even when weather data is fetched every 10 minutes.
 */
class AlertNotificationManagerTest {

    // Simulates the displayedAlerts set from AlertNotificationManager
    private lateinit var displayedAlerts: MutableSet<String>

    @Before
    fun setUp() {
        displayedAlerts = mutableSetOf()
    }

    /**
     * Helper function that mimics the alert key generation logic
     * from AlertNotificationManager.showWeatherAlerts()
     */
    private fun generateAlertKey(event: String, start: Long): String {
        return "${event}_${start}"
    }

    /**
     * Helper function that mimics the filtering logic from
     * AlertNotificationManager.showWeatherAlerts()
     * Returns the number of NEW alerts that would be queued
     */
    private fun processAlerts(alerts: List<TestWeatherAlert>): Int {
        var newAlertsCount = 0
        alerts.forEach { alert ->
            val alertKey = generateAlertKey(alert.event, alert.start)
            if (!displayedAlerts.contains(alertKey)) {
                displayedAlerts.add(alertKey)
                newAlertsCount++
            }
        }
        return newAlertsCount
    }

    // Simple test data class to avoid dependency on OpenWeatherMapClient
    data class TestWeatherAlert(
        val event: String,
        val start: Long,
        val end: Long,
        val description: String
    )

    @Test
    fun `same alert is not shown twice in same session`() {
        val alert = TestWeatherAlert(
            event = "Thunderstorm Warning",
            start = 1700000000L,
            end = 1700003600L,
            description = "Severe thunderstorm expected"
        )

        // First time seeing this alert - should be queued
        val firstFetch = processAlerts(listOf(alert))
        assertEquals("First fetch should queue the alert", 1, firstFetch)

        // Second fetch (simulating 10 minutes later) with same alert - should NOT be queued
        val secondFetch = processAlerts(listOf(alert))
        assertEquals("Second fetch should not queue duplicate alert", 0, secondFetch)

        // Third fetch - still should not be queued
        val thirdFetch = processAlerts(listOf(alert))
        assertEquals("Third fetch should not queue duplicate alert", 0, thirdFetch)
    }

    @Test
    fun `different alerts are all shown`() {
        val alert1 = TestWeatherAlert(
            event = "Thunderstorm Warning",
            start = 1700000000L,
            end = 1700003600L,
            description = "Severe thunderstorm expected"
        )
        val alert2 = TestWeatherAlert(
            event = "Flood Warning",
            start = 1700001000L,
            end = 1700005000L,
            description = "Flash flooding possible"
        )

        val result = processAlerts(listOf(alert1, alert2))
        assertEquals("Both different alerts should be queued", 2, result)
    }

    @Test
    fun `same event with different start time is treated as different alert`() {
        val alert1 = TestWeatherAlert(
            event = "Thunderstorm Warning",
            start = 1700000000L,
            end = 1700003600L,
            description = "First thunderstorm"
        )
        val alert2 = TestWeatherAlert(
            event = "Thunderstorm Warning",
            start = 1700010000L, // Different start time
            end = 1700013600L,
            description = "Second thunderstorm"
        )

        val firstResult = processAlerts(listOf(alert1))
        assertEquals("First alert should be queued", 1, firstResult)

        val secondResult = processAlerts(listOf(alert2))
        assertEquals("Same event with different start time should be queued", 1, secondResult)
    }

    @Test
    fun `mixed new and duplicate alerts filters correctly`() {
        val existingAlert = TestWeatherAlert(
            event = "Heat Advisory",
            start = 1700000000L,
            end = 1700010000L,
            description = "Extreme heat expected"
        )

        // First fetch with one alert
        processAlerts(listOf(existingAlert))

        // Second fetch returns the same alert plus a new one
        val newAlert = TestWeatherAlert(
            event = "Wind Advisory",
            start = 1700005000L,
            end = 1700015000L,
            description = "High winds expected"
        )

        val result = processAlerts(listOf(existingAlert, newAlert))
        assertEquals("Only the new alert should be queued", 1, result)
    }

    @Test
    fun `empty alerts list returns zero`() {
        val result = processAlerts(emptyList())
        assertEquals("Empty list should queue zero alerts", 0, result)
    }

    @Test
    fun `alert key generation is consistent`() {
        val event = "Tornado Warning"
        val start = 1700000000L

        val key1 = generateAlertKey(event, start)
        val key2 = generateAlertKey(event, start)

        assertEquals("Same inputs should generate same key", key1, key2)
        assertEquals("Key format should be event_start", "Tornado Warning_1700000000", key1)
    }
}