/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

import org.eu.nl.syu.zeroclaw.service.device.tools.DeviceToolCatalog

/**
 * Input for one Reason step.
 *
 * @property screen Compact UI-tree text from [UiTreeSerializer].
 * @property goal Task description supplied at start.
 * @property step Zero-based loop iteration.
 * @property observation Optional output of the previous tool
 *   (e.g. a `list_apps` listing); null when the last tool returned
 *   no data.
 */
data class ReasonInput(
    val screen: String,
    val goal: String,
    val step: Int,
    val observation: String? = null,
)

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
     * Chooses the next action for [ReasonInput.goal] given the current screen.
     *
     * @param input Screen, goal, step, and previous tool observation.
     * @return Next action. Implementations must never throw for
     *   malformed model output — return [DeviceAction.NoOp].
     */
    suspend fun reason(input: ReasonInput): DeviceAction
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
    override suspend fun reason(input: ReasonInput): DeviceAction {
        val response =
            try {
                chat(buildPrompt(input.goal, input.screen, input.step, input.observation))
            } catch (_: Exception) {
                return DeviceAction.NoOp
            }
        return DeviceAction.parse(extractJson(response))
    }

    /**
     * Builds the Reason prompt: goal, compact screen, tool catalog, and
     * the previous tool's observation when present.
     *
     * @param goal Task description.
     * @param screen Compact UI-tree text.
     * @param step Zero-based iteration (for step-budget awareness).
     * @param observation Previous data-returning tool output, if any.
     * @return Prompt string for the LLM.
     */
    fun buildPrompt(
        goal: String,
        screen: String,
        step: Int,
        observation: String? = null,
    ): String =
        buildString {
            append("You control an Android phone. Goal: ")
            append(goal)
            append("\nStep: ")
            append(step)
            append("\nCurrent screen:\n")
            append(screen.take(MAX_SCREEN_CHARS))
            append("\n")
            if (!observation.isNullOrBlank()) {
                append("Last tool output:\n")
                append(observation.take(MAX_OBSERVATION_CHARS))
                append("\n")
            }
            append(DeviceToolCatalog.promptSection())
        }

    private fun extractJson(response: String): String {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start < 0 || end <= start) return response
        return response.substring(start, end + 1)
    }

    /** Prompt size caps for the gateway-backed reasoner. */
    companion object {
        /** Max screen characters included in the prompt. */
        const val MAX_SCREEN_CHARS = 8_000

        /** Max observation characters included in the prompt. */
        const val MAX_OBSERVATION_CHARS = 2_000
    }
}
