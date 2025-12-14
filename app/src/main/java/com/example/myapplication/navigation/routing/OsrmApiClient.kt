package com.example.myapplication.navigation.routing

import android.util.Log
import com.example.myapplication.navigation.NavigationConfig
import com.example.myapplication.navigation.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * OSRM (Open Source Routing Machine) API Client.
 * 
 * OPTIMIZED FOR PERFORMANCE:
 * - Multiple API fallback servers (OSRM primary + Valhalla backup)
 * - Intelligent route caching (5-minute expiry, 20 route max)
 * - Configurable timeouts
 * - Automatic error recovery
 * 
 * SCALABILITY FEATURES:
 * - Easy to add new routing providers
 * - Centralized configuration via NavigationConfig
 * - Clean async/await pattern with coroutines
 * 
 * Uses multiple public routing APIs with automatic fallback:
 * 1. OSRM public servers (tried in order)
 * 2. Valhalla (Mapbox alternative)
 * 
 * Provides turn-by-turn navigation without requiring Google services.
 * 
 * API Documentation: http://project-osrm.org/docs/v5.24.0/api/
 */
object OsrmApiClient {
    
    private const val TAG = "OsrmApiClient"
    
    // OSRM servers (tried first) - only try primary for speed
    private val OSRM_SERVERS = listOf(
        "https://router.project-osrm.org"
    )
    
    // OpenRouteService (primary fallback - excellent coverage in Portugal/Europe)
    private const val OPENROUTESERVICE_URL = "https://api.openrouteservice.org/v2/directions/driving-car"
    private var openrouteserviceApiKey: String? = null
    
    // Valhalla public server (secondary fallback)
    private const val VALHALLA_URL = "https://valhalla1.openstreetmap.de/route"
    
    // Use centralized configuration
    private const val CONNECT_TIMEOUT_MS = NavigationConfig.API_CONNECT_TIMEOUT_MS
    private const val READ_TIMEOUT_MS = NavigationConfig.API_READ_TIMEOUT_MS
    
    // Track errors for debugging
    private var lastServerError: String? = null
    
    // Track which API successfully provided the route (for consistent recalculations)
    private var lastSuccessfulApi: String? = null
    
    /**
     * Initialize API keys. Call this before using the client.
     */
    fun initialize(orsApiKey: String?) {
        openrouteserviceApiKey = orsApiKey
        if (orsApiKey.isNullOrBlank()) {
            Log.w(TAG, "OpenRouteService API key not set - ORS fallback will be skipped")
        } else {
            Log.d(TAG, "OpenRouteService API key configured")
        }
    }
    
    // Performance optimization: Cache recent routes
    // Key: "lat1,lon1->lat2,lon2" (rounded to 4 decimals ~11m precision)
    private val routeCache = mutableMapOf<String, Pair<NavigationRoute, Long>>()
    private val CACHE_EXPIRY_MS = NavigationConfig.ROUTE_CACHE_EXPIRY_MS
    private val MAX_CACHE_SIZE = NavigationConfig.MAX_ROUTE_CACHE_SIZE
    
    /**
     * Generate cache key for origin-destination pair
     */
    private fun getCacheKey(origin: LatLng, destination: LatLng): String {
        return String.format("%.4f,%.4f->%.4f,%.4f", 
            origin.latitude, origin.longitude, 
            destination.latitude, destination.longitude)
    }
    
    /**
     * Clean expired cache entries
     */
    private fun cleanExpiredCache() {
        val currentTime = System.currentTimeMillis()
        val iterator = routeCache.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTime - entry.value.second > CACHE_EXPIRY_MS) {
                iterator.remove()
            }
        }
        
        // If cache is still too large, remove oldest entries
        if (routeCache.size > MAX_CACHE_SIZE) {
            val sortedEntries = routeCache.entries.sortedBy { it.value.second }
            val toRemove = sortedEntries.take(routeCache.size - MAX_CACHE_SIZE)
            toRemove.forEach { routeCache.remove(it.key) }
        }
    }
    
    /**
     * Calculate a route between origin and destination.
     * Tries multiple APIs with automatic fallback: OSRM -> Valhalla
     * Includes intelligent caching for performance
     */
    suspend fun calculateRoute(
        origin: LatLng,
        destination: LatLng,
        alternatives: Boolean = false
    ): NavigationRoute? = withContext(Dispatchers.IO) {
        lastServerError = null
        
        // Check cache first (performance optimization)
        val cacheKey = getCacheKey(origin, destination)
        val cached = routeCache[cacheKey]
        if (cached != null) {
            val (route, timestamp) = cached
            val age = System.currentTimeMillis() - timestamp
            if (age < CACHE_EXPIRY_MS) {
                Log.d(TAG, "Route from cache (age: ${age/1000}s)")
                return@withContext route
            } else {
                // Remove expired entry
                routeCache.remove(cacheKey)
            }
        }
        
        // 1. Try OSRM first (fast fail with single server)
        for (serverUrl in OSRM_SERVERS) {
            try {
                val route = tryOsrmRoute(serverUrl, origin, destination, alternatives)
                if (route != null) {
                    Log.d(TAG, "Route from OSRM: ${serverUrl.substringAfter("://").substringBefore("/")}")
                    
                    // Track successful API for recalculations
                    lastSuccessfulApi = "OSRM:$serverUrl"
                    
                    // Cache the result
                    cleanExpiredCache()
                    routeCache[cacheKey] = Pair(route, System.currentTimeMillis())
                    
                    return@withContext route
                }
            } catch (e: Exception) {
                lastServerError = e.message
                Log.w(TAG, "OSRM failed (${serverUrl.substringAfter("://").substringBefore("/")}): ${e.message}")
            }
        }
        
        // 2. Try OpenRouteService as primary fallback (excellent Portugal/Europe coverage)
        if (!openrouteserviceApiKey.isNullOrBlank()) {
            try {
                val route = tryOpenRouteServiceRoute(origin, destination)
                if (route != null) {
                    Log.d(TAG, "Route from OpenRouteService (primary fallback)")
                    
                    // Track successful API for recalculations
                    lastSuccessfulApi = "OPENROUTESERVICE"
                    
                    // Cache the result
                    cleanExpiredCache()
                    routeCache[cacheKey] = Pair(route, System.currentTimeMillis())
                    
                    return@withContext route
                }
            } catch (e: Exception) {
                lastServerError = e.message
                Log.w(TAG, "OpenRouteService fallback failed: ${e.message}")
            }
        } else {
            Log.d(TAG, "Skipping OpenRouteService (no API key configured)")
        }
        
        // 3. Try Valhalla as final fallback
        try {
            val route = tryValhallaRoute(origin, destination)
            if (route != null) {
                Log.d(TAG, "Route from Valhalla (final fallback)")
                
                // Track successful API for recalculations
                lastSuccessfulApi = "VALHALLA"
                
                // Cache the result
                cleanExpiredCache()
                routeCache[cacheKey] = Pair(route, System.currentTimeMillis())
                
                return@withContext route
            }
        } catch (e: Exception) {
            lastServerError = e.message
            Log.w(TAG, "Valhalla fallback failed: ${e.message}")
        }
        
        Log.e(TAG, "All routing APIs failed: $lastServerError")
        null
    }
    
    private fun tryOsrmRoute(
        serverUrl: String,
        origin: LatLng,
        destination: LatLng,
        alternatives: Boolean
    ): NavigationRoute? {
        // OSRM expects coordinates as lon,lat (not lat,lon!)
        val coordinates = "${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}"
        
        val url = buildString {
            append(serverUrl)
            append("/route/v1/driving/")
            append(coordinates)
            append("?overview=full")
            append("&geometries=geojson")
            append("&steps=true")
            append("&annotations=false")
            if (alternatives) append("&alternatives=true")
        }
        
        Log.d(TAG, "OSRM request...")
        val response = executeRequest(url)
        return parseOsrmResponse(response, origin, destination)
    }
    
    /**
     * Try Valhalla routing API as fallback.
     */
    private fun tryValhallaRoute(origin: LatLng, destination: LatLng): NavigationRoute? {
        val jsonBody = JSONObject().apply {
            put("locations", JSONArray().apply {
                put(JSONObject().put("lat", origin.latitude).put("lon", origin.longitude))
                put(JSONObject().put("lat", destination.latitude).put("lon", destination.longitude))
            })
            put("costing", "auto")
            put("directions_options", JSONObject().put("language", "pt-PT"))
        }
        
        Log.d(TAG, "Valhalla request...")
        val response = executePostRequest(VALHALLA_URL, jsonBody.toString())
        return parseValhallaResponse(response, origin, destination)
    }
    
    /**
     * Try OpenRouteService routing API.
     * Excellent coverage in Portugal and Europe.
     */
    private fun tryOpenRouteServiceRoute(origin: LatLng, destination: LatLng): NavigationRoute? {
        if (openrouteserviceApiKey.isNullOrBlank()) {
            Log.w(TAG, "OpenRouteService API key not configured")
            return null
        }
        
        // ORS expects coordinates as [lon, lat]
        val jsonBody = JSONObject().apply {
            put("coordinates", JSONArray().apply {
                put(JSONArray().apply {
                    put(origin.longitude)
                    put(origin.latitude)
                })
                put(JSONArray().apply {
                    put(destination.longitude)
                    put(destination.latitude)
                })
            })
            put("preference", "recommended") // Balance between fastest and shortest
            put("instructions", true)
            put("language", "en") // Get English instructions
            put("units", "m") // Meters
        }
        
        Log.d(TAG, "OpenRouteService request...")
        val response = executePostRequestWithAuth(OPENROUTESERVICE_URL, jsonBody.toString(), openrouteserviceApiKey!!)
        return parseOpenRouteServiceResponse(response, origin, destination)
    }
    
    /**
     * Recalculate route from current position (for dynamic rerouting).
     * Uses the same API that was successful for the initial route.
     */
    suspend fun recalculateRoute(
        currentPosition: LatLng,
        destination: LatLng
    ): NavigationRoute? = withContext(Dispatchers.IO) {
        // If we know which API worked, use that one preferentially
        when {
            lastSuccessfulApi?.startsWith("OSRM:") == true -> {
                val serverUrl = lastSuccessfulApi!!.substringAfter("OSRM:")
                try {
                    val route = tryOsrmRoute(serverUrl, currentPosition, destination, false)
                    if (route != null) {
                        Log.d(TAG, "Recalculated route using same OSRM server")
                        return@withContext route
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Recalculation with OSRM failed, trying fallback: ${e.message}")
                }
            }
            lastSuccessfulApi == "OPENROUTESERVICE" -> {
                try {
                    val route = tryOpenRouteServiceRoute(currentPosition, destination)
                    if (route != null) {
                        Log.d(TAG, "Recalculated route using OpenRouteService")
                        return@withContext route
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Recalculation with OpenRouteService failed: ${e.message}")
                }
            }
            lastSuccessfulApi == "VALHALLA" -> {
                try {
                    val route = tryValhallaRoute(currentPosition, destination)
                    if (route != null) {
                        Log.d(TAG, "Recalculated route using Valhalla")
                        return@withContext route
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Recalculation with Valhalla failed: ${e.message}")
                }
            }
        }
        
        // Fallback to regular calculation if preferred API fails
        Log.d(TAG, "Recalculation falling back to full API selection")
        calculateRoute(currentPosition, destination)
    }
    
    /**
     * Snap position to nearest road.
     */
    suspend fun snapToRoad(position: LatLng): LatLng? = withContext(Dispatchers.IO) {
        try {
            val url = "${OSRM_SERVERS[0]}/nearest/v1/driving/${position.longitude},${position.latitude}"
            val response = executeRequest(url)
            val json = JSONObject(response)
            
            if (json.optString("code") == "Ok") {
                val waypoints = json.optJSONArray("waypoints")
                if (waypoints != null && waypoints.length() > 0) {
                    val location = waypoints.getJSONObject(0).getJSONArray("location")
                    return@withContext LatLng(location.getDouble(1), location.getDouble(0))
                }
            }
            position
        } catch (e: Exception) {
            Log.w(TAG, "Snap to road failed: ${e.message}")
            position
        }
    }
    
    private fun executeRequest(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "AndroidAutomotive/1.0")
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP $responseCode")
            }
            
            BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
    
    private fun executePostRequest(urlString: String, body: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "AndroidAutomotive/1.0")
            connection.doOutput = true
            
            connection.outputStream.use { os ->
                os.write(body.toByteArray())
            }
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP $responseCode")
            }
            
            BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
    
    private fun executePostRequestWithAuth(urlString: String, body: String, apiKey: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", apiKey) // ORS uses simple API key in header
            connection.setRequestProperty("User-Agent", "AndroidAutomotive/1.0")
            connection.doOutput = true
            
            connection.outputStream.use { os ->
                os.write(body.toByteArray())
            }
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = try {
                    BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                } catch (e: Exception) {
                    "Unable to read error"
                }
                throw Exception("HTTP $responseCode: $errorBody")
            }
            
            BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
    
    private fun parseOsrmResponse(response: String, origin: LatLng, destination: LatLng): NavigationRoute? {
        val json = JSONObject(response)
        
        if (json.optString("code") != "Ok") {
            Log.e(TAG, "OSRM error: ${json.optString("message", "Unknown")}")
            return null
        }
        
        val routes = json.optJSONArray("routes")
        if (routes == null || routes.length() == 0) return null
        
        val route = routes.getJSONObject(0)
        val geometry = route.getJSONObject("geometry")
        val coordinates = geometry.getJSONArray("coordinates")
        val routePoints = parseCoordinates(coordinates)
        
        val legs = route.getJSONArray("legs")
        val leg = legs.getJSONObject(0)
        val stepsJson = leg.getJSONArray("steps")
        val steps = parseSteps(stepsJson)
        
        val totalDistance = route.getDouble("distance")
        val totalDuration = route.getDouble("duration")
        
        Log.d(TAG, "Route: ${routePoints.size} pts, ${steps.size} steps, %.1f km".format(totalDistance / 1000))
        
        return NavigationRoute(
            origin = origin,
            destination = destination,
            routePoints = routePoints,
            steps = steps,
            totalDistance = totalDistance,
            totalDuration = totalDuration,
            routeName = leg.optString("summary", null)
        )
    }
    
    private fun parseCoordinates(coordinates: JSONArray): List<LatLng> {
        return (0 until coordinates.length()).map { i ->
            val coord = coordinates.getJSONArray(i)
            LatLng(coord.getDouble(1), coord.getDouble(0)) // OSRM: [lon, lat]
        }
    }
    
    private fun parseSteps(stepsJson: JSONArray): List<NavigationStep> {
        return (0 until stepsJson.length()).map { i ->
            val step = stepsJson.getJSONObject(i)
            val maneuver = step.getJSONObject("maneuver")
            val location = maneuver.getJSONArray("location")
            val roadName = step.optString("name", "").takeIf { it.isNotEmpty() }
            val maneuverType = ManeuverType.fromOsrmModifier(
                maneuver.optString("type", ""),
                maneuver.optString("modifier", "")
            )
            
            NavigationStep(
                instruction = buildInstruction(maneuverType, roadName),
                maneuverType = maneuverType,
                distance = step.getDouble("distance"),
                duration = step.getDouble("duration"),
                location = LatLng(location.getDouble(1), location.getDouble(0)),
                roadName = roadName
            )
        }
    }
    
    private fun buildInstruction(type: ManeuverType, road: String?): String {
        return when (type) {
            ManeuverType.DEPART -> road?.let { "Head straight on $it" } ?: "Start navigation"
            ManeuverType.ARRIVE -> "You have arrived at your destination"
            ManeuverType.TURN_LEFT -> road?.let { "Turn left onto $it" } ?: "Turn left"
            ManeuverType.TURN_RIGHT -> road?.let { "Turn right onto $it" } ?: "Turn right"
            ManeuverType.TURN_SLIGHT_LEFT -> road?.let { "Turn slightly left onto $it" } ?: "Turn slightly left"
            ManeuverType.TURN_SLIGHT_RIGHT -> road?.let { "Turn slightly right onto $it" } ?: "Turn slightly right"
            ManeuverType.TURN_SHARP_LEFT -> road?.let { "Turn sharp left onto $it" } ?: "Turn sharp left"
            ManeuverType.TURN_SHARP_RIGHT -> road?.let { "Turn sharp right onto $it" } ?: "Turn sharp right"
            ManeuverType.STRAIGHT, ManeuverType.CONTINUE -> road?.let { "Continue on $it" } ?: "Continue straight"
            ManeuverType.ROUNDABOUT -> "Enter the roundabout"
            ManeuverType.ROUNDABOUT_EXIT -> road?.let { "Exit the roundabout onto $it" } ?: "Exit the roundabout"
            ManeuverType.UTURN -> "Make a U-turn"
            ManeuverType.MERGE -> road?.let { "Merge onto $it" } ?: "Merge"
            ManeuverType.ON_RAMP -> road?.let { "Take the ramp to $it" } ?: "Take the ramp"
            ManeuverType.OFF_RAMP -> road?.let { "Exit onto $it" } ?: "Take the exit"
            ManeuverType.FORK_LEFT -> road?.let { "Keep left onto $it" } ?: "Keep left"
            ManeuverType.FORK_RIGHT -> road?.let { "Keep right onto $it" } ?: "Keep right"
            ManeuverType.ROUNDABOUT_LEFT -> road?.let { "At the roundabout, turn left onto $it" } ?: "At the roundabout, turn left"
            ManeuverType.ROUNDABOUT_RIGHT -> road?.let { "At the roundabout, turn right onto $it" } ?: "At the roundabout, turn right"
            ManeuverType.ROUNDABOUT_STRAIGHT -> road?.let { "At the roundabout, continue straight onto $it" } ?: "At the roundabout, continue straight"
            ManeuverType.ROUNDABOUT_SHARP_LEFT -> road?.let { "At the roundabout, turn sharp left onto $it" } ?: "At the roundabout, turn sharp left"
            ManeuverType.ROUNDABOUT_SHARP_RIGHT -> road?.let { "At the roundabout, turn sharp right onto $it" } ?: "At the roundabout, turn sharp right"
            ManeuverType.ROUNDABOUT_SLIGHT_LEFT -> road?.let { "At the roundabout, turn slightly left onto $it" } ?: "At the roundabout, turn slightly left"
            ManeuverType.ROUNDABOUT_SLIGHT_RIGHT -> road?.let { "At the roundabout, turn slightly right onto $it" } ?: "At the roundabout, turn slightly right"
            ManeuverType.UNKNOWN -> road?.let { "Continue to $it" } ?: "Continue"
            else -> road?.let { "Continue to $it" } ?: "Continue straight"
        }
    }
    
    /**
     * Parse Valhalla API response.
     */
    private fun parseValhallaResponse(response: String, origin: LatLng, destination: LatLng): NavigationRoute? {
        val json = JSONObject(response)
        
        val trip = json.optJSONObject("trip") ?: return null
        val legs = trip.optJSONArray("legs") ?: return null
        if (legs.length() == 0) return null
        
        val leg = legs.getJSONObject(0)
        val shape = leg.getString("shape")
        val routePoints = decodePolyline(shape)
        
        val summary = trip.getJSONObject("summary")
        val totalDistance = summary.getDouble("length") * 1000 // km to m
        val totalDuration = summary.getDouble("time")
        
        // Parse maneuvers
        val maneuvers = leg.getJSONArray("maneuvers")
        val steps = mutableListOf<NavigationStep>()
        
        for (i in 0 until maneuvers.length()) {
            val m = maneuvers.getJSONObject(i)
            val type = m.getInt("type")
            val instruction = m.optString("instruction", "Continue")
            val streetName = m.optJSONArray("street_names")?.optString(0)
            val length = m.optDouble("length", 0.0) * 1000 // km to m
            val time = m.optDouble("time", 0.0)
            val beginShapeIndex = m.optInt("begin_shape_index", 0)
            
            val location = if (beginShapeIndex < routePoints.size) {
                routePoints[beginShapeIndex]
            } else {
                origin
            }
            
            steps.add(NavigationStep(
                instruction = instruction,
                maneuverType = valhallaTypeToManeuver(type),
                distance = length,
                duration = time,
                location = location,
                roadName = streetName
            ))
        }
        
        Log.d(TAG, "Valhalla route: ${routePoints.size} pts, ${steps.size} steps, %.1f km".format(totalDistance / 1000))
        
        return NavigationRoute(
            origin = origin,
            destination = destination,
            routePoints = routePoints,
            steps = steps,
            totalDistance = totalDistance,
            totalDuration = totalDuration,
            routeName = leg.optString("summary", null)
        )
    }
    
    /**
     * Decode Valhalla's encoded polyline (uses precision 6).
     */
    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0
        
        while (index < encoded.length) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lat += dlat
            
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lng += dlng
            
            poly.add(LatLng(lat / 1e6, lng / 1e6))
        }
        return poly
    }
    
    /**
     * Convert Valhalla maneuver type to our ManeuverType.
     */
    private fun valhallaTypeToManeuver(type: Int): ManeuverType {
        return when (type) {
            0 -> ManeuverType.UNKNOWN
            1 -> ManeuverType.DEPART
            2 -> ManeuverType.DEPART
            3 -> ManeuverType.DEPART
            4 -> ManeuverType.ARRIVE
            5 -> ManeuverType.ARRIVE
            6 -> ManeuverType.ARRIVE
            7 -> ManeuverType.CONTINUE
            8 -> ManeuverType.CONTINUE
            9 -> ManeuverType.TURN_SLIGHT_RIGHT
            10 -> ManeuverType.TURN_RIGHT
            11 -> ManeuverType.TURN_SHARP_RIGHT
            12 -> ManeuverType.UTURN
            13 -> ManeuverType.UTURN
            14 -> ManeuverType.TURN_SHARP_LEFT
            15 -> ManeuverType.TURN_LEFT
            16 -> ManeuverType.TURN_SLIGHT_LEFT
            17, 18, 19, 20, 21, 22, 23, 24 -> ManeuverType.CONTINUE
            25, 26 -> ManeuverType.MERGE
            27 -> ManeuverType.ROUNDABOUT
            28, 29, 30, 31, 32, 33, 34, 35 -> ManeuverType.ROUNDABOUT_EXIT
            36, 37 -> ManeuverType.CONTINUE
            else -> ManeuverType.UNKNOWN
        }
    }
    
    /**
     * Parse OpenRouteService response.
     * ORS has excellent coverage in Portugal/Europe with detailed turn instructions.
     */
    private fun parseOpenRouteServiceResponse(response: String, origin: LatLng, destination: LatLng): NavigationRoute? {
        val json = JSONObject(response)
        
        val routes = json.optJSONArray("routes")
        if (routes == null || routes.length() == 0) {
            Log.e(TAG, "OpenRouteService: No routes found")
            return null
        }
        
        val route = routes.getJSONObject(0)
        val summary = route.getJSONObject("summary")
        val totalDistance = summary.getDouble("distance") // Already in meters
        val totalDuration = summary.getDouble("duration") // Already in seconds
        
        // Parse geometry (encoded polyline)
        val geometry = route.getString("geometry")
        val routePoints = decodePolyline6(geometry) // ORS uses precision 5 by default, but let's handle both
        
        // Parse segments and steps
        val segments = route.getJSONArray("segments")
        val steps = mutableListOf<NavigationStep>()
        
        var cumulativeDistance = 0.0
        
        for (i in 0 until segments.length()) {
            val segment = segments.getJSONObject(i)
            val segmentSteps = segment.getJSONArray("steps")
            
            for (j in 0 until segmentSteps.length()) {
                val step = segmentSteps.getJSONObject(j)
                val distance = step.getDouble("distance")
                val duration = step.getDouble("duration")
                val instruction = step.getString("instruction")
                val name = step.optString("name", "")
                val type = step.getInt("type")
                
                // ORS provides waypoint indices
                val waypointIndex = step.optJSONArray("way_points")?.optInt(0, 0) ?: 0
                val location = if (waypointIndex < routePoints.size) {
                    routePoints[waypointIndex]
                } else {
                    origin
                }
                
                steps.add(NavigationStep(
                    instruction = instruction,
                    maneuverType = orsTypeToManeuver(type),
                    distance = distance,
                    duration = duration,
                    location = location,
                    roadName = name.takeIf { it.isNotBlank() }
                ))
                
                cumulativeDistance += distance
            }
        }
        
        Log.d(TAG, "OpenRouteService route: ${routePoints.size} pts, ${steps.size} steps, %.1f km".format(totalDistance / 1000))
        
        return NavigationRoute(
            origin = origin,
            destination = destination,
            routePoints = routePoints,
            steps = steps,
            totalDistance = totalDistance,
            totalDuration = totalDuration,
            routeName = null
        )
    }
    
    /**
     * Decode polyline with precision 5 (OpenRouteService default).
     */
    private fun decodePolyline6(encoded: String): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0
        
        while (index < encoded.length) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lat += dlat
            
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lng += dlng
            
            // ORS uses precision 5 (1e5) instead of 6 (1e6)
            poly.add(LatLng(lat / 1e5, lng / 1e5))
        }
        return poly
    }
    
    /**
     * Convert OpenRouteService step type to our ManeuverType.
     * Reference: https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/
     */
    private fun orsTypeToManeuver(type: Int): ManeuverType {
        return when (type) {
            0 -> ManeuverType.TURN_LEFT
            1 -> ManeuverType.TURN_RIGHT
            2 -> ManeuverType.TURN_SHARP_LEFT
            3 -> ManeuverType.TURN_SHARP_RIGHT
            4 -> ManeuverType.TURN_SLIGHT_LEFT
            5 -> ManeuverType.TURN_SLIGHT_RIGHT
            6 -> ManeuverType.CONTINUE
            7 -> ManeuverType.ROUNDABOUT
            8 -> ManeuverType.ROUNDABOUT_EXIT
            9 -> ManeuverType.UTURN
            10 -> ManeuverType.ARRIVE
            11 -> ManeuverType.DEPART
            else -> ManeuverType.UNKNOWN
        }
    }
    
    /**
     * Get the last server error message (useful for debugging).
     */
    fun getLastError(): String? = lastServerError
}
