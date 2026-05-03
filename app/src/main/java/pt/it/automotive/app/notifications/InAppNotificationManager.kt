package pt.it.automotive.app.notifications

import pt.it.automotive.app.R

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
import kotlin.math.max
import kotlin.math.min
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

    // Helper reference initialized post-construction
    var alertNotificationManager: AlertNotificationManager? = null

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
        val type: Type?,
        val title: String?,
        val message: String? = null,
        val duration: Long = DEFAULT_DURATION_MS,
        
        // Priority System props
        val priority: Int = 0,
        val expirationS: Int = 0,
        val timestamp: Long = 0,

        // Exception props
        val isVisualExempt: Boolean = false,
        val customVisualAction: (() -> Unit)? = null,
        val playAudioAction: (() -> Unit)? = null,
        
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

    private val queue = mutableListOf<AppNotification>()
    private var currentView: View? = null
    private var currentTag: String? = null
    private var isShowing = false
    private val handler = Handler(Looper.getMainLooper())
    private var autoDismissJob: Runnable? = null
    
    private var currentPriority: Int = 0
    private var currentPriorityAudio: Int = 0

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Master function for priority-based alerts.
     * Determines whether to discard, update-in-place, queue, or immediately display an alert.
     */
    fun handleAlert(notification: AppNotification) {
        val now = System.currentTimeMillis() / 1000

        // 1. TTL Check: Discard if expired
        if (notification.timestamp > 0 && notification.expirationS > 0) {
            if (notification.timestamp + notification.expirationS < now) {
                Log.d(TAG, "Alert expired, discarding: ${notification.title}")
                return
            }
        }

        // 2. Dismissal Check: Skip if the user swiped this specific hazard away
        if (notification.tag != null && notification.tag in dismissedTags) {
            Log.d(TAG, "Alert tag ${notification.tag} was dismissed by user, dropping.")
            return
        }

        // 3. Exception Rule: Speeding & Overtaking (Visual Exempt)
        if (notification.isVisualExempt) {
            handler.post {
                notification.customVisualAction?.invoke()

            }
            
            if (notification.priority > currentPriorityAudio) {
                val prevAudioPriority = currentPriorityAudio
                currentPriorityAudio = notification.priority
                
                handler.post {
                    notification.playAudioAction?.invoke()
                }
            }
            return 
        }

        // 4. NEW: Tag Deduplication / Update-In-Place Rule
        if (notification.tag != null) {
            // Case A: It's currently showing on screen
            if (isShowing && currentTag == notification.tag && currentView?.parent != null) {
                Log.d(TAG, "Updating existing on-screen alert for tag: ${notification.tag}")
                handler.post {
                    updateCurrentViewInPlace(notification)
                }
                return // Done, do not queue
            }

            // Case B: It's already in the queue waiting to be shown
            val existingIndex = queue.indexOfFirst { it.tag == notification.tag }
            if (existingIndex != -1) {
                Log.d(TAG, "Updating existing queued alert for tag: ${notification.tag}")
                queue[existingIndex] = notification // Replace with freshest distance/data
                return // Done, do not add duplicate
            }
        }

        // 5. Normal Banner Alert Rule (Accident, Highway Entry, Jam, EV)
        if (notification.priority > currentPriority) {
            Log.d(TAG, "Higher priority alert (${notification.priority} > $currentPriority): overwriting screen")
            currentPriority = notification.priority
            currentPriorityAudio = notification.priority

            handler.post {
                cancelAutoDismiss()
                currentView?.let { v ->
                    safeRemoveView(v)
                    currentView = null
                }
                isShowing = false
                enqueue(notification)
            }
        } else {
            Log.d(TAG, "Lower/equal priority alert (${notification.priority} <= $currentPriority): adding to queue")
            enqueue(notification)
        }
    }

    private fun updateCurrentViewInPlace(notification: AppNotification) {
        val view = currentView ?: return
        
        ensureNotificationOnTop(view)
        
        // Update Title and Message dynamically
        view.findViewById<TextView>(R.id.notifTitle)?.text = notification.title
        val msgView = view.findViewById<TextView>(R.id.notifMessage)
        
        if (!notification.message.isNullOrBlank()) {
            msgView?.text = notification.message
            msgView?.visibility = View.VISIBLE
        } else {
            msgView?.visibility = View.GONE
        }
        
        // Reset the auto-dismiss timer so it stays on screen as long as updates stream in
        cancelAutoDismiss()
        val dismissRunnable = Runnable {
            if (view.parent != null) {
                val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
                // Assuming you have animateOut defined later in the file
                animateOut(view, rootView, translationX = 0f, notification.onDismissed)
            }
        }
        autoDismissJob = dismissRunnable
        handler.postDelayed(dismissRunnable, notification.duration)
    }

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
        enqueue(AppNotification(
            type = type, 
            title = title, 
            message = message, 
            duration = duration, 
            onDismissed = onDismissed, 
            tag = tag
        ))
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
                ensureNotificationOnTop(view)
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
                enqueue(AppNotification(
                    type = type,
                    title = title,
                    message = message,
                    duration = duration,
                    onDismissed = onDismissed,
                    tag = tag
                ))
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
     * Validates TTL, sorts by priority (highest first), and maintains queue size.
     */
    fun enqueue(notification: AppNotification) {
        handler.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            if (notification.tag != null && notification.tag in dismissedTags) return@post
            
            // Validate TTL before adding to queue
            if (notification.expirationS > 0 && notification.timestamp > 0) {
                val currentTimeS = System.currentTimeMillis() / 1000
                if (currentTimeS >= notification.timestamp + notification.expirationS) {
                    return@post  // Alert expired, skip
                }
            }
            
            if (queue.size >= MAX_QUEUE_SIZE) {
                Log.w(TAG, "Queue full - dropping oldest notification")
                queue.remove(queue.minByOrNull { it.timestamp }) // Keep newest
            }
            
            queue.add(notification)
            
            // Sort queue by priority (descending), then by timestamp (ascending - oldest first)
            queue.sortWith(compareBy<AppNotification> { -it.priority }.thenBy { it.timestamp })
            
            currentView?.let { ensureNotificationOnTop(it) }
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
            currentPriority = 0
            currentPriorityAudio = 0
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
        currentPriority = 0
        currentPriorityAudio = 0
        queue.clear()
    }

    // ── Core display logic ───────────────────────────────────────────────

    private fun showNext() {
        // Remove expired alerts from queue
        val currentTimeS = System.currentTimeMillis() / 1000
        queue.removeAll { notification ->
            notification.expirationS > 0 && 
            notification.timestamp > 0 && 
            currentTimeS >= notification.timestamp + notification.expirationS
        }
        
        val notification = queue.removeFirstOrNull() ?: run {
            isShowing = false
            currentPriority = 0
            currentPriorityAudio = 0
            return
        }
        isShowing = true
        
        // Update priority tracking
        currentPriority = notification.priority
        currentPriorityAudio = notification.priority

        // Only play audio if this alert was valid natively triggered
        notification.playAudioAction?.invoke()

        if (activity.isFinishing || activity.isDestroyed) {
            isShowing = false
            return
        }

        val rootView = activity.findViewById<ViewGroup>(R.id.mainContainer) 
            ?: activity.findViewById<ViewGroup>(android.R.id.content) 
            ?: run {
                isShowing = false
                return
            }

        val view = inflateNotificationView(notification)
        view.elevation = 9999f  // float above all other in-app UI

        val density = activity.resources.displayMetrics.density
        val screenWidthPx = activity.resources.displayMetrics.widthPixels
        val desiredWidthPx = (420 * density).toInt()
        val topMarginPx = (16 * density).toInt()

        val lp = buildNotificationLayoutParams(rootView, desiredWidthPx, topMarginPx)

        rootView.addView(view, lp)
        currentView = view
        currentTag = notification.tag
        ensureNotificationOnTop(view)

        // Measure and then animate in from above
        view.post {
            view.translationY = -(view.height + lp.topMargin + 60).toFloat()
            view.alpha = 0f
            view.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(ENTER_DURATION_MS)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .setListener(null)
                .start()
        }

        // Swipe-to-dismiss
        setupSwipeDismiss(view, rootView, notification)

        // Auto-dismiss
        val dismissRunnable = Runnable {
            if (view.parent != null) {
                animateOut(view, rootView, translationX = 0f, {
                    currentPriority = 0
                    currentPriorityAudio = 0
                    notification.onDismissed?.invoke()
                })
            }
        }
        autoDismissJob = dismissRunnable
        handler.postDelayed(dismissRunnable, notification.duration + ENTER_DURATION_MS)
    }

    private fun buildNotificationLayoutParams(
        rootView: ViewGroup,
        widthPx: Int,
        topMarginPx: Int
    ): ViewGroup.MarginLayoutParams {
        return if (rootView is androidx.constraintlayout.widget.ConstraintLayout) {
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                widthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                topMargin = topMarginPx
            }
        } else {
            android.widget.FrameLayout.LayoutParams(
                widthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                this.topMargin = topMarginPx
            }
        }
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
                        animateOut(view, rootView, exitX, {
                            currentPriority = 0
                            currentPriorityAudio = 0
                            handleUserDismissal(notification.tag, notification.onDismissed)                        })
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
                            animateOut(view, rootView, exitX, {
                                currentPriority = 0
                                currentPriorityAudio = 0
                                handleUserDismissal(notification.tag, notification.onDismissed)
                            })
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
        val strokeColor = resolveThemeColor(notification.type?.accentColorAttr ?: R.attr.colorNotificationInfo)
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
        val defaultStroke = (resolveThemeColor(notification.type?.accentColorAttr ?: R.attr.colorNotificationInfo) and 0x00FFFFFF) or 0xB3000000.toInt()
        if (notification.type != Type.WEATHER) return defaultStroke

        val firstWord = (notification.title ?: "")
            .trim()            .replace(Regex("^[^A-Za-z]+"), "")
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

    fun releaseAudioChannel() {
        currentPriorityAudio = 0 // Free up the audio channel safely
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

    private fun ensureNotificationOnTop(view: View) {
        val parent = view.parent as? ViewGroup ?: return
        parent.bringChildToFront(view)
        view.bringToFront()
        view.elevation = 9999f
        view.translationZ = 9999f
        parent.requestLayout()
        parent.invalidate()
    }

    /**
     * Centralized logic for when a driver manually dismisses a notification.
     * Halts audio, drops priority locks, and sets a temporary mute cooldown for the hazard.
     */
    fun handleUserDismissal(tag: String?, originalOnDismissed: (() -> Unit)?) {
        // 1. Immediately kill the TTS voice
        alertNotificationManager?.stopAudio()

        // 2. Free up the audio priority lock so new alerts can talk
        currentPriorityAudio = 0

        // 3. Handle the MQTT spam cooldown if the alert has a tag
        if (tag != null) {
            dismissedTags.add(tag)
            Log.d(TAG, "Tag $tag dismissed by driver. Muted for 2 minutes.")

            // 4. Remove the tag after 2 minutes (120,000 ms)
            // If the driver encounters the SAME hazard later, we want it to warn them again.
            handler.postDelayed({
                dismissedTags.remove(tag)
                Log.d(TAG, "Cooldown ended for tag $tag. Tracking restored.")
            }, 120_000L)
        }

        // 5. Trigger any custom dismissal logic (like stopping navigation)
        originalOnDismissed?.invoke()
    }
}
