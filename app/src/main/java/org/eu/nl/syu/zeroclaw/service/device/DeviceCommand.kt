/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

/**
 * Low-level command dispatched through [DeviceControlBridge] to
 * [ZeroClawAccessibilityService], mirroring DroidClaw's
 * `AccessibilityCommand` command set.
 *
 * [DeviceAction] is the LLM-facing model; a [DeviceCommand] is the
 * executor-facing model. [DeviceAgentController] maps one to the other
 * so parsing (tested, pure) stays separate from dispatch (Android).
 */
data class DeviceCommand private constructor(
    /** Command type. */
    val type: Type,
    /** Tap X / swipe start X. */
    val x: Int = 0,
    /** Tap Y / swipe start Y. */
    val y: Int = 0,
    /** Swipe end X. */
    val x2: Int = 0,
    /** Swipe end Y. */
    val y2: Int = 0,
    /** Swipe duration in milliseconds. */
    val durationMs: Int = 300,
    /** Target view resource ID, if any. */
    val resourceId: String? = null,
    /** Visible-text match, if any. */
    val nodeText: String? = null,
    /** Text payload for set-text (never logged). */
    val text: String? = null,
    /** Global action ID for [Type.GLOBAL_ACTION]. */
    val globalAction: Int = 0,
    /** UI-tree depth cap for [Type.GET_UI_TREE]. */
    val depth: Int = UiTreeSerializer.DEFAULT_MAX_DEPTH,
) {
    /** Command types supported by the accessibility service. */
    enum class Type {
        /** Read the current UI tree. */
        GET_UI_TREE,

        /** Tap at coordinates. */
        TAP,

        /** Swipe between coordinates. */
        SWIPE,

        /** Set text on a node. */
        SET_TEXT,

        /** Click a node by selector. */
        CLICK_NODE,

        /** Perform a global system action. */
        GLOBAL_ACTION,
    }

    /** Low-level command factories mirroring the DroidClaw command set. */
    companion object {
        /** Reads the UI tree up to [depth]. */
        fun getUiTree(depth: Int = UiTreeSerializer.DEFAULT_MAX_DEPTH): DeviceCommand =
            DeviceCommand(type = Type.GET_UI_TREE, depth = depth)

        /** Taps at ([x], [y]). */
        fun tapAt(
            x: Int,
            y: Int,
        ): DeviceCommand = DeviceCommand(type = Type.TAP, x = x, y = y)

        /** Swipes from ([x1], [y1]) to ([x2], [y2]). */
        fun swipe(
            x1: Int,
            y1: Int,
            x2: Int,
            y2: Int,
            durationMs: Int = 300,
        ): DeviceCommand =
            DeviceCommand(
                type = Type.SWIPE,
                x = x1,
                y = y1,
                x2 = x2,
                y2 = y2,
                durationMs = durationMs,
            )

        /** Sets [text] on the focused field or [resourceId]. */
        fun setText(
            text: String,
            resourceId: String? = null,
        ): DeviceCommand = DeviceCommand(type = Type.SET_TEXT, text = text, resourceId = resourceId)

        /** Clicks the node matching [resourceId] or [text]. */
        fun clickNode(
            resourceId: String? = null,
            text: String? = null,
        ): DeviceCommand = DeviceCommand(type = Type.CLICK_NODE, resourceId = resourceId, nodeText = text)

        /** Performs global action [actionId]. */
        fun globalAction(actionId: Int): DeviceCommand =
            DeviceCommand(type = Type.GLOBAL_ACTION, globalAction = actionId)

        /**
         * Maps an LLM-level [DeviceAction] to an executor-level command.
         *
         * @return `null` for [DeviceAction.NoOp] and [DeviceAction.Finish],
         *   which require no gesture dispatch.
         */
        @Suppress("ReturnCount")
        fun fromAction(action: DeviceAction): DeviceCommand? =
            when (action) {
                is DeviceAction.Tap -> tapAt(action.x, action.y)
                is DeviceAction.Swipe ->
                    swipe(action.x1, action.y1, action.x2, action.y2, action.safeDurationMs)
                is DeviceAction.Type -> setText(action.text, action.resourceId)
                is DeviceAction.ClickNode -> clickNode(action.resourceId, action.text)
                is DeviceAction.Global -> globalAction(action.actionId)
                DeviceAction.NoOp, is DeviceAction.Finish -> null
            }
    }
}

/**
 * Outcome of a [DeviceCommand] execution.
 *
 * @property success Whether the command succeeded.
 * @property payload Success payload (serialized UI tree for
 *   `GET_UI_TREE`, short status otherwise). Never logged verbatim —
 *   it may contain screen contents.
 * @property error Short error code when [success] is false.
 */
data class DeviceCommandResult(
    val success: Boolean,
    val payload: String? = null,
    val error: String? = null,
) {
    /** Result factories for command outcomes. */
    companion object {
        /** Successful result with optional [payload]. */
        fun success(payload: String? = null): DeviceCommandResult =
            DeviceCommandResult(success = true, payload = payload)

        /** Failed result with an [error] code. */
        fun error(error: String): DeviceCommandResult =
            DeviceCommandResult(success = false, error = error)
    }
}
