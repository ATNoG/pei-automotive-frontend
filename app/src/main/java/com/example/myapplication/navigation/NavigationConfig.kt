package com.example.myapplication.navigation

/**
 * NavigationConfig - Centralized configuration for navigation system.
 * 
 * This object provides a single source of truth for all navigation-related
 * configuration parameters, making the system easy to tune and maintain.
 * 
 * Professional approach: All magic numbers extracted to configurable constants
 * with clear documentation of their purpose and impact.
 */
object NavigationConfig {
    
    // ========== Distance Thresholds ==========
    
    /**
     * Distance threshold for considering the vehicle "at" a maneuver point (meters).
     * When vehicle is within this distance, the system advances to next step.
     * 
     * Smaller = more precise but may miss turns
     * Larger = more forgiving but may advance too early
     */
    const val MANEUVER_ARRIVAL_THRESHOLD_M = 50.0
    
    /**
     * Distance threshold for arriving at final destination (meters).
     * When vehicle is within this distance of destination, navigation completes.
     * 
     * Should be smaller than MANEUVER_ARRIVAL_THRESHOLD for precision
     */
    const val DESTINATION_ARRIVAL_THRESHOLD_M = 5.0
    
    /**
     * Distance threshold for triggering off-route detection (meters).
     * If vehicle is further than this from route, rerouting is considered.
     * 
     * Smaller = more sensitive, frequent rerouting
     * Larger = more tolerant of GPS drift
     */
    const val OFF_ROUTE_THRESHOLD_M = 30.0
    
    // ========== API Call Optimization ==========
    
    /**
     * Minimum time between route recalculations (milliseconds).
     * Prevents excessive API calls during rapid position updates.
     * 
     * PERFORMANCE CRITICAL: This is the time throttle for API calls
     * Default: 1000ms (1 second) for faster route adaptation
     */
    const val MIN_TIME_BETWEEN_RECALCULATIONS_MS = 1000L
    
    /**
     * Minimum distance the vehicle must move before triggering recalculation (meters).
     * Combined with time throttle to prevent unnecessary API calls.
     * 
     * PERFORMANCE CRITICAL: This is the distance throttle for API calls
     * Default: 10m for faster route adaptation
     * 
     * Example: Even if 1 second passed, API won't be called if car moved < 10m
     */
    const val MIN_DISTANCE_FOR_RECALCULATION_M = 10.0
    
    /**
     * Absolute minimum cooldown between any rerouting attempts (milliseconds).
     * Safety net to prevent rapid-fire rerouting in edge cases.
     */
    const val RECALCULATION_COOLDOWN_MS = 2000L
    
    // ========== Route Caching ==========
    
    /**
     * How long to keep cached routes before considering them stale (milliseconds).
     * Cached routes improve performance by avoiding duplicate API calls.
     * 
     * Default: 5 minutes (300000ms)
     * Traffic conditions don't change significantly in this timeframe
     */
    const val ROUTE_CACHE_EXPIRY_MS = 300000L
    
    /**
     * Maximum number of routes to keep in cache.
     * Prevents unbounded memory growth.
     */
    const val MAX_ROUTE_CACHE_SIZE = 20
    
    // ========== UI Update Optimization ==========
    
    /**
     * Minimum interval between trail visualization updates (milliseconds).
     * Balances visual smoothness with rendering performance.
     * 
     * Default: 0ms (no throttling)
     * Real-time updates for maximum responsiveness
     */
    const val TRAIL_UPDATE_INTERVAL_MS = 0L
    
    // ========== API Configuration ==========
    
    /**
     * Timeout for route API connections (milliseconds).
     * Prevents hanging on slow/dead servers.
     */
    const val API_CONNECT_TIMEOUT_MS = 3000
    
    /**
     * Timeout for reading route API responses (milliseconds).
     */
    const val API_READ_TIMEOUT_MS = 3000
    
    // ========== Logging & Debug ==========
    
    /**
     * Enable verbose navigation logging.
     * Set to false in production for performance.
     */
    const val VERBOSE_LOGGING = true
    
    /**
     * Log navigation state every N position updates.
     * Reduces log spam while maintaining debuggability.
     */
    const val LOG_EVERY_N_UPDATES = 5
}
