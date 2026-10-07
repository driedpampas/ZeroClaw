/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.toColorInt

/**
 * Google Lens-style screen-edge border shown while the agent is active.
 *
 * Draws a rounded-rectangle stroke inset from the view edges. [BorderMode.ACTIVE]
 * uses Google Blue with a subtle alpha pulse; [BorderMode.PAUSED] uses amber
 * with a slower, more distinct pulse to reinforce the paused state.
 */
class AgentBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    /** Visual mode of the border. */
    var mode: BorderMode = BorderMode.ACTIVE
        set(value) {
            field = value
            borderPaint.color = value.color
            restartPulse()
            invalidate()
        }

    private val borderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = BorderMode.ACTIVE.color
        }

    private var pulseAnimator: android.animation.ObjectAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val density = resources.displayMetrics.density
        borderPaint.strokeWidth = STROKE_WIDTH_DP * density
        restartPulse()
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val halfStroke = borderPaint.strokeWidth / 2f
        val radius = CORNER_RADIUS_DP * resources.displayMetrics.density
        canvas.drawRoundRect(
            halfStroke + INSET_PX,
            halfStroke + INSET_PX,
            width - halfStroke - INSET_PX,
            height - halfStroke - INSET_PX,
            radius,
            radius,
            borderPaint,
        )
    }

    /** Border visual modes. */
    enum class BorderMode(
        /** Stroke color. */
        val color: Int,
        /** Pulse cycle duration in milliseconds. */
        val pulseDurationMs: Long,
    ) {
        /** Agent running: Google Blue, brisk subtle pulse. */
        ACTIVE("#4285F4".toColorInt(), 1_200L),

        /** Agent paused: amber, slow distinct pulse. */
        PAUSED("#FFA000".toColorInt(), 2_200L),
    }

    private fun restartPulse() {
        stopPulse()
        alpha = 1f
        pulseAnimator =
            android.animation.ObjectAnimator.ofFloat(this, "alpha", 1f, MIN_ALPHA, 1f).apply {
                duration = mode.pulseDurationMs
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.RESTART
                start()
            }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        alpha = 1f
    }

    /** Border drawing constants. */
    companion object {
        /** Border stroke width in dp. */
        private const val STROKE_WIDTH_DP = 3f

        /** Corner radius in dp. */
        private const val CORNER_RADIUS_DP = 14f

        /** Extra inset from the screen edge in pixels. */
        private const val INSET_PX = 2f

        /** Pulse trough alpha. */
        private const val MIN_ALPHA = 0.6f
    }
}
