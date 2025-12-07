package com.example.myapplication

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
    private val brokerAddress: String,
    private val brokerPort: Int
) {
    private val clientId = "android-${UUID.randomUUID()}"
    private val serverUri = "tcp://$brokerAddress:$brokerPort"
    private var client: MqttClient? = null
    private var onMessageReceived: ((topic: String, message: String) -> Unit)? = null

    fun connect(onSuccess: () -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                client = MqttClient(serverUri, clientId, null)
                client?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.w("MQTT", "Connection lost: ${cause?.message}")
                    }

                    override fun messageArrived(topic: String, message: MqttMessage) {
                        val payload = String(message.payload)
                        onMessageReceived?.invoke(topic, payload)
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 30
                    keepAliveInterval = 60
                }

                client?.connect(options)
                onSuccess()
            } catch (e: Exception) {
                Log.e("MQTT", "Connection failed: ${e.message}")
                onError(e.message ?: "Unknown error")
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
}