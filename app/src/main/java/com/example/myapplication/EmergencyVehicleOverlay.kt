package com.example.myapplication

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * EmergencyVehicleOverlay - animated notification widget for emergency vehicle proximity alerts.
 *
 * States:
 *  - HIDDEN: not visible at all (no EV in range)
 *  - COLLAPSED: small rounded square with ambulance icon (EV in range, idle)
 *  - EXPANDED: rectangle showing alert message (new alert received), collapses on click
 *
 * Click toggles between COLLAPSED and EXPANDED.
 */
class EmergencyVehicleOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "EVOverlay"
        
        // Sizes in dp
        private const val COLLAPSED_SIZE_DP = 60
        private const val EXPANDED_WIDTH_DP = 280
        private const val EXPANDED_HEIGHT_DP = 72
        private const val ICON_SIZE_DP = 45
        private const val CORNER_RADIUS_DP = 16
        
        // Timing
        private const val EXPAND_DURATION_MS = 350L
        private const val COLLAPSE_DURATION_MS = 300L
    }

    private enum class State { HIDDEN, COLLAPSED, EXPANDED }

    private var currentState = State.HIDDEN
    private var isAnimating = false
    
    // Track if the user manually collapsed — don't auto-expand on new alerts from same EV
    private var userCollapsed = false
    
    // Store last alert data for re-expanding on click
    private var lastMessage: String = "Emergency vehicle nearby"
    private var lastDistStr: String = ""
    
    // Child views
    private val iconView: ImageView
    private val messageContainer: LinearLayout
    private val messageText: TextView
    private val distanceText: TextView
    
    // Background drawable for dynamic corner radius / color
    private val bgDrawable: GradientDrawable

    init {
        // Setup background
        bgDrawable = GradientDrawable().apply {
            cornerRadius = dpToPx(CORNER_RADIUS_DP).toFloat()
            setColor(Color.parseColor("#E61B1B2F"))   // dark semi-transparent
            setStroke(dpToPx(2), Color.parseColor("#FF4444"))
        }
        background = bgDrawable
        clipToPadding = false
        clipChildren = false

        // Collapsed size initially
        val collapsedPx = dpToPx(COLLAPSED_SIZE_DP)
        layoutParams = LayoutParams(collapsedPx, collapsedPx)
        visibility = View.GONE
        elevation = dpToPx(8).toFloat()

        // --- Ambulance icon (always visible) ---
        iconView = ImageView(context).apply {
            val iconPx = dpToPx(ICON_SIZE_DP)
            layoutParams = FrameLayout.LayoutParams(iconPx, iconPx).apply {
                gravity = Gravity.CENTER
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            // Will be set to the ambulance PNG provided by the user
            setImageResource(R.drawable.ic_ev_ambulance)
        }
        addView(iconView)

        // --- Message container (only visible when expanded) ---
        messageContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dpToPx(COLLAPSED_SIZE_DP+5)   // offset past the icon area
                marginEnd = dpToPx(12)
            }
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }

        messageText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
            text = "Emergency vehicle nearby"
        }
        messageContainer.addView(messageText)

        distanceText = TextView(context).apply {
            setTextColor(Color.parseColor("#FFAAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            text = ""
        }
        messageContainer.addView(distanceText)

        addView(messageContainer)

        // --- Click listener: toggle between collapsed and expanded ---
        setOnClickListener {
            if (isAnimating) return@setOnClickListener
            when (currentState) {
                State.EXPANDED -> {
                    userCollapsed = true
                    collapseToIcon()
                }
                State.COLLAPSED -> {
                    userCollapsed = false
                    expandWithMessage(lastMessage, lastDistStr)
                }
                State.HIDDEN -> { /* no-op */ }
            }
        }
        isClickable = true
        isFocusable = true
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Show alert with message from backend.
     * If already visible (collapsed), expand with new message.
     * If hidden, appear collapsed first then expand.
     */
    fun showAlert(evId: String, distanceM: Double) {
        val distStr = if (distanceM < 1000) {
            "${distanceM.toInt()}m"
        } else {
            String.format("%.1f km", distanceM / 1000)
        }

        val msg = "Emergency vehicle nearby"
        Log.d(TAG, "showAlert: evId=$evId dist=$distStr state=$currentState")

        // Always store latest data for click-to-toggle
        lastMessage = msg
        lastDistStr = distStr

        when (currentState) {
            State.HIDDEN -> {
                // First appearance: show collapsed, then expand once
                userCollapsed = false
                showCollapsed {
                    postDelayed({
                        expandWithMessage(msg, distStr)
                    }, 200)
                }
            }
            State.COLLAPSED -> {
                // Just update stored data silently — user controls expand/collapse
                Log.d(TAG, "Collapsed, updating data silently")
            }
            State.EXPANDED -> {
                // Already expanded — update text in-place
                messageText.text = msg
                distanceText.text = "$distStr away"
            }
        }
    }

    /**
     * Update only the distance text without triggering any expand/collapse.
     * Called from live position updates for real-time distance.
     */
    fun updateDistance(distanceM: Double) {
        val distStr = if (distanceM < 1000) {
            "${distanceM.toInt()}m"
        } else {
            String.format("%.1f km", distanceM / 1000)
        }
        lastDistStr = distStr
        if (currentState == State.EXPANDED) {
            distanceText.text = "$distStr away"
        }
    }

    /**
     * Called when the emergency vehicle leaves the radius – hides completely.
     */
    fun dismiss() {
        Log.d(TAG, "dismiss")
        userCollapsed = false
        animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(COLLAPSE_DURATION_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = View.GONE
                    currentState = State.HIDDEN
                    alpha = 1f
                    scaleX = 1f
                    scaleY = 1f
                    // Reset to collapsed size
                    val lp = layoutParams
                    val collapsedPx = dpToPx(COLLAPSED_SIZE_DP)
                    lp.width = collapsedPx
                    lp.height = collapsedPx
                    layoutParams = lp
                    messageContainer.visibility = View.GONE
                }
            })
            .start()
    }

    /**
     * Quick check if currently showing (collapsed or expanded).
     */
    fun isShowing(): Boolean = currentState != State.HIDDEN

    // =========================================================================
    // Internal animations
    // =========================================================================

    private fun showCollapsed(onComplete: (() -> Unit)? = null) {
        val collapsedPx = dpToPx(COLLAPSED_SIZE_DP)
        val lp = layoutParams
        lp.width = collapsedPx
        lp.height = collapsedPx
        layoutParams = lp
        messageContainer.visibility = View.GONE

        alpha = 0f
        scaleX = 0.5f
        scaleY = 0.5f
        visibility = View.VISIBLE

        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(EXPAND_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentState = State.COLLAPSED
                    onComplete?.invoke()
                }
            })
            .start()
    }

    private fun expandWithMessage(msg: String, distStr: String) {
        if (isAnimating) return
        isAnimating = true
        
        messageText.text = msg
        distanceText.text = "$distStr away"

        val collapsedPx = dpToPx(COLLAPSED_SIZE_DP)
        val expandedW = dpToPx(EXPANDED_WIDTH_DP)
        val expandedH = dpToPx(EXPANDED_HEIGHT_DP)

        // Animate width
        val widthAnim = ValueAnimator.ofInt(layoutParams.width, expandedW).apply {
            addUpdateListener { anim ->
                val lp = layoutParams
                lp.width = anim.animatedValue as Int
                layoutParams = lp
            }
        }

        // Animate height
        val heightAnim = ValueAnimator.ofInt(layoutParams.height, expandedH).apply {
            addUpdateListener { anim ->
                val lp = layoutParams
                lp.height = anim.animatedValue as Int
                layoutParams = lp
            }
        }

        // Move icon to left-center
        val iconLp = iconView.layoutParams as FrameLayout.LayoutParams
        iconLp.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        iconLp.marginStart = dpToPx(12)
        iconView.layoutParams = iconLp

        val set = AnimatorSet()
        set.playTogether(widthAnim, heightAnim)
        set.duration = EXPAND_DURATION_MS
        set.interpolator = AccelerateDecelerateInterpolator()
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Show message text with fade-in
                messageContainer.alpha = 0f
                messageContainer.visibility = View.VISIBLE
                messageContainer.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .setListener(null)   // clear any leftover listener from collapseToIcon
                    .start()
                
                currentState = State.EXPANDED
                isAnimating = false
            }
        })
        set.start()
    }

    private fun collapseToIcon() {
        if (isAnimating) return
        isAnimating = true
        
        val collapsedPx = dpToPx(COLLAPSED_SIZE_DP)

        // Fade out message first
        messageContainer.animate()
            .alpha(0f)
            .setDuration(150)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    messageContainer.animate().setListener(null)   // detach so it doesn't re-fire
                    messageContainer.visibility = View.GONE
                    
                    // Move icon back to center
                    val iconLp = iconView.layoutParams as FrameLayout.LayoutParams
                    iconLp.gravity = Gravity.CENTER
                    iconLp.marginStart = 0
                    iconView.layoutParams = iconLp

                    // Animate size back to collapsed
                    val widthAnim = ValueAnimator.ofInt(layoutParams.width, collapsedPx).apply {
                        addUpdateListener { anim ->
                            val lp = layoutParams
                            lp.width = anim.animatedValue as Int
                            layoutParams = lp
                        }
                    }
                    val heightAnim = ValueAnimator.ofInt(layoutParams.height, collapsedPx).apply {
                        addUpdateListener { anim ->
                            val lp = layoutParams
                            lp.height = anim.animatedValue as Int
                            layoutParams = lp
                        }
                    }

                    val set = AnimatorSet()
                    set.playTogether(widthAnim, heightAnim)
                    set.duration = COLLAPSE_DURATION_MS
                    set.interpolator = AccelerateDecelerateInterpolator()
                    set.addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            currentState = State.COLLAPSED
                            isAnimating = false
                        }
                    })
                    set.start()
                }
            })
            .start()
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
