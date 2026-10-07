/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CompletableFuture

/**
 * ZeroClaw's single on-device AccessibilityService.
 *
 * Provides the Act/Perceive primitives for [DeviceAgentController]:
 * UI-tree reads ([DeviceCommand.Type.GET_UI_TREE]) and gesture dispatch
 * (`TAP`, `SWIPE` via [dispatchGesture], `CLICK_NODE`/`SET_TEXT` via node
 * actions, `GLOBAL_ACTION` via [performGlobalAction]). Command handling is
 * ported from DroidClaw's `DroidClawAccessibilityService` (gesture
 * callbacks completing a future, BFS node lookup, focused-editable search).
 *
 * User-touch detection parks the agent loop: on API 33+ a
 * [TouchStateWatcher] pauses the controller on any touch state other
 * than `STATE_CLEAR`; on all versions
 * [AccessibilityEvent.TYPE_TOUCH_INTERACTION_START] is a fallback
 * trigger. Both funnel into
 * [DeviceAgentController.requestPause] with [DeviceAgentController.REASON_USER_TOUCH].
 *
 * Declare exactly once in the manifest; all gesture dispatching and UI
 * tree reading in the app must go through [DeviceControlBridge].
 */
class ZeroClawAccessibilityService : AccessibilityService() {
    private var touchWatcher: TouchStateWatcher? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        DeviceControlBridge.register(this)
        enableTouchStateObservation()
        Log.i(TAG, "Service connected and registered with bridge")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            DeviceAgentController.requestPause(DeviceAgentController.REASON_USER_TOUCH)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            touchWatcher?.unregister()
        }
        touchWatcher = null
        DeviceControlBridge.unregister()
        DeviceAgentController.onAccessibilityLost()
        AgentOverlayManager.hide()
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    /**
     * Executes [command], completing [future] when done.
     *
     * Must be called on the main thread (enforced by [DeviceControlBridge]
     * posting via a main-thread handler). Gesture commands complete the
     * future from the gesture callback; all other commands complete it
     * synchronously before returning.
     */
    fun executeCommand(
        command: DeviceCommand,
        future: CompletableFuture<DeviceCommandResult>,
    ) {
        when (command.type) {
            DeviceCommand.Type.GET_UI_TREE -> future.complete(handleGetUiTree(command))
            DeviceCommand.Type.TAP -> handleTapAsync(command, future)
            DeviceCommand.Type.SWIPE -> handleSwipeAsync(command, future)
            DeviceCommand.Type.CLICK_NODE -> future.complete(handleClickNode(command))
            DeviceCommand.Type.SET_TEXT -> future.complete(handleSetText(command))
            DeviceCommand.Type.GLOBAL_ACTION -> future.complete(handleGlobalAction(command))
        }
    }

    private fun handleGetUiTree(command: DeviceCommand): DeviceCommandResult {
        val root = rootInActiveWindow ?: return DeviceCommandResult.error("NO_WINDOW")
        return try {
            val uiRoot = convertNode(root, 0, command.depth)
            val pkg = root.packageName?.toString() ?: "unknown"
            val serialized = UiTreeSerializer.serialize(listOf(uiRoot), pkg, command.depth)
            DeviceCommandResult.success(serialized)
        } finally {
            root.recycle()
        }
    }

    private fun handleTapAsync(
        command: DeviceCommand,
        future: CompletableFuture<DeviceCommandResult>,
    ) {
        val path = Path().apply { moveTo(command.x.toFloat(), command.y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted =
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        future.complete(DeviceCommandResult.success("{\"action\":\"tap\"}"))
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        future.complete(DeviceCommandResult.error("GESTURE_CANCELLED"))
                    }
                },
                null,
            )
        if (!accepted) future.complete(DeviceCommandResult.error("DISPATCH_REJECTED"))
    }

    private fun handleSwipeAsync(
        command: DeviceCommand,
        future: CompletableFuture<DeviceCommandResult>,
    ) {
        val duration =
            command.durationMs
                .coerceIn(
                    DeviceAction.Swipe.MIN_SWIPE_MS,
                    DeviceAction.Swipe.MAX_SWIPE_MS,
                ).toLong()
        val path =
            Path().apply {
                moveTo(command.x.toFloat(), command.y.toFloat())
                lineTo(command.x2.toFloat(), command.y2.toFloat())
            }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted =
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        future.complete(DeviceCommandResult.success("{\"action\":\"swipe\"}"))
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        future.complete(DeviceCommandResult.error("GESTURE_CANCELLED"))
                    }
                },
                null,
            )
        if (!accepted) future.complete(DeviceCommandResult.error("DISPATCH_REJECTED"))
    }

    private fun handleClickNode(command: DeviceCommand): DeviceCommandResult {
        val root = rootInActiveWindow ?: return DeviceCommandResult.error("NO_WINDOW")
        return try {
            val target =
                findNode(root, command.resourceId, command.nodeText)
                    ?: return DeviceCommandResult.error("NODE_NOT_FOUND")
            val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target.recycle()
            if (clicked) {
                DeviceCommandResult.success("{\"action\":\"click_node\"}")
            } else {
                DeviceCommandResult.error("CLICK_FAILED")
            }
        } finally {
            root.recycle()
        }
    }

    private fun handleSetText(command: DeviceCommand): DeviceCommandResult {
        val root = rootInActiveWindow ?: return DeviceCommandResult.error("NO_WINDOW")
        return try {
            val target =
                if (command.resourceId != null) {
                    findNode(root, command.resourceId, null)
                        ?: return DeviceCommandResult.error("NODE_NOT_FOUND")
                } else {
                    findFocusedEditable(root) ?: return DeviceCommandResult.error("NO_FOCUSED_FIELD")
                }
            val args =
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        command.text,
                    )
                }
            val set = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            target.recycle()
            if (set) {
                DeviceCommandResult.success("{\"action\":\"set_text\"}")
            } else {
                DeviceCommandResult.error("SET_TEXT_FAILED")
            }
        } finally {
            root.recycle()
        }
    }

    private fun handleGlobalAction(command: DeviceCommand): DeviceCommandResult {
        val performed = performGlobalAction(command.globalAction)
        return if (performed) {
            DeviceCommandResult.success("{\"action\":\"global_action\"}")
        } else {
            DeviceCommandResult.error("GLOBAL_ACTION_FAILED")
        }
    }

    /**
     * BFS lookup for the first node matching [resourceId] or [text].
     *
     * The caller owns the returned node and must recycle it.
     */
    private fun findNode(
        root: AccessibilityNodeInfo,
        resourceId: String?,
        text: String?,
    ): AccessibilityNodeInfo? {
        if (resourceId != null) {
            val found = root.findAccessibilityNodeInfosByViewId(resourceId)
            if (found.isNotEmpty()) {
                for (i in 1 until found.size) found[i].recycle()
                return found[0]
            }
        }
        if (text != null) {
            val found = root.findAccessibilityNodeInfosByText(text)
            if (found.isNotEmpty()) {
                for (i in 1 until found.size) found[i].recycle()
                return found[0]
            }
        }
        return null
    }

    /** Walks the tree for a focused editable node. */
    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditable(child)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    /** Converts a platform node into a serializable [UiNode]. */
    private fun convertNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
    ): UiNode {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val children =
            if (depth < maxDepth) {
                buildList {
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i) ?: continue
                        add(convertNode(child, depth + 1, maxDepth))
                        child.recycle()
                    }
                }
            } else {
                emptyList()
            }
        val className = node.className?.toString()?.substringAfterLast('.')
        return UiNode(
            className = className,
            resourceId = node.viewIdResourceName,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            hint = node.hintText?.toString(),
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            enabled = node.isEnabled,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            children = children,
        )
    }

    /**
     * Registers touch-state observation so user touches pause the agent.
     *
     * Primary path (API 33+): [TouchStateWatcher] — any state other than
     * `STATE_CLEAR` means the user is touching the screen. The
     * [FLAG_REQUEST_TOUCH_EXPLORATION_MODE] capability flag is requested
     * so the controller delivers states. On all versions,
     * [onAccessibilityEvent] remains the broad-compatibility fallback via
     * `TYPE_TOUCH_INTERACTION_START`.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun enableTouchStateObservation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            serviceInfo =
                serviceInfo?.apply {
                    flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
                }
            TouchStateWatcher(this) {
                DeviceAgentController.requestPause(DeviceAgentController.REASON_USER_TOUCH)
            }.also {
                touchWatcher = it
                it.register()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Touch state observation unavailable: ${e.javaClass.simpleName}")
            touchWatcher = null
        }
    }

    /** Service constants for gesture timing. */
    companion object {
        private const val TAG = "ZeroClawA11y"

        /** Tap gesture stroke duration in milliseconds. */
        private const val TAP_DURATION_MS = 50L
    }
}
