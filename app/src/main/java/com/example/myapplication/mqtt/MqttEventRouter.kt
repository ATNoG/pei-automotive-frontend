package com.example.myapplication.mqtt

import android.util.Log
import com.example.myapplication.AlertNotificationManager
import com.example.myapplication.BuildConfig
import com.example.myapplication.MqttManager
import com.example.myapplication.config.AlertPreferenceManager
import com.example.myapplication.config.AppConfig
import org.json.JSONObject

/**
 * MqttEventRouter — Routes incoming MQTT messages to typed event handlers.
 *
 * Responsibilities:
 * - Parse raw MQTT topic + payload into structured events
 * - Route to appropriate handler methods on [MqttEventListener]
 * - Isolate message protocol/format details from business logic
 * - Validate and sanitize incoming data
 * - Gate alerts through [AlertNotificationManager.shouldProcessAlert]
 *
 * Adding a new event type:
 * 1. Add the topic constant to [AppConfig]
 * 2. Add a handler method to [MqttEventListener]
 * 3. Add a routing branch in [routeMessage]
 */
class MqttEventRouter(
    private val mqttManager: MqttManager,
    private val alertNotificationManager: AlertNotificationManager,
    private val userCarIds: Set<String>
) {

    companion object {
        private const val TAG = "MqttEventRouter"
    }

    private var listener: MqttEventListener? = null

    // ── Public API ───────────────────────────────────────────────────────

    fun setListener(listener: MqttEventListener) {
        this.listener = listener
    }

    /**
     * Connect to the broker, wire the message callback, and subscribe to
     * all required topics.
     */
    fun connectAndSubscribe() {
        mqttManager.setOnMessageReceived { topic, message ->
            routeMessage(topic, message)
        }

        mqttManager.connect(
            onSuccess = {
                Log.d(TAG, "Connected to MQTT broker")
                listener?.onMqttConnected()
                subscribeToTopics()
            },
            onError = { error ->
                Log.e(TAG, "Connection failed: $error")
                listener?.onMqttError("Connection failed: $error")
            }
        )
    }

    fun disconnect() {
        mqttManager.disconnect()
    }

    // ── Routing ──────────────────────────────────────────────────────────

    /**
     * Route a single incoming MQTT message to the appropriate
     * [MqttEventListener] method. Preference-gating is applied here
     * so that the listener only sees events the user has enabled.
     */
    private fun routeMessage(topic: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Received topic=$topic len=${message.length}")
        }

        when {
            topic == AppConfig.MQTT_TOPIC_SPEED_ALERT -> {
                Log.d(TAG, "Speed alert received")
                if (isForUserCar(message) && shouldProcess(AlertPreferenceManager.AlertType.SPEEDING)) {
                    listener?.onSpeedAlert()
                    alertNotificationManager.speakForAlert(
                        AlertPreferenceManager.AlertType.SPEEDING,
                        "Warning, you are exceeding the speed limit"
                    )
                }
            }

            topic == AppConfig.MQTT_TOPIC_OVERTAKING_ALERT -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Overtaking alert received")
                if (isForUserCar(message) && shouldProcess(AlertPreferenceManager.AlertType.OVERTAKING)) {
                    listener?.onOvertakingAlert()
                    alertNotificationManager.speakForAlert(
                        AlertPreferenceManager.AlertType.OVERTAKING,
                        "Warning, vehicle overtaking"
                    )
                }
            }

            topic == AppConfig.MQTT_TOPIC_ACCIDENT_CLEARED -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Accident cleared")
                listener?.onAccidentCleared(message)
            }

            topic.startsWith(AppConfig.MQTT_TOPIC_ACCIDENT_ALERT) || topic.contains("accident") -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Accident alert on $topic")
                if (shouldProcess(AlertPreferenceManager.AlertType.ACCIDENT)) {
                    listener?.onAccidentAlert(topic, message)
                }
            }

            topic == AppConfig.MQTT_TOPIC_EV_ALERT -> {
                Log.d(TAG, "Emergency vehicle alert")
                if (isForUserCar(message) && shouldProcess(AlertPreferenceManager.AlertType.EMERGENCY_VEHICLE)) {
                    listener?.onEmergencyVehicleAlert(message)
                }
            }

            topic == AppConfig.MQTT_TOPIC_CAR_UPDATES -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Car update received")
                try {
                    val json = JSONObject(message)
                    val data = parseCarUpdate(json)
                    listener?.onCarUpdate(data)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing car update: ${e.message}")
                }
            }
        }
    }

    // ── Subscriptions ────────────────────────────────────────────────────

    private fun subscribeToTopics() {
        subscribe(AppConfig.MQTT_TOPIC_ALERTS)
        subscribe(AppConfig.MQTT_TOPIC_CAR_UPDATES)
        subscribe(AppConfig.MQTT_TOPIC_ACCIDENT_CLEARED)

        userCarIds.forEach { carId ->
            subscribe("${AppConfig.MQTT_TOPIC_ACCIDENT_ALERT}/$carId")
        }
    }

    private fun subscribe(topic: String) {
        mqttManager.subscribe(
            topic,
            onSuccess = { Log.d(TAG, "Subscribed to $topic") },
            onError = { error -> Log.e(TAG, "Subscribe failed ($topic): $error") }
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun shouldProcess(type: AlertPreferenceManager.AlertType): Boolean =
        alertNotificationManager.shouldProcessAlert(type)

    /**
     * Check if an alert payload is targeted at one of our user cars.
     * Inspects common fields: car_id, target_car_id, regular_car_id.
     * Returns true if no car identifier is found (broadcast alert) or
     * if the identifier matches one of our [userCarIds].
     */
    private fun isForUserCar(message: String): Boolean {
        return try {
            val json = JSONObject(message)
            val carId = json.optString("car_id", "")
            val targetCarId = json.optString("target_car_id", "")
            val regularCarId = json.optString("regular_car_id", "")

            val id = carId.ifEmpty { targetCarId.ifEmpty { regularCarId } }
            if (id.isEmpty()) return true // No car filter in payload — treat as broadcast
            id in userCarIds
        } catch (e: Exception) {
            true // Parse failure — don't drop the alert
        }
    }

    // ── Car Update Parsing ───────────────────────────────────────────────

    /**
     * Structured result of parsing a car-position MQTT payload.
     *
     * Supports Digital Twin nested format, Ditto features wrapper,
     * and flat JSON format.
     */
    data class CarUpdateData(
        val carId: String,
        val latitude: Double,
        val longitude: Double,
        val speedKmh: Double,
        val headingDeg: Float,
        val speedLimitKmh: Int? = null
    )

    /**
     * Parse a car update message from any supported MQTT payload format.
     */
    private fun parseCarUpdate(json: JSONObject): CarUpdateData {
        val pf = AppConfig.PayloadFormat

        var carId = json.optString(pf.CAR_ID_KEY, "")
        var lat = 0.0
        var lon = 0.0
        var speed = 0.0
        var heading = 0.0f
        var speedLimit: Int? = null

        // 1. Try Digital Twin Ditto nested format
        val gpsFeature = json.optJSONObject(pf.GPS_FEATURE_KEY)
        if (gpsFeature != null) {
            gpsFeature.optJSONObject(pf.PROPERTIES_KEY)?.let { props ->
                lat = props.optDouble(pf.LATITUDE_KEY, 0.0)
                lon = props.optDouble(pf.LONGITUDE_KEY, 0.0)
                speed = props.optDouble(pf.SPEED_KMH_KEY, props.optDouble(pf.SPEED_KEY, 0.0))
                heading = props.optDouble(pf.HEADING_DEG_KEY, props.optDouble(pf.HEADING_KEY, 0.0)).toFloat()
                if (!props.isNull(pf.SPEED_LIMIT_KMH_KEY)) {
                    speedLimit = props.optDouble(pf.SPEED_LIMIT_KMH_KEY, -1.0).takeIf { it > 0 }?.toInt()
                }
                if (carId.isEmpty()) {
                    carId = extractCarIdFromThingId(json, pf)
                }
            }
        }

        // 2. Try features wrapper (Ditto thing format)
        val features = json.optJSONObject(pf.FEATURES_KEY)
        if (features != null && lat == 0.0) {
            features.optJSONObject(pf.GPS_FEATURE_KEY)?.optJSONObject(pf.PROPERTIES_KEY)?.let { props ->
                lat = props.optDouble(pf.LATITUDE_KEY, 0.0)
                lon = props.optDouble(pf.LONGITUDE_KEY, 0.0)
                speed = props.optDouble(pf.SPEED_KMH_KEY, props.optDouble(pf.SPEED_KEY, 0.0))
                heading = props.optDouble(pf.HEADING_DEG_KEY, props.optDouble(pf.HEADING_KEY, 0.0)).toFloat()
                if (speedLimit == null && !props.isNull(pf.SPEED_LIMIT_KMH_KEY)) {
                    speedLimit = props.optDouble(pf.SPEED_LIMIT_KMH_KEY, -1.0).takeIf { it > 0 }?.toInt()
                }
            }
            if (carId.isEmpty()) {
                carId = extractCarIdFromThingId(json, pf)
            }
        }

        // 3. Fallback to flat format
        if (lat == 0.0 && lon == 0.0) {
            lat = json.optDouble(pf.LATITUDE_KEY, 0.0)
            lon = json.optDouble(pf.LONGITUDE_KEY, 0.0)
            speed = json.optDouble(pf.SPEED_KMH_KEY, 0.0)
            heading = json.optDouble(pf.HEADING_DEG_KEY, 0.0).toFloat()
        }
        if (speedLimit == null && !json.isNull(pf.SPEED_LIMIT_KMH_KEY)) {
            speedLimit = json.optDouble(pf.SPEED_LIMIT_KMH_KEY, -1.0).takeIf { it > 0 }?.toInt()
        }

        if (carId.isEmpty()) carId = "main-car"

        return CarUpdateData(carId, lat, lon, speed, heading, speedLimit)
    }

    private fun extractCarIdFromThingId(json: JSONObject, pf: AppConfig.PayloadFormat): String =
        json.optString(pf.THING_ID_KEY, "main-car")
            .substringAfterLast(":")
            .substringAfterLast("/")
            .ifEmpty { "main-car" }
}
