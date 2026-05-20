package pt.it.automotive.app

import android.content.Context
import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import java.util.UUID

class MqttManager(
    private val context: Context,
    private val brokerUrl: String,
    private val accessToken: String? = null,
    private val onConnectionLost: ((cause: Throwable?) -> Unit)? = null
) {
    private val clientId = "android-${UUID.randomUUID()}"
    private val serverUri = brokerUrl
    private var client: MqttClient? = null
    private var onMessageReceived: ((topic: String, message: String) -> Unit)? = null

    fun connect(onSuccess: () -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                client = MqttClient(serverUri, clientId, null)
                client?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.w("MQTT", "Connection lost: ${cause?.message}")
                        onConnectionLost?.invoke(cause)
                    }

                    override fun messageArrived(topic: String, message: MqttMessage) {
                        val payload = String(message.payload)
                        onMessageReceived?.invoke(topic, payload)
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                client?.connect(buildOptions(accessToken))
                onSuccess()
            } catch (e: Exception) {
                Log.e("MQTT", "Connection failed: ${e.message}")
                onError(e.message ?: "Unknown error")
            }
        }.start()
    }

    // Reconnect with a freshly obtained token after a connectionLost event.
    fun reconnect(newToken: String?, onSuccess: () -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                client?.connect(buildOptions(newToken))
                onSuccess()
            } catch (e: Exception) {
                Log.e("MQTT", "Reconnect failed: ${e.message}")
                onError(e.message ?: "Reconnect error")
            }
        }.start()
    }

    fun subscribe(topic: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                client?.subscribe(topic, 1)
                onSuccess()
            } catch (e: Exception) {
                Log.e("MQTT", "Subscribe failed: ${e.message}")
                onError(e.message ?: "Unknown error")
            }
        }.start()
    }

    fun publish(topic: String, message: String) {
        Thread {
            try {
                val msg = MqttMessage(message.toByteArray())
                msg.qos = 1
                client?.publish(topic, msg)
            } catch (e: Exception) {
                Log.e("MQTT", "Publish failed: ${e.message}")
            }
        }.start()
    }

    fun unsubscribe(topic: String, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        Thread {
            try {
                client?.unsubscribe(topic)
                onSuccess()
            } catch (e: Exception) {
                Log.e("MQTT", "Unsubscribe failed: ${e.message}")
                onError(e.message ?: "Unknown error")
            }
        }.start()
    }

    fun disconnect() {
        Thread {
            try {
                client?.disconnect()
                client?.close()
            } catch (e: Exception) {
                Log.e("MQTT", "Disconnect error: ${e.message}")
            }
        }.start()
    }

    fun setOnMessageReceived(callback: (topic: String, message: String) -> Unit) {
        onMessageReceived = callback
    }

    fun isConnected(): Boolean = client?.isConnected ?: false

    private fun buildOptions(token: String?): MqttConnectOptions =
        MqttConnectOptions().apply {
            // Automatic reconnect is disabled: a dropped connection means the JWT
            // may have expired, so the caller must refresh the token and call
            // reconnect() explicitly with the new one.
            isAutomaticReconnect = false
            isCleanSession = true
            connectionTimeout = 30
            keepAliveInterval = 60
            if (token != null) {
                userName = token
                password = "jwt".toCharArray()
            }
        }
}
