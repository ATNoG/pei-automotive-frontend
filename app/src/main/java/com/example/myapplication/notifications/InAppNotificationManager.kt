package com.example.myapplication.notifications

import com.example.myapplication.R

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.annotation.AttrRes
import java.util.Locale

/**
 * InAppNotificationManager — unified in-app notification system.
 *
 * Replaces all disparate notification mechanisms (native AAOS notifications, Toast,
 * accident banner, arrival popup) with a single consistent UI component that:
 *   - Appears at the top-center of the screen
 *   - Slides in from above with a smooth decelerate animation
 *   - Can be dismissed by swiping left or right
 *   - Auto-dismisses after a configurable duration
 *   - Queues multiple notifications and shows them sequentially
 *   - Follows the existing dark card design language
 *
 * Usage:
 *   notificationManager.show(Type.ACCIDENT, "⚠️ Accident Alert", "500 m ahead")
 *   notificationManager.show(Type.SUCCESS, "You have arrived!", "Navigation complete",
 *       onDismissed = ::stopNavigation)
 */
class InAppNotificationManager(private val activity: Activity) {

    // ── Notification Types ───────────────────────────────────────────────

    enum class Type(@AttrRes val accentColorAttr: Int) {
        INFO(accentColorAttr = R.attr.colorNotificationInfo),
        SUCCESS(accentColorAttr = R.attr.colorNotificationSuccess),
        WARNING(accentColorAttr = R.attr.colorNotificationWarning),
        ERROR(accentColorAttr = R.attr.colorNotificationError),
        ACCIDENT(accentColorAttr = R.attr.colorNotificationAccident),
        WEATHER(accentColorAttr = R.attr.colorNotificationWeather),
        EMERGENCY(accentColorAttr = R.attr.colorNotificationEmergency)
    }

    // ── Data ─────────────────────────────────────────────────────────────

    data class AppNotification(
        val type: Type,
        val title: String,
        val message: String? = null,
        val duration: Long = DEFAULT_DURATION_MS,
        val onDismissed: (() -> Unit)? = null,
        val tag: String? = null
    )

    // ── Constants ────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "InAppNotifMgr"
        const val DEFAULT_DURATION_MS = 5_000L
        const val SHORT_DURATION_MS = 2_500L
        const val LONG_DURATION_MS = 8_000L
        private const val ENTER_DURATION_MS = 380L
        private const val EXIT_DURATION_MS = 300L
        private const val BETWEEN_NOTIF_DELAY_MS = 250L
        private const val MAX_QUEUE_SIZE = 10

        // Swipe thresholds
        private const val SWIPE_VELOCITY_THRESHOLD = 900f  // px/s
        private const val SWIPE_DISTANCE_DP = 110
    }

    // ── State ────────────────────────────────────────────────────────────

    private val queue = ArrayDeque<AppNotification>()
    private var currentView: View? = null
    private var currentTag: String? = null
    private var isShowing = false
    private val handler = Handler(Looper.getMainLooper())
    private var autoDismissJob: Runnable? = null

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Convenience overload — enqueue a notification with individual parameters.
     */
    fun show(
        type: Type,
        title: String,
        message: String? = null,
        duration: Long = DEFAULT_DURATION_MS,
        onDismissed: (() -> Unit)? = null,
        tag: String? = null
    ) {
        enqueue(AppNotification(type, title, message, duration, onDismissed, tag))
    }

    /**
     * Show or update a tagged notification in-place.
     * If a notification with the same [tag] is currently visible, its title/message
     * are updated and the auto-dismiss timer is reset — no new banner is created.
     * If no matching notification is showing, a new one is enqueued.
     * Returns false if the tag was previously dismissed by the user (caller should skip).
     */
    fun showOrUpdate(
        tag: String,
        type: Type,
        title: String,
        message: String? = null,
        duration: Long = DEFAULT_DURATION_MS,
        onDismissed: (() -> Unit)? = null
    ): Boolean {
        if (tag in dismissedTags) return false
        handler.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            val view = currentView
            if (view != null && currentTag == tag && view.parent != null) {
                // Update in place
                view.findViewById<TextView>(R.id.notifTitle)?.text = title
                val msgView = view.findViewById<TextView>(R.id.notifMessage)
                if (!message.isNullOrBlank()) {
                    msgView?.text = message
                    msgView?.visibility = View.VISIBLE
                } else {
                    msgView?.visibility = View.GONE
                }
                // Reset auto-dismiss timer
                cancelAutoDismiss()
                val dismissRunnable = Runnable {
                    if (view.parent != null) {
                        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
                        animateOut(view, rootView, translationX = 0f, onDismissed)
                    }
                }
                autoDismissJob = dismissRunnable
                handler.postDelayed(dismissRunnable, duration)
            } else {
                enqueue(AppNotification(type, title, message, duration, {
                    dismissedTags.add(tag)
                    onDismissed?.invoke()
                }, tag))
            }
        }
        return true
    }

    /** Clear a dismissed tag so future notifications for it can appear again. */
    fun clearDismissedTag(tag: String) {
        dismissedTags.remove(tag)
    }

    /** Tags dismissed by the user — notifications with these tags are suppressed. */
    private val dismissedTags = mutableSetOf<String>()

    /**
     * Enqueue a pre-built [AppNotification] for display.
     */
    fun enqueue(notification: AppNotification) {
        handler.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            if (notification.tag != null && notification.tag in dismissedTags) return@post
            if (queue.size >= MAX_QUEUE_SIZE) {
                Log.w(TAG, "Queue full - dropping oldest notification")
                queue.removeFirstOrNull()
            }
            queue.addLast(notification)
            if (!isShowing) showNext()
        }
    }

    /**
     * Immediately dismiss the current notification (if any) and clear the queue.
     */
    fun dismissAll() {
        handler.post {
            queue.clear()
            cancelAutoDismiss()
            currentView?.let { v ->
                safeRemoveView(v)
                currentView = null
            }
            isShowing = false
        }
    }

    /** Call from Activity.onDestroy to release resources. */
    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        currentView = null
        isShowing = false
        queue.clear()
    }

    // ── Core display logic ───────────────────────────────────────────────

    private fun showNext() {
        val notification = queue.removeFirstOrNull() ?: run {
            isShowing = false
            return
        }
        isShowing = true

        if (activity.isFinishing || activity.isDestroyed) {
            isShowing = false
            return
        }

        val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
            isShowing = false
            return
        }

        val view = inflateNotificationView(notification)
        view.elevation = 9999f  // float above all other in-app UI

        val density = activity.resources.displayMetrics.density
        val widthPx = (420 * density).toInt()
        val topMarginPx = (15 * density).toInt()

        val lp = android.widget.FrameLayout.LayoutParams(
            widthPx,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            topMargin = topMarginPx
        }

        rootView.addView(view, lp)
        currentView = view
        currentTag = notification.tag

        // Measure and then animate in from above
        view.post {
            view.translationY = -(view.height + topMarginPx + 60).toFloat()
            view.alpha = 0f
            view.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(ENTER_DURATION_MS)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .setListener(null)
                .start()
        }

        // Dismiss button intentionally removed — notifications are dismissed by swipe or auto-dismiss.

        // Wire swipe-to-dismiss
        setupSwipeDismiss(view, rootView, notification)

        // Schedule auto-dismiss after enter animation + display duration
        val dismissRunnable = Runnable {
            if (view.parent != null) {
                animateOut(view, rootView, translationX = 0f, notification.onDismissed)
            }
        }
        autoDismissJob = dismissRunnable
        handler.postDelayed(dismissRunnable, notification.duration + ENTER_DURATION_MS)
    }

    // ── Swipe gesture ────────────────────────────────────────────────────

    private fun setupSwipeDismiss(view: View, rootView: ViewGroup, notification: AppNotification) {
        val density = activity.resources.displayMetrics.density
        val distanceThreshPx = SWIPE_DISTANCE_DP * density
        var isDismissing = false

        // Use a GestureDetector for fling detection alongside raw touch tracking
        val gestureDetector = GestureDetector(
            activity,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (isDismissing) return false
                    if (Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD &&
                        Math.abs(velocityX) > Math.abs(velocityY)
                    ) {
                        isDismissing = true
                        cancelAutoDismiss()
                        val exitX = Math.signum(velocityX) * 1200 * density
                        animateOut(view, rootView, exitX, notification.onDismissed)
                        return true
                    }
                    return false
                }
            }
        )

        var touchStartX = 0f
        var touchStartY = 0f
        var isHorizontalSwipe = false

        view.setOnTouchListener { v, event ->
            if (isDismissing) return@setOnTouchListener false

            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isHorizontalSwipe = false
                    // Return true only if we'll consume, decided in MOVE
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY

                    if (!isHorizontalSwipe) {
                        // Decide direction on first significant movement
                        if (Math.abs(dx) > 12 * density || Math.abs(dy) > 12 * density) {
                            isHorizontalSwipe = Math.abs(dx) > Math.abs(dy)
                        }
                    }

                    if (isHorizontalSwipe) {
                        v.translationX = dx
                        val progress = Math.abs(dx) / (400 * density)
                        v.alpha = (1f - progress).coerceIn(0.15f, 1f)
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDismissing && isHorizontalSwipe) {
                        val dx = event.rawX - touchStartX
                        if (Math.abs(dx) >= distanceThreshPx) {
                            isDismissing = true
                            cancelAutoDismiss()
                            val exitX = Math.signum(dx) * 1200 * density
                            animateOut(view, rootView, exitX, notification.onDismissed)
                        } else {
                            // Snap back to center
                            v.animate()
                                .translationX(0f)
                                .alpha(1f)
                                .setDuration(220)
                                .setInterpolator(DecelerateInterpolator())
                                .setListener(null)
                                .start()
                        }
                    }
                    false
                }

                else -> false
            }
        }
    }

    // ── Animate out ──────────────────────────────────────────────────────

    /**
     * Animate the notification view out of the screen.
     * - If [translationX] is 0, slides upward (auto-dismiss or X button).
     * - Otherwise slides sideways (swipe direction).
     */
    private fun animateOut(
        view: View,
        rootView: ViewGroup,
        translationX: Float,
        onDismissed: (() -> Unit)?
    ) {
        if (view.parent == null) return

        val density = activity.resources.displayMetrics.density
        val targetY = if (translationX == 0f) -(view.height + 80 * density) else 0f
        val targetX = translationX
        val targetAlpha = 0f

        view.animate()
            .translationX(targetX)
            .translationY(targetY)
            .alpha(targetAlpha)
            .setDuration(EXIT_DURATION_MS)
            .setInterpolator(AccelerateInterpolator(1.4f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    safeRemoveView(view)
                    if (currentView == view) {
                        currentView = null
                        currentTag = null
                    }
                    onDismissed?.invoke()
                    processQueue()
                }

                override fun onAnimationCancel(animation: Animator) {
                    safeRemoveView(view)
                    if (currentView == view) {
                        currentView = null
                        currentTag = null
                    }
                    processQueue()
                }
            })
            .start()
    }

    private fun processQueue() {
        if (queue.isNotEmpty()) {
            handler.postDelayed({ showNext() }, BETWEEN_NOTIF_DELAY_MS)
        } else {
            isShowing = false
        }
    }

    // ── View inflation ───────────────────────────────────────────────────

    private fun inflateNotificationView(notification: AppNotification): View {
        val view = LayoutInflater.from(activity).inflate(R.layout.notification_banner, null)

        // Title — emoji is embedded directly in the title string at the call site
        view.findViewById<TextView>(R.id.notifTitle).text = notification.title

        // Message (optional)
        val msgView = view.findViewById<TextView>(R.id.notifMessage)
        if (!notification.message.isNullOrBlank()) {
            msgView.text = notification.message
            msgView.visibility = View.VISIBLE
        } else {
            msgView.visibility = View.GONE
        }

        // Apply a colored stroke that matches the notification type's accent color
        val density = activity.resources.displayMetrics.density
        val cornerRadiusPx = 20 * density
        val strokeWidthPx = (1.5f * density).toInt()
        val strokeColor = resolveThemeColor(notification.type.accentColorAttr)
        val backgroundColor = resolveThemeColor(R.attr.colorSurfaceCard)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(backgroundColor)
            cornerRadius = cornerRadiusPx
            setStroke(strokeWidthPx, strokeColor)
        }
        view.background = background

        return view
    }

    private fun resolveStrokeColor(notification: AppNotification): Int {
        val defaultStroke = (resolveThemeColor(notification.type.accentColorAttr) and 0x00FFFFFF) or 0xB3000000.toInt()
        if (notification.type != Type.WEATHER) return defaultStroke

        val firstWord = notification.title
            .trim()
            .replace(Regex("^[^A-Za-z]+"), "")
            .substringBefore(" ")
            .lowercase(Locale.getDefault())

        val weatherStroke = when (firstWord) {
            "yellow" -> 0xB3FFD54F.toInt()
            "orange" -> 0xB3FB8C00.toInt()
            "red" -> 0xB3E53935.toInt()
            else -> return defaultStroke
        }

        return weatherStroke
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun cancelAutoDismiss() {
        autoDismissJob?.let { handler.removeCallbacks(it) }
        autoDismissJob = null
    }

    private fun resolveThemeColor(@AttrRes attrRes: Int): Int {
        val typedValue = TypedValue()
        if (!activity.theme.resolveAttribute(attrRes, typedValue, true)) {
            Log.w(TAG, "Theme color attribute not found: $attrRes")
            return 0
        }
        return if (typedValue.resourceId != 0) {
            activity.getColor(typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private fun safeRemoveView(view: View) {
        try {
            (view.parent as? ViewGroup)?.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove notification view: ${e.message}")
        }
    }
}
