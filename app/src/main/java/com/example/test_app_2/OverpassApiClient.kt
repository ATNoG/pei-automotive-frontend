package com.example.pei_test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object OverpassApiClient {
    private const val OVERPASS_API_URL = "https://overpass-api.de/api/interpreter"

    /**
     * Executes a proper Overpass query:
     * Finds any nearby way/node with "maxspeed" tag.
     */
    suspend fun getSpeedLimit(lat: Double, lon: Double): String = withContext(Dispatchers.IO) {
        try {
            val query = """
                [out:json][timeout:25];
                (
                  way(around:5,$lat,$lon)["maxspeed"];
                  node(around:5,$lat,$lon)["maxspeed"];
                );
                out body;
                >;
                out skel qt;
            """.trimIndent()

            val result = executeQuery(query)
            val json = JSONObject(result)

            if (!json.has("elements")) return@withContext "--"

            val elements = json.getJSONArray("elements")
            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)
                if (element.has("tags")) {
                    val tags = element.getJSONObject("tags")
                    // Strictly use real maxspeed tags — no guessing
                    when {
                        tags.has("maxspeed") -> return@withContext tags.getString("maxspeed")
                        tags.has("maxspeed:forward") -> return@withContext tags.getString("maxspeed:forward")
                        tags.has("maxspeed:backward") -> return@withContext tags.getString("maxspeed:backward")
                    }
                }
            }
            return@withContext "--" // No tag found
        } catch (e: Exception) {
            e.printStackTrace()
            "--"
        }
    }

    private fun executeQuery(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("$OVERPASS_API_URL?data=$encoded")
        val conn = url.openConnection() as HttpURLConnection

        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
