/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

import org.json.JSONException
import org.json.JSONObject

/**
 * Actions the on-device agent can perform in one Act step.
 *
 * Adapted from DroidClaw's `AccessibilityCommand` action schema
 * (tap / swipe / set-text / click-node / global-action / get-ui-tree),
 * expressed as a Kotlin sealed hierarchy so the agent loop is
 * exhaustive and testable without Android dependencies.
 *
 * Use [eventName] for structured logging. It carries only the action
 * type — never coordinates, text, or selectors — so screen contents
 * and typed text can never leak into logs.
 */
sealed interface DeviceAction {
    /** Structured log token for this action (no payload). */
    val eventName: String

    /**
     * Tap at absolute screen pixels.
     *
     * @property x X coordinate in pixels.
     * @property y Y coordinate in pixels.
     */
    data class Tap(val x: Int, val y: Int) : DeviceAction {
        override val eventName: String = "ACTION_TAP"
    }

    /**
     * Swipe between two points.
     *
     * @property x1 Start X in pixels.
     * @property y1 Start Y in pixels.
     * @property x2 End X in pixels.
     * @property y2 End Y in pixels.
     * @property durationMs Gesture duration, clamped to 50–5000 ms.
     */
    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Int = 300,
    ) : DeviceAction {
        override val eventName: String = "ACTION_SWIPE"

        /** Duration clamped to the platform-accepted range. */
        val safeDurationMs: Int = durationMs.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)

        /** Swipe duration bounds in milliseconds. */
        companion object {
            /** Minimum swipe duration in milliseconds. */
            const val MIN_SWIPE_MS = 50

            /** Maximum swipe duration in milliseconds. */
            const val MAX_SWIPE_MS = 5_000
        }
    }

    /**
     * Type text into the focused field or a node selector.
     *
     * @property text Text to type (never logged).
     * @property resourceId Optional target view resource ID.
     */
    data class Type(val text: String, val resourceId: String? = null) : DeviceAction {
        override val eventName: String = "ACTION_TYPE"
    }

    /**
     * Click a node matched by resource ID or visible text.
     *
     * @property resourceId Optional Android view resource ID.
     * @property text Optional visible-text match (never logged).
     */
    data class ClickNode(val resourceId: String? = null, val text: String? = null) : DeviceAction {
        override val eventName: String = "ACTION_CLICK"
    }

    /**
     * Perform a global accessibility action (back, home, recents…).
     *
     * @property actionId `AccessibilityService.GLOBAL_ACTION_*` constant.
     */
    data class Global(val actionId: Int) : DeviceAction {
        override val eventName: String = "ACTION_GLOBAL"
    }

    /** No-op step; advances the loop without touching the screen. */
    data object NoOp : DeviceAction {
        override val eventName: String = "ACTION_NOOP"
    }

    /**
     * Goal reached; ends the loop successfully.
     *
     * @property summary Short outcome description (structured only).
     */
    data class Finish(val summary: String = "") : DeviceAction {
        override val eventName: String = "ACTION_FINISH"
    }

    /** LLM JSON action parsing and global-action constants. */
    companion object {
        /**
         * Parses an LLM-produced JSON action into a [DeviceAction].
         *
         * Expected shape: `{"action":"tap","x":100,"y":200}`,
         * `{"action":"swipe","x1":…,"y1":…,"x2":…,"y2":…}`,
         * `{"action":"type","text":"…"}`, `{"action":"click",…}`,
         * `{"action":"back"|"home"|"recents"}`, `{"action":"finish"}`,
         * `{"action":"noop"}`. Unknown or malformed input yields [NoOp]
         * so the loop keeps its step budget instead of crashing.
         *
         * @param json Raw LLM response text containing a JSON object.
         * @return Parsed action, or [NoOp] when unparseable.
         */
        fun parse(json: String): DeviceAction {
            val obj =
                try {
                    JSONObject(json)
                } catch (_: JSONException) {
                    return NoOp
                }
            return when (obj.optString("action").lowercase()) {
                "tap" -> parseTap(obj)
                "swipe" -> parseSwipe(obj)
                "type" -> parseType(obj)
                "click" -> parseClick(obj)
                "back" -> Global(GLOBAL_ACTION_BACK)
                "home" -> Global(GLOBAL_ACTION_HOME)
                "recents" -> Global(GLOBAL_ACTION_RECENTS)
                "finish" -> Finish(obj.optString("summary", ""))
                else -> NoOp
            }
        }

        private fun parseTap(obj: JSONObject): DeviceAction {
            if (!obj.has("x") || !obj.has("y")) return NoOp
            return Tap(obj.optInt("x"), obj.optInt("y"))
        }

        private fun parseSwipe(obj: JSONObject): DeviceAction {
            val hasCoords = obj.has("x1") && obj.has("y1") && obj.has("x2") && obj.has("y2")
            if (!hasCoords) return NoOp
            return Swipe(
                obj.optInt("x1"),
                obj.optInt("y1"),
                obj.optInt("x2"),
                obj.optInt("y2"),
                obj.optInt("duration_ms", DEFAULT_SWIPE_MS),
            )
        }

        private fun parseType(obj: JSONObject): DeviceAction {
            val text = obj.optString("text", "")
            if (text.isEmpty()) return NoOp
            return Type(text, obj.optString("resource_id", "").takeIf { it.isNotBlank() })
        }

        private fun parseClick(obj: JSONObject): DeviceAction {
            val rid = obj.optString("resource_id", "").takeIf { it.isNotBlank() }
            val text = obj.optString("text", "").takeIf { it.isNotBlank() }
            if (rid == null && text == null) return NoOp
            return ClickNode(rid, text)
        }

        /** Mirrors `AccessibilityService.GLOBAL_ACTION_BACK` (= 1). */
        const val GLOBAL_ACTION_BACK = 1

        /** Mirrors `AccessibilityService.GLOBAL_ACTION_HOME` (= 2). */
        const val GLOBAL_ACTION_HOME = 2

        /** Mirrors `AccessibilityService.GLOBAL_ACTION_RECENTS` (= 3). */
        const val GLOBAL_ACTION_RECENTS = 3

        /** Default swipe duration in milliseconds. */
        const val DEFAULT_SWIPE_MS = 300
    }
}
