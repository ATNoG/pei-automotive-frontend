package com.example.myapplication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object OpenWeatherMapClient {
    private const val BASE_URL = "https://api.openweathermap.org/data/2.5/weather"

    data class WeatherData(
        val temperature: Int,
        val weatherCondition: String,
        val isRain: Boolean
    )

    /**
     * Fetches weather data for the given coordinates
     */
    suspend fun getWeather(lat: Double, lon: Double, apiKey: String): WeatherData? = withContext(Dispatchers.IO) {
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

                val main = json.getJSONObject("main")
                val temp = main.getDouble("temp").toInt()

                val weatherArray = json.getJSONArray("weather")
                val weather = weatherArray.getJSONObject(0)
                val condition = weather.getString("main")
                // Consider rain, drizzle, thunderstorm, and clouds as "not sunny"
                val isRain = condition.equals("Rain", ignoreCase = true) || 
                            condition.equals("Drizzle", ignoreCase = true) ||
                            condition.equals("Thunderstorm", ignoreCase = true) ||
                            condition.equals("Clouds", ignoreCase = true)

                WeatherData(temp, condition, isRain)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
