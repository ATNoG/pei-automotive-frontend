package pt.it.automotive.app.mqtt

/**
 * Typed callback interface for MQTT-sourced events.
 *
 * Each method corresponds to a distinct event category received over MQTT.
 * Implementors handle only the business-level semantics — all parsing,
 * validation, and routing is done by [MqttEventRouter].
 *
 * Adding a new event type:
 * 1. Add a handler method here.
 * 2. Add the corresponding topic constant to AppConfig.
 * 3. Add a routing branch in [MqttEventRouter.routeMessage].
 */
interface MqttEventListener {

    /** A speed alert was received for the user's vehicle. */
    fun onSpeedAlert()

    /** An overtaking alert was received. */
    fun onOvertakingAlert(payload: String)

    /**
     * An accident alert was received.
     *
     * @param topic   The full MQTT topic (contains target car ID suffix).
     * @param payload The raw JSON payload.
     */
    fun onAccidentAlert(topic: String, payload: String)

    /**
     * An accident-cleared notification was received.
     *
     * @param payload The raw JSON payload.
     */
    fun onAccidentCleared(payload: String)

    /**
     * An emergency-vehicle proximity alert was received.
     *
     * @param payload The raw JSON payload.
     */
    fun onEmergencyVehicleAlert(payload: String)

    /**
     * A highway entry alert was received.
     *
     * @param payload The raw JSON payload.
     */
    fun onHighwayEntryAlert(payload: String)

    /**
     * A car position update was received (for any car type).
     *
     * @param data Parsed and validated car update data.
     */
    fun onCarUpdate(data: MqttEventRouter.CarUpdateData)

    /**
     * A weather station assignment was received for the user's car.
     * Contains the nearest station's info and latest measurement.
     *
     * @param data Parsed station assignment data.
     */
    fun onStationAssignment(data: MqttEventRouter.StationAssignmentData)

    /**
     * A bulk meteo stations update was received from meteo_consumer.
     * Contains all stations with their latest measurements.
     * Used as fallback when no per-car station assignment has arrived yet.
     *
     * @param payload The raw JSON payload with stations array.
     */
    fun onMeteoStationsUpdate(payload: String)

    /** MQTT connection established successfully. */
    fun onMqttConnected()

    /** MQTT connection or subscription failed. */
    fun onMqttError(error: String)

    /**
     * A traffic jam alert was received.
     *
     * @param payload The raw JSON payload.
     */
    fun onTrafficJamAlert(payload: String)
}
