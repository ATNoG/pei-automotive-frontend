package com.example.myapplication.navigation.geocoding

import android.util.Log
import com.example.myapplication.navigation.models.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GeoCodeResult(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val type: String = "point_of_interest"
)

/**
 * MapTiler Geocoding API Client for reverse and forward geocoding.
 * 
 * Uses MapTiler's free geocoding API to:
 * - Search for locations by name
 * - Get coordinates for addresses
 * - Get address from coordinates
 * 
 * API Documentation: https://docs.maptiler.com/cloud/api/geocoding/
 */
object GeocodeApiClient {
    
    private const val TAG = "GeocodeApiClient"
    private const val MAPTILER_GEOCODE_URL = "https://api.maptiler.com/geocoding"
    private var mapTilerApiKey: String? = null
    
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 10000
    
    /**
     * Initialize with MapTiler API key
     */
    fun initialize(apiKey: String?) {
        mapTilerApiKey = apiKey
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "MapTiler API key not set - geocoding will be disabled")
        } else {
            Log.d(TAG, "MapTiler geocoding initialized")
        }
    }
    
    /**
     * Search for locations by name/address.
     * 
     * @param query Search query (can be partial - triggers as-you-type)
     * @param proximity Optional LatLng to bias search results towards nearby locations
     * @param limit Maximum number of results to return (default 5)
     * @return List of GeoCodeResult objects
     */
    suspend fun searchLocations(
        query: String,
        proximity: LatLng? = null,
        limit: Int = 5
    ): List<GeoCodeResult> = withContext(Dispatchers.IO) {
        if (mapTilerApiKey.isNullOrBlank()) {
            Log.e(TAG, "MapTiler API key not set")
            return@withContext emptyList()
        }
        
        if (query.trim().isEmpty()) {
            return@withContext emptyList()
        }
        
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            var urlString = "$MAPTILER_GEOCODE_URL/$encodedQuery.json?key=$mapTilerApiKey&limit=$limit"
            
            // Add proximity bias if provided (for location-aware search)
            if (proximity != null) {
                urlString += "&proximity=${proximity.longitude},${proximity.latitude}"
                
                // Provide a boundary box to constrain results preventing matches from other continents
                // +/- 5.0 degrees provides roughly a 500km radius focus area around the car
                val bboxOffset = 5.0
                val minLon = proximity.longitude - bboxOffset
                val minLat = proximity.latitude - bboxOffset
                val maxLon = proximity.longitude + bboxOffset
                val maxLat = proximity.latitude + bboxOffset
                urlString += "&bbox=$minLon,$minLat,$maxLon,$maxLat"
            }
            
            // Add language bias for better local results (fixes issues with accents like Glicínias vs Glicinias)
            val lang = java.util.Locale.getDefault().language
            if (lang.isNotEmpty()) {
                urlString += "&language=$lang"
            }
            
            Log.d(TAG, "Searching for: $query")
            
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Geocoding API error: $responseCode")
                return@withContext emptyList()
            }
            
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                reader.readText()
            }
            
            val results = mutableListOf<GeoCodeResult>()
            val jsonResponse = JSONObject(response)
            Log.d(TAG, "Full API response: $response")
            val features = jsonResponse.getJSONArray("features")
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val geometry = feature.getJSONObject("geometry")
                val coordinates = geometry.getJSONArray("coordinates")
                
                val lon = coordinates.getDouble(0)
                val lat = coordinates.getDouble(1)
                
                val properties = feature.optJSONObject("properties") ?: JSONObject()
                Log.d(TAG, "Feature $i properties: $properties")
                
                // Extract name with multiple fallbacks - MapTiler uses top-level fields for text/place_name
                val name = feature.optString("text", "").takeIf { it.isNotEmpty() }
                    ?: feature.optString("place_name", "").takeIf { it.isNotEmpty() }
                    ?: properties.optString("name", "").takeIf { it.isNotEmpty() }
                    ?: properties.optString("title", "").takeIf { it.isNotEmpty() }
                    ?: properties.optString("label", "").takeIf { it.isNotEmpty() }
                    ?: "Location"
                Log.d(TAG, "Extracted name: '$name'")
                
                // Build full address from top-level and properties
                val addressParts = mutableListOf<String>()
                
                val placeName = feature.optString("place_name", "").takeIf { it.isNotEmpty() }
                if (placeName != null && placeName != name) {
                    addressParts.add(placeName)
                } else {
                    properties.optString("address").takeIf { it.isNotEmpty() }?.let { addressParts.add(it) }
                    properties.optString("street").takeIf { it.isNotEmpty() }?.let { addressParts.add(it) }
                    properties.optString("city").takeIf { it.isNotEmpty() }?.let { addressParts.add(it) }
                    properties.optString("state").takeIf { it.isNotEmpty() }?.let { addressParts.add(it) }
                    properties.optString("country").takeIf { it.isNotEmpty() }?.let { addressParts.add(it) }
                }
                
                val address = if (addressParts.isNotEmpty()) {
                    addressParts.joinToString(", ")
                } else {
                    "Location"
                }
                
                val type = feature.optString("place_type", properties.optString("type", "point_of_interest"))
                        .replace("[\\[\\]\"]".toRegex(), "")
                
                results.add(
                    GeoCodeResult(
                        name = name,
                        address = address,
                        latitude = lat,
                        longitude = lon,
                        type = type
                    )
                )
                
                Log.d(TAG, "Found: $name at $lat, $lon")
            }
            
            results
        } catch (e: Exception) {
            Log.e(TAG, "Error searching locations", e)
            emptyList()
        }
    }
    
    /**
     * Reverse geocode - get address from coordinates
     */
    suspend fun reverseGeocode(location: LatLng): GeoCodeResult? = withContext(Dispatchers.IO) {
        if (mapTilerApiKey.isNullOrBlank()) {
            Log.e(TAG, "MapTiler API key not set")
            return@withContext null
        }
        
        try {
            val urlString = "$MAPTILER_GEOCODE_URL/${location.longitude},${location.latitude}.json?key=$mapTilerApiKey"
            
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Reverse geocoding error: $responseCode")
                return@withContext null
            }
            
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                reader.readText()
            }
            
            val jsonResponse = JSONObject(response)
            val features = jsonResponse.getJSONArray("features")
            
            if (features.length() > 0) {
                val feature = features.getJSONObject(0)
                val geometry = feature.getJSONObject("geometry")
                val coordinates = geometry.getJSONArray("coordinates")
                
                val lon = coordinates.getDouble(0)
                val lat = coordinates.getDouble(1)
                
                val properties = feature.optJSONObject("properties") ?: JSONObject()
                
                val name = feature.optString("text", "").takeIf { it.isNotEmpty() }
                    ?: feature.optString("place_name", "").takeIf { it.isNotEmpty() }
                    ?: properties.optString("name", "Location")
                    
                val addressParts = mutableListOf<String>()
                val placeName = feature.optString("place_name", "").takeIf { it.isNotEmpty() }
                if (placeName != null && placeName != name) {
                    addressParts.add(placeName)
                } else {
                    properties.optString("address").takeIf { it.isNotEmpty() }?.let { addressParts.add(it) }
                    properties.optString("city").takeIf { it.isNotEmpty() }?.let { addressParts.add(it) }
                    properties.optString("state").takeIf { it.isNotEmpty() }?.let { addressParts.add(it) }
                }
                
                val address = if (addressParts.isNotEmpty()) {
                    addressParts.joinToString(", ")
                } else {
                    ""
                }
                
                val type = feature.optString("place_type", properties.optString("type", "point_of_interest")).replace("[\\[\\]\"]".toRegex(), "")
                
                return@withContext GeoCodeResult(
                    name = name,
                    address = address,
                    latitude = lat,
                    longitude = lon,
                    type = type
                )
            }
            
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error reverse geocoding", e)
            null
        }
    }
}
