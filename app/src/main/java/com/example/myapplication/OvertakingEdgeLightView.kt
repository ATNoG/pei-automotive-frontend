package com.example.myapplication

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

class OvertakingEdgeLightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val GLOW_WIDTH_DP = 24f
        private const val CORE_WIDTH_DP = 1f
        private const val EDGE_ALPHA = 175
        private const val MID_ALPHA = 72
        private const val CORE_ALPHA = 220
    }

    enum class Side {
        LEFT,
        RIGHT,
        NONE
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var activeSide = Side.NONE
    private var glowStrength = 0f
    private var pulseAnimator: ValueAnimator? = null
    private val hideIndicatorRunnable = Runnable { hideIndicator() }

    init {
        isClickable = false
        isFocusable = false
    }

    fun showIndicator(side: Side, durationMs: Long) {
        if (side == Side.NONE) {
            hideIndicator()
            return
        }

        activeSide = side
        removeCallbacks(hideIndicatorRunnable)
        ensurePulseAnimator()
        if (pulseAnimator?.isStarted != true) {
            pulseAnimator?.start()
        }
        postDelayed(hideIndicatorRunnable, durationMs)
        invalidate()
    }

    fun hideIndicator() {
        removeCallbacks(hideIndicatorRunnable)
        activeSide = Side.NONE
        glowStrength = 0f
        pulseAnimator?.cancel()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (activeSide == Side.NONE || glowStrength <= 0f) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val density = resources.displayMetrics.density
        val glowWidth = GLOW_WIDTH_DP * density
        val coreWidth = CORE_WIDTH_DP * density
        val edgeAlpha = (EDGE_ALPHA * glowStrength).roundToInt().coerceIn(0, 255)
        val midAlpha = (MID_ALPHA * glowStrength).roundToInt().coerceIn(0, 255)
        val coreAlpha = (CORE_ALPHA * glowStrength).roundToInt().coerceIn(0, 255)
        val top = 0f
        val bottom = viewHeight

        val glowRect = when (activeSide) {
            Side.LEFT -> RectF(0f, top, glowWidth, bottom)
            Side.RIGHT -> RectF(viewWidth - glowWidth, top, viewWidth, bottom)
            Side.NONE -> return
        }

        glowPaint.shader = when (activeSide) {
            Side.LEFT -> LinearGradient(
                glowRect.left,
                0f,
                glowRect.right,
                0f,
                intArrayOf(
                    Color.argb(edgeAlpha, 255, 70, 70),
                    Color.argb(midAlpha, 255, 20, 20),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.08f, 1f),
                Shader.TileMode.CLAMP
            )

            Side.RIGHT -> LinearGradient(
                glowRect.left,
                0f,
                glowRect.right,
                0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(midAlpha, 255, 20, 20),
                    Color.argb(edgeAlpha, 255, 70, 70)
                ),
                floatArrayOf(0f, 0.92f, 1f),
                Shader.TileMode.CLAMP
            )

            Side.NONE -> null
        }
        canvas.drawRect(glowRect, glowPaint)

        val coreRect = when (activeSide) {
            Side.LEFT -> RectF(0f, top, coreWidth, bottom)
            Side.RIGHT -> RectF(viewWidth - coreWidth, top, viewWidth, bottom)
            Side.NONE -> return
        }
        corePaint.color = Color.argb(coreAlpha, 255, 110, 110)
        canvas.drawRect(coreRect, corePaint)
    }

    override fun onDetachedFromWindow() {
        hideIndicator()
        pulseAnimator = null
        super.onDetachedFromWindow()
    }

    private fun ensurePulseAnimator() {
        if (pulseAnimator != null) return

        pulseAnimator = ValueAnimator.ofFloat(0.35f, 1f).apply {
            duration = 700L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                glowStrength = animator.animatedValue as Float
                invalidate()
            }
        }
    }
}
