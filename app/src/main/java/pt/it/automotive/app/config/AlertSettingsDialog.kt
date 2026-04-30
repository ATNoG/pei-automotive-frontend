package pt.it.automotive.app.config

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import pt.it.automotive.app.MapController
import pt.it.automotive.app.R
import pt.it.automotive.app.auth.LoginActivity
import pt.it.automotive.app.auth.TokenStore
import pt.it.automotive.app.preferences.AlertCategory
import pt.it.automotive.app.preferences.PreferencesSectionType
import pt.it.automotive.app.preferences.PreferencesSectionUpdate

/**
 * Encapsulates the Settings dialog UI and alert preference wiring.
 *
 * Displays alert types in a 2-column grid, each in its own bordered card
 * with the event name plus explicit sound and enable switches.
 */
class AlertSettingsDialog(
    private val activity: AppCompatActivity,
    private val alertPreferenceManager: AlertPreferenceManager,
    private val mapController: MapController,
    private val onPreferenceSectionChanged: ((PreferencesSectionUpdate) -> Unit)? = null,
    private val onDialogClosed: ((Set<PreferencesSectionType>) -> Unit)? = null,
    private val onLogout: (() -> Unit)? = null
) {

    private var currentSettingsView: View? = null

    private companion object {
        const val MODAL_SIDE_MARGIN_DP = 24
        const val MODAL_VERTICAL_MARGIN_DP = 36
        const val MODAL_TARGET_WIDTH_DP = 640
        const val MODAL_MAX_HEIGHT_RATIO = 0.70f
        const val GRID_COLUMNS_TABLET = 2
        const val GRID_GAP_DP = 10
        const val CARD_PADDING_DP = 14
        const val CONTROL_GAP_DP = 16
        const val CARD_TITLE_BOTTOM_GAP_DP = 16
    }

    private val changedSections = mutableSetOf<PreferencesSectionType>()

    fun show() {
        if (currentSettingsView != null) return
        changedSections.clear()

        val settingsView = activity.layoutInflater.inflate(R.layout.dialog_settings, null)
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        currentSettingsView = settingsView
        configureModalBounds(settingsView)
        rootView.addView(settingsView)

        setupMapStyleToggle(settingsView)
        setupColorBlindToggle(settingsView)
        buildAlertGrid(settingsView)
        setupLogoutButton(settingsView)
        setupCloseActions(settingsView, rootView)
    }

    /**
     * Keep the settings card as a centered modal (not full-screen) across displays.
     * Tuned to remain comfortable on 1024x768 @ 160 dpi while scaling to other sizes.
     */
    private fun configureModalBounds(settingsView: View) {
        val card = settingsView.findViewById<View>(R.id.settingsCard) ?: return
        val metrics = activity.resources.displayMetrics
        val density = metrics.density
        val sideMarginPx = activity.resources.getDimensionPixelSize(R.dimen.dialog_margin)
        val verticalMarginPx = (MODAL_VERTICAL_MARGIN_DP * density).toInt()
        val targetWidthPx = activity.resources.getDimensionPixelSize(R.dimen.dialog_width)

        val maxWidth = (metrics.widthPixels - (sideMarginPx * 2)).coerceAtLeast(sideMarginPx)
        val maxHeightByRatio = (metrics.heightPixels * MODAL_MAX_HEIGHT_RATIO).toInt()
        val maxHeightByMargins = (metrics.heightPixels - (verticalMarginPx * 2)).coerceAtLeast(verticalMarginPx)
        val modalHeight = minOf(maxHeightByRatio, maxHeightByMargins)

        (card.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.width = minOf(targetWidthPx, maxWidth)
            lp.height = modalHeight
            lp.gravity = Gravity.CENTER
            lp.topMargin = verticalMarginPx
            lp.bottomMargin = verticalMarginPx
            card.layoutParams = lp
        }
    }

    // ── Map Style ────────────────────────────────────────────────────────

    private fun setupMapStyleToggle(settingsView: View) {
        val switchLightMode = settingsView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchLightMode)
        switchLightMode.scaleX = 1.2f
        switchLightMode.scaleY = 1.2f
        val prefs = activity.getSharedPreferences("AppSettings", AppCompatActivity.MODE_PRIVATE)
        switchLightMode.isChecked = prefs.getBoolean("lightMode", false)
        switchLightMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("lightMode", isChecked).apply()
            onPreferenceSectionChanged?.invoke(
                PreferencesSectionUpdate.AppearanceUpdate(darkMode = !isChecked)
            )
            changedSections.add(PreferencesSectionType.APPEARANCE)
            val mainActivity = activity as? pt.it.automotive.app.MainActivity
            mainActivity?.applyTheme(!isChecked)
        }
    }
            
    // ── Colorblind Mode ──────────────────────────────────────────────────

    private fun setupColorBlindToggle(settingsView: View) {
        val switchColorBlind = settingsView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchColorBlind)
        switchColorBlind.scaleX = 1.2f
        switchColorBlind.scaleY = 1.2f
        val prefs = activity.getSharedPreferences("AppSettings", AppCompatActivity.MODE_PRIVATE)
        switchColorBlind.isChecked = prefs.getBoolean("colorBlindMode", false)
        switchColorBlind.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("colorBlindMode", isChecked).apply()
            onPreferenceSectionChanged?.invoke(
                PreferencesSectionUpdate.AppearanceUpdate(colorblindEnabled = isChecked)
            )
            changedSections.add(PreferencesSectionType.APPEARANCE)
        }
    }

    // ── Alert Grid (2-column) ────────────────────────────────────────────

    private fun buildAlertGrid(settingsView: View) {
        val container = settingsView.findViewById<LinearLayout>(R.id.alertSettingsContainer)
        container.removeAllViews()
        val density = activity.resources.displayMetrics.density
        val gapPx = (GRID_GAP_DP * density).toInt()
        val gridColumns = if (activity.resources.configuration.smallestScreenWidthDp >= 600) {
            GRID_COLUMNS_TABLET
        } else {
            1
        }

        val alertTypes = listOf(
            AlertPreferenceManager.AlertType.ACCIDENT,
            AlertPreferenceManager.AlertType.HIGHWAY_ENTRY,
            AlertPreferenceManager.AlertType.TRAFFIC_JAM,
            AlertPreferenceManager.AlertType.SPEEDING,
            AlertPreferenceManager.AlertType.WEATHER,
            AlertPreferenceManager.AlertType.OVERTAKING,
            AlertPreferenceManager.AlertType.EMERGENCY_VEHICLE,
            AlertPreferenceManager.AlertType.NAVIGATION
        )
        val rows = alertTypes.chunked(gridColumns)

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
            if (rowItems.size < gridColumns) {
                for (i in rowItems.size until gridColumns) {
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
     * Layout: title on top, then two toggle blocks on the next row.
     */
    private fun createAlertCard(
        alertType: AlertPreferenceManager.AlertType,
        density: Float
    ): LinearLayout {
        val padPx = (CARD_PADDING_DP * density).toInt()
        val controlGapPx = (CONTROL_GAP_DP * density).toInt()
        val titleBottomGapPx = (CARD_TITLE_BOTTOM_GAP_DP * density).toInt()

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padPx, padPx, padPx, padPx)
            background = activity.getDrawable(R.drawable.card_background_with_stroke)
        }

        val nameText = TextView(activity).apply {
            text = activity.getString(alertType.displayNameResId)
            textSize = 28f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = titleBottomGapPx
            }
        }

        val controlsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val soundControl = createToggleBlock(
            labelRes = R.string.alert_sound,
            initialChecked = alertPreferenceManager.isAudioEnabled(alertType),
            marginEndPx = controlGapPx
        ) { checked ->
            alertPreferenceManager.setAudioEnabled(alertType, checked)
            onPreferenceSectionChanged?.invoke(
                PreferencesSectionUpdate.AlertUpdate(
                    category = alertType.toAlertCategory(),
                    audio = checked
                )
            )
            changedSections.add(PreferencesSectionType.ALERTS)
        }

        val enableControl = createToggleBlock(
            labelRes = R.string.alert_enabled,
            initialChecked = alertPreferenceManager.isEnabled(alertType),
            marginEndPx = 0
        ) { checked ->
            alertPreferenceManager.setEnabled(alertType, checked)
            soundControl.second.isEnabled = checked
            onPreferenceSectionChanged?.invoke(
                PreferencesSectionUpdate.AlertUpdate(
                    category = alertType.toAlertCategory(),
                    alert = checked
                )
            )
            changedSections.add(PreferencesSectionType.ALERTS)
        }

        soundControl.second.isEnabled = enableControl.second.isChecked

        card.addView(nameText)
        controlsRow.addView(soundControl.first)
        controlsRow.addView(enableControl.first)
        card.addView(controlsRow)
        return card
    }

    private fun createToggleBlock(
        labelRes: Int,
        initialChecked: Boolean,
        marginEndPx: Int,
        onChanged: (Boolean) -> Unit
    ): Pair<LinearLayout, SwitchCompat> {
        val label = TextView(activity).apply {
            text = activity.getString(labelRes)
            textSize = 24f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (16 * activity.resources.displayMetrics.density).toInt() }
        }

        val toggle = SwitchCompat(activity).apply {
            isChecked = initialChecked
            setOnCheckedChangeListener { _, checked -> onChanged(checked) }
            scaleX = 1.2f
            scaleY = 1.2f
        }

        val block = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = marginEndPx
            }
            addView(label)
            addView(toggle)
        }

        return block to toggle
    }

    private fun setupLogoutButton(settingsView: View) {
        val fullNameTextView = settingsView.findViewById<TextView>(R.id.fullNameTextView)
        val usernameTextView = settingsView.findViewById<TextView>(R.id.usernameTextView)
        val btnLogout = settingsView.findViewById<Button>(R.id.btnLogout)
        
        val fullName = TokenStore.getFullName(activity)
        val username = TokenStore.getUsername(activity)
        
        fullNameTextView?.text = fullName ?: activity.getString(R.string.unknown_user)
        usernameTextView?.text = username?.let { "@$it" } ?: ""

        btnLogout?.setOnClickListener {
            // 1. Clear local preferences cache so the next user starts fresh
            onLogout?.invoke()

            // 2. Clear saved tokens
            TokenStore.clear(activity)

            // 3. Redirect to LoginActivity and clear the back stack
            val intent = android.content.Intent(activity, LoginActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            activity.startActivity(intent)
            activity.finish()
        }
    }

    fun dismiss() {
        currentSettingsView?.let {
            val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
            rootView.removeView(it)
            currentSettingsView = null
        }
    }

    // ── Close Actions ────────────────────────────────────────────────────

    private fun setupCloseActions(settingsView: View, rootView: ViewGroup) {
        val dismissAction = {
            onDialogClosed?.invoke(changedSections.toSet())
            dismiss()
        }

        settingsView.findViewById<ImageButton>(R.id.btnCloseSettings)?.setOnClickListener { dismissAction() }
        settingsView.setOnClickListener { dismissAction() }

        // Prevent clicks on card from closing overlay
        settingsView.findViewById<View>(R.id.settingsCard)?.setOnClickListener { }
    }
    
    private fun AlertPreferenceManager.AlertType.toAlertCategory(): AlertCategory {
        return when (this) {
            AlertPreferenceManager.AlertType.ACCIDENT -> AlertCategory.ACCIDENT
            AlertPreferenceManager.AlertType.SPEEDING -> AlertCategory.SPEEDING
            AlertPreferenceManager.AlertType.WEATHER -> AlertCategory.WEATHER
            AlertPreferenceManager.AlertType.OVERTAKING -> AlertCategory.OVERTAKING
            AlertPreferenceManager.AlertType.EMERGENCY_VEHICLE -> AlertCategory.EMERGENCY_VEHICLE
            AlertPreferenceManager.AlertType.NAVIGATION -> AlertCategory.NAVIGATION
            AlertPreferenceManager.AlertType.HIGHWAY_ENTRY -> AlertCategory.MANEUVER
            AlertPreferenceManager.AlertType.TRAFFIC_JAM -> AlertCategory.TRAFFIC
        }
    }
}
