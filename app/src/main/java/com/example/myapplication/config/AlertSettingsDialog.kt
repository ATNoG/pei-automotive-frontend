package com.example.myapplication.config

import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.MapController
import com.example.myapplication.R

/**
 * Encapsulates the Settings dialog UI and alert preference wiring.
 *
 * Displays alert types in a 2-column grid, each in its own bordered card
 * with the event name, audio toggle, and enable switch on the same row.
 */
class AlertSettingsDialog(
    private val activity: AppCompatActivity,
    private val alertPreferenceManager: AlertPreferenceManager,
    private val mapController: MapController
) {

    private companion object {
        const val GRID_COLUMNS = 2
        const val GRID_GAP_DP = 10
        const val CARD_PADDING_DP = 14
    }

    fun show() {
        val settingsView = activity.layoutInflater.inflate(R.layout.dialog_settings, null)
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        rootView.addView(settingsView)

        setupMapStyleToggle(settingsView)
        buildAlertGrid(settingsView)
        setupCloseActions(settingsView, rootView)
    }

    // ── Map Style ────────────────────────────────────────────────────────

    private fun setupMapStyleToggle(settingsView: View) {
        val switchLightMode = settingsView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchLightMode)
        val prefs = activity.getSharedPreferences("AppSettings", AppCompatActivity.MODE_PRIVATE)
        switchLightMode.isChecked = prefs.getBoolean("lightMode", false)
        switchLightMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("lightMode", isChecked).apply()
            mapController.setMapStyle(isChecked)
        }
    }

    // ── Alert Grid (2-column) ────────────────────────────────────────────

    private fun buildAlertGrid(settingsView: View) {
        val container = settingsView.findViewById<LinearLayout>(R.id.alertSettingsContainer)
        val density = activity.resources.displayMetrics.density
        val gapPx = (GRID_GAP_DP * density).toInt()

        val alertTypes = AlertPreferenceManager.AlertType.entries
        val rows = alertTypes.chunked(GRID_COLUMNS)

        rows.forEachIndexed { rowIndex, rowItems ->
            val rowLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (rowIndex > 0) topMargin = gapPx
                }
            }

            rowItems.forEachIndexed { colIndex, alertType ->
                val card = createAlertCard(alertType, density)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                if (colIndex > 0) lp.marginStart = gapPx
                card.layoutParams = lp
                rowLayout.addView(card)
            }

            // Pad incomplete last row with spacer
            if (rowItems.size < GRID_COLUMNS) {
                for (i in rowItems.size until GRID_COLUMNS) {
                    val spacer = View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 0, 1f).apply {
                            marginStart = gapPx
                        }
                    }
                    rowLayout.addView(spacer)
                }
            }

            container.addView(rowLayout)
        }
    }

    /**
     * Create a single bordered card for one alert type.
     * Layout: [EventName]  [🔊]  [Switch]
     */
    private fun createAlertCard(
        alertType: AlertPreferenceManager.AlertType,
        density: Float
    ): LinearLayout {
        val padPx = (CARD_PADDING_DP * density).toInt()

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(padPx, padPx, padPx, padPx)
            background = activity.getDrawable(R.drawable.card_background_with_stroke)
        }

        // Event name
        val nameText = TextView(activity).apply {
            text = alertType.displayName
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        // Audio toggle emoji
        val audioToggle = createAudioToggle(alertType, density)

        // Enable switch
        val enableSwitch = createEnableSwitch(alertType, audioToggle)

        card.addView(nameText)
        card.addView(audioToggle)
        card.addView(enableSwitch)
        return card
    }

    private fun createAudioToggle(
        alertType: AlertPreferenceManager.AlertType,
        density: Float
    ): TextView {
        return TextView(activity).apply {
            text = "\uD83D\uDD0A"
            textSize = 18f
            setPadding(
                (8 * density).toInt(), (4 * density).toInt(),
                (8 * density).toInt(), (4 * density).toInt()
            )
            visibility = if (alertPreferenceManager.isEnabled(alertType)) View.VISIBLE else View.INVISIBLE
            alpha = if (alertPreferenceManager.isAudioEnabled(alertType)) 1.0f else 0.3f
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val newState = !alertPreferenceManager.isAudioEnabled(alertType)
                alertPreferenceManager.setAudioEnabled(alertType, newState)
                this.alpha = if (newState) 1.0f else 0.3f
            }
        }
    }

    private fun createEnableSwitch(
        alertType: AlertPreferenceManager.AlertType,
        audioToggle: TextView
    ): androidx.appcompat.widget.SwitchCompat {
        return androidx.appcompat.widget.SwitchCompat(activity).apply {
            isChecked = alertPreferenceManager.isEnabled(alertType)
            setOnCheckedChangeListener { _, checked ->
                alertPreferenceManager.setEnabled(alertType, checked)
                audioToggle.visibility = if (checked) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    // ── Close Actions ────────────────────────────────────────────────────

    private fun setupCloseActions(settingsView: View, rootView: ViewGroup) {
        val dismiss = { rootView.removeView(settingsView) }

        settingsView.findViewById<ImageButton>(R.id.btnCloseSettings)?.setOnClickListener { dismiss() }
        settingsView.setOnClickListener { dismiss() }

        // Prevent clicks on card from closing overlay
        settingsView.findViewById<View>(R.id.settingsCard)?.setOnClickListener { }
    }
}
