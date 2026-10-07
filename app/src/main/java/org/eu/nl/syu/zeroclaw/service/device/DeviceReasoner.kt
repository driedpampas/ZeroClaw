/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

/**
 * Reason step of the Perceive → Reason → Act loop.
 *
 * Implementations map a compact screen snapshot plus the task goal to
 * the next [DeviceAction]. The interface keeps the loop testable with
 * scripted fakes while production uses [GatewayDeviceReasoner], which
 * delegates to ZeroClaw's existing LLM provider layer (the engine
 * gateway reached via `DaemonServiceBridge.send`), never bringing in
 * an external provider stack.
 */
fun interface DeviceReasoner {
    /**
     * Chooses the next action for [goal] given the current screen.
     *
     * @param screen Compact UI-tree text from [UiTreeSerializer].
     * @param goal Task description supplied at start.
     * @param step Zero-based loop iteration.
     * @return Next action. Implementations must never throw for
     *   malformed model output — return [DeviceAction.NoOp].
     */
    suspend fun reason(
        screen: String,
        goal: String,
        step: Int,
    ): DeviceAction
}

/**
 * [DeviceReasoner] that prompts ZeroClaw's existing LLM backend.
 *
 * The [chat] lambda is wired by [DeviceAgentService] to the engine
 * gateway (`DaemonServiceBridge.send`), so provider selection, API
 * keys, and model routing reuse the user's configured connections.
 *
 * @param chat Sends a prompt to the LLM and returns raw response text.
 */
class GatewayDeviceReasoner(
    private val chat: suspend (prompt: String) -> String,
) : DeviceReasoner {
    override suspend fun reason(
        screen: String,
        goal: String,
        step: Int,
    ): DeviceAction {
        val response =
            try {
                chat(buildPrompt(goal, screen, step))
            } catch (_: Exception) {
                return DeviceAction.NoOp
            }
        return DeviceAction.parse(extractJson(response))
    }

    /**
     * Builds the Reason prompt: goal, compact screen, and action schema.
     *
     * @param goal Task description.
     * @param screen Compact UI-tree text.
     * @param step Zero-based iteration (for step-budget awareness).
     * @return Prompt string for the LLM.
     */
    fun buildPrompt(
        goal: String,
        screen: String,
        step: Int,
    ): String =
        buildString {
            append("You control an Android phone. Goal: ")
            append(goal)
            append("\nStep: ")
            append(step)
            append("\nCurrent screen:\n")
            append(screen.take(MAX_SCREEN_CHARS))
            append("\nReply with ONE JSON action only, no other text. Schema:\n")
            append(ACTION_SCHEMA)
        }

    private fun extractJson(response: String): String {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start < 0 || end <= start) return response
        return response.substring(start, end + 1)
    }

    /** Prompt constants for the gateway-backed reasoner. */
    companion object {
        /** Max screen characters included in the prompt. */
        const val MAX_SCREEN_CHARS = 8_000

        /** Action schema advertised to the model. */
        const val ACTION_SCHEMA =
            "{\"action\":\"tap\",\"x\":100,\"y\":200} | " +
                "{\"action\":\"swipe\",\"x1\":100,\"y1\":500,\"x2\":100,\"y2\":200} | " +
                "{\"action\":\"type\",\"text\":\"hello\"} | " +
                "{\"action\":\"click\",\"resource_id\":\"com.app:id/btn\"} | " +
                "{\"action\":\"back\"|\"home\"|\"recents\"} | " +
                "{\"action\":\"finish\",\"summary\":\"done\"}"
    }
}
