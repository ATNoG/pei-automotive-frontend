package com.example.test_app_2

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MyMapScreen(carContext: CarContext) : Screen(carContext) {

    private var speedText = "Fetching..."
    private var speedLimitText = "Fetching..."
    private val repo = CarLocationRepository(carContext)

    init {
        lifecycleScope.launch {
            while (true) {
                try {
                    val (speed, limit) = repo.getSpeedData()
                    speedText = "%.1f km/h".format(speed)
                    speedLimitText = "Limit: $limit"
                    invalidate() // refresh UI
                } catch (e: Exception) {
                    speedText = "Unavailable"
                    speedLimitText = "Unavailable"
                }
                delay(2000)
            }
        }
    }

    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("Speed").addText(speedText).build())
            .addRow(Row.Builder().setTitle("Speed Limit").addText(speedLimitText).build())
            .build()

        val mapActionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Recenter")
                    .setOnClickListener { /* Let system handle camera recentering */ }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(mapActionStrip)
            .build()
    }
}