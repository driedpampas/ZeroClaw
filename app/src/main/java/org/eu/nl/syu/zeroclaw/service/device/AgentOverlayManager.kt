/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

/**
 * Lifecycle owner for the [AgentBorderView] screen-edge overlay.
 *
 * Uses `TYPE_ACCESSIBILITY_OVERLAY` (no `SYSTEM_ALERT_WINDOW` needed when
 * added from the accessibility service path) with
 * `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE` so the border never
 * intercepts the agent's gestures or user input. All add/update/remove
 * operations are posted to the main thread; every method is idempotent
 * and leak-safe ([hide] in service `onDestroy` paths).
 */
object AgentOverlayManager {
    private const val TAG = "AgentOverlay"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var borderView: AgentBorderView? = null

    /**
     * Whether the overlay is currently attached.
     *
     * @return `true` after [show] until [hide].
     */
    fun isShowing(): Boolean = borderView != null

    /**
     * Shows the border in [mode], attaching the view on first call.
     *
     * @param context Any context; the application context is used.
     * @param mode Border appearance for the current agent state.
     */
    fun show(
        context: Context,
        mode: AgentBorderView.BorderMode,
    ) {
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            attachOrUpdate(appContext, mode)
        } else {
            mainHandler.post { attachOrUpdate(appContext, mode) }
        }
    }

    /**
     * Updates the border appearance without re-attaching.
     *
     * No-op when the overlay is not showing.
     *
     * @param mode New border appearance.
     */
    fun updateMode(mode: AgentBorderView.BorderMode) {
        val apply = {
            borderView?.mode = mode
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply()
        } else {
            mainHandler.post(apply)
        }
    }

    /**
     * Removes the overlay and releases the view.
     *
     * Safe to call when not showing or from any thread. Must be called
     * from `AccessibilityService.onDestroy` and the agent foreground
     * service's `onDestroy`.
     */
    fun hide() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            detach()
        } else {
            mainHandler.post { detach() }
        }
    }

    // WindowManager.addView/removeView throw platform RuntimeExceptions
    // (BadToken, Security, IllegalState, IllegalArgument); the overlay must
    // degrade silently instead of crashing the service.
    @Suppress("TooGenericExceptionCaught")
    private fun attachOrUpdate(
        context: Context,
        mode: AgentBorderView.BorderMode,
    ) {
        val existing = borderView
        if (existing != null) {
            existing.mode = mode
            return
        }
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = AgentBorderView(context).apply { this.mode = mode }
            windowManager.addView(view, overlayParams())
            borderView = view
        } catch (e: RuntimeException) {
            Log.w(TAG, "Overlay attach failed: ${e.javaClass.simpleName}")
        }
    }

    /** Removes the overlay; see [attachOrUpdate] for the catch rationale. */
    @Suppress("TooGenericExceptionCaught")
    private fun detach() {
        val view = borderView ?: return
        borderView = null
        try {
            val windowManager =
                view.context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.removeView(view)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Overlay detach failed: ${e.javaClass.simpleName}")
        }
    }

    private fun overlayParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
}
