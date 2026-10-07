/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.TouchInteractionController
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi

/**
 * User-touch observer based on `TouchInteractionController`.
 *
 * Any touch state other than [TouchInteractionController.STATE_CLEAR]
 * means the user is touching the screen. Requires API 33 (the
 * display-scoped controller accessor); callers must guard with
 * `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU` and rely on
 * `TYPE_TOUCH_INTERACTION_START` events on older releases.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class TouchStateWatcher(
    private val service: AccessibilityService,
    private val onUserTouch: () -> Unit,
) {
    private val callback =
        object : TouchInteractionController.Callback {
            override fun onMotionEvent(event: android.view.MotionEvent) {
                // State changes drive pausing; motion events ignored.
            }

            override fun onStateChanged(state: Int) {
                if (state != TouchInteractionController.STATE_CLEAR) {
                    onUserTouch()
                }
            }
        }

    /** Registers the touch-state callback on the default display. */
    @Suppress("TooGenericExceptionCaught")
    fun register() {
        try {
            service
                .getTouchInteractionController(Display.DEFAULT_DISPLAY)
                .registerCallback(service.mainExecutor, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Touch callback register failed: ${e.javaClass.simpleName}")
        }
    }

    /** Unregisters the touch-state callback. */
    @Suppress("TooGenericExceptionCaught")
    fun unregister() {
        try {
            service
                .getTouchInteractionController(Display.DEFAULT_DISPLAY)
                .unregisterCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Touch callback unregister failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "TouchStateWatcher"
    }
}
