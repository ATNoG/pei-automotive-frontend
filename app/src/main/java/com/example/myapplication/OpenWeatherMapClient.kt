package com.example.myapplication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import android.util.Log

object OpenWeatherMapClient {
    private const val BASE_URL = "https://api.openweathermap.org/data/3.0/onecall"
    private const val TAG = "OpenWeatherMap"

    data class WeatherData(
        val temperature: Int,
        val feelsLike: Int,
        val weatherCondition: String,
        val weatherDescription: String,
        val isRain: Boolean,
        val windSpeed: Int,
        val humidity: Int,
        val pressure: Int,
        val visibility: Int,
        val uvIndex: Double,
        val clouds: Int
    )

    data class WeatherAlert(
        val event: String,
        val start: Long,
        val end: Long,
        val description: String,
        val senderName: String = "",
        val tags: List<String> = emptyList()
    )

    /**
     * Fetches both weather data and alerts in a single call
     * Returns a pair of WeatherData and list of WeatherAlerts
     */
    suspend fun getWeatherAndAlerts(
        lat: Double, 
        lon: Double, 
        apiKey: String
    ): Pair<WeatherData?, List<WeatherAlert>> = 
        withContext(Dispatchers.IO) {
        try {
            val urlString = "$BASE_URL?lat=$lat&lon=$lon&appid=$apiKey&units=metric"
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection

            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(response)

                // Parse weather data
                val weatherData = parseWeatherData(json)

                // Parse alerts
                val alerts = parseAlerts(json)

                Pair(weatherData, alerts)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching weather and alerts", e)
            e.printStackTrace()
            Pair(null, emptyList())
        }
    }

    /**
     * Helper function to parse weather data from JSON response
     */
    private fun parseWeatherData(json: JSONObject): WeatherData? {
        return try {
            val current = json.getJSONObject("current")
            val temp = current.getDouble("temp").toInt()
            val feelsLike = current.getDouble("feels_like").toInt()
            val windSpeedMs = current.getDouble("wind_speed")
            val windSpeedKmh = (windSpeedMs * 3.6).toInt()
            val humidity = current.getInt("humidity")
            val pressure = current.getInt("pressure")
            val visibility = current.optInt("visibility", 10000) / 1000 // Convert to km
            val uvIndex = current.optDouble("uvi", 0.0)
            val clouds = current.optInt("clouds", 0)

            val weatherArray = current.getJSONArray("weather")
            val weather = weatherArray.getJSONObject(0)
            val condition = weather.getString("main")
            val description = weather.getString("description")
            
            val isRain = condition.equals("Rain", ignoreCase = true) || 
                        condition.equals("Drizzle", ignoreCase = true) ||
                        condition.equals("Thunderstorm", ignoreCase = true) ||
                        condition.equals("Clouds", ignoreCase = true)

            WeatherData(temp, feelsLike, condition, description, isRain, windSpeedKmh, humidity, pressure, visibility, uvIndex, clouds)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing weather data", e)
            null
        }
    }

    /**
     * Helper function to parse alerts from JSON response
     */
    private fun parseAlerts(json: JSONObject): List<WeatherAlert> {
        return try {
            val alerts = mutableListOf<WeatherAlert>()

            if (json.has("alerts")) {
                val alertsArray = json.getJSONArray("alerts")
                
                for (i in 0 until alertsArray.length()) {
                    val alertObj = alertsArray.getJSONObject(i)
                    
                    val event = alertObj.getString("event")
                    val start = alertObj.getLong("start")
                    val end = alertObj.getLong("end")
                    val description = alertObj.getString("description")
                    val senderName = alertObj.optString("sender_name", "")
                    
                    val tags = mutableListOf<String>()
                    if (alertObj.has("tags")) {
                        val tagsArray = alertObj.getJSONArray("tags")
                        for (j in 0 until tagsArray.length()) {
                            tags.add(tagsArray.getString(j))
                        }
                    }
                    
                    val alert = WeatherAlert(event, start, end, description, senderName, tags)
                    alerts.add(alert)
                    
                    Log.d(TAG, "Weather Alert: $event - $description")
                }
            }

            alerts
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing alerts", e)
            emptyList()
        }
    }
}