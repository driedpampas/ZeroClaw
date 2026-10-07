/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device.tools

/**
 * A single device-tool parameter.
 *
 * @property type JSON type name (`int`, `string`).
 * @property required Whether the parameter must be present.
 * @property description Human-readable meaning for the model.
 */
data class ToolParam(
    val type: String,
    val required: Boolean,
    val description: String,
)

/**
 * Definition of one on-device tool available to the agent.
 *
 * Definitions feed the Reason prompt ([DeviceToolCatalog.promptSection])
 * and are structured for future display in the app's tools browser.
 * Device tasks are explicitly user-initiated, so tools run in trust
 * mode ([requiresApproval] is `false`, the DroidClaw `trustMode`
 * equivalent); the flag exists for a future approval UI.
 *
 * @property name Stable tool name, also the LLM JSON `action` value.
 * @property description What the tool does, for the model.
 * @property params Parameter schema by name.
 * @property example Example JSON request body.
 * @property requiresApproval Whether execution needs user approval.
 */
data class DeviceToolDefinition(
    val name: String,
    val description: String,
    val params: Map<String, ToolParam> = emptyMap(),
    val example: String = "",
    val requiresApproval: Boolean = false,
)

/**
 * Catalog of on-device tools the agent can call.
 *
 * Single source of truth for tool names, descriptions, and parameter
 * schemas: the Reason prompt is generated from here so the model can
 * only request actions the executor implements.
 */
object DeviceToolCatalog {
    /** All tools in stable prompt order. */
    val tools: List<DeviceToolDefinition> =
        listOf(
            DeviceToolDefinition(
                name = "screen_tap",
                description = "Tap an element. Use centerX/centerY from the screen state.",
                params =
                    mapOf(
                        "x" to ToolParam("int", true, "X coordinate in screen pixels"),
                        "y" to ToolParam("int", true, "Y coordinate in screen pixels"),
                    ),
                example = "{\"action\":\"screen_tap\",\"x\":100,\"y\":200}",
            ),
            DeviceToolDefinition(
                name = "screen_swipe",
                description = "Swipe from one point to another (scrolling, dismissing).",
                params =
                    mapOf(
                        "x1" to ToolParam("int", true, "Start X in screen pixels"),
                        "y1" to ToolParam("int", true, "Start Y in screen pixels"),
                        "x2" to ToolParam("int", true, "End X in screen pixels"),
                        "y2" to ToolParam("int", true, "End Y in screen pixels"),
                        "duration_ms" to ToolParam("int", false, "Swipe duration, default 300"),
                    ),
                example = "{\"action\":\"screen_swipe\",\"x1\":100,\"y1\":500,\"x2\":100,\"y2\":200}",
            ),
            DeviceToolDefinition(
                name = "screen_type",
                description = "Type text into the focused field, or into resource_id.",
                params =
                    mapOf(
                        "text" to ToolParam("string", true, "Text to type"),
                        "resource_id" to ToolParam("string", false, "Target view resource ID"),
                    ),
                example = "{\"action\":\"screen_type\",\"text\":\"hello\"}",
            ),
            DeviceToolDefinition(
                name = "screen_click",
                description = "Click a node matched by resource ID or visible text.",
                params =
                    mapOf(
                        "resource_id" to ToolParam("string", false, "Android view resource ID"),
                        "text" to ToolParam("string", false, "Visible-text match"),
                    ),
                example = "{\"action\":\"screen_click\",\"resource_id\":\"com.app:id/btn\"}",
            ),
            DeviceToolDefinition(
                name = "press_back",
                description = "System back button.",
                example = "{\"action\":\"press_back\"}",
            ),
            DeviceToolDefinition(
                name = "press_home",
                description = "Go to the home screen.",
                example = "{\"action\":\"press_home\"}",
            ),
            DeviceToolDefinition(
                name = "press_recents",
                description = "Open the recent-apps overview.",
                example = "{\"action\":\"press_recents\"}",
            ),
            DeviceToolDefinition(
                name = "open_app",
                description = "Launch an installed app. Call list_apps first to find packages.",
                params =
                    mapOf(
                        "package" to ToolParam("string", true, "Application package name"),
                    ),
                example = "{\"action\":\"open_app\",\"package\":\"com.android.settings\"}",
            ),
            DeviceToolDefinition(
                name = "list_apps",
                description = "List launchable apps as 'label (package)' lines for open_app.",
                params =
                    mapOf(
                        "query" to ToolParam("string", false, "Case-insensitive filter"),
                    ),
                example = "{\"action\":\"list_apps\",\"query\":\"maps\"}",
            ),
            DeviceToolDefinition(
                name = "finish",
                description = "Goal reached; end the task.",
                params =
                    mapOf(
                        "summary" to ToolParam("string", false, "Short outcome description"),
                    ),
                example = "{\"action\":\"finish\",\"summary\":\"done\"}",
            ),
        )

    /**
     * Generates the tool section of the Reason prompt.
     *
     * @return One entry per tool with its schema and example.
     */
    fun promptSection(): String =
        buildString {
            append("Available tools (reply with ONE JSON object, no other text):\n")
            for (tool in tools) {
                append("- ")
                append(tool.name)
                append(": ")
                append(tool.description)
                if (tool.params.isNotEmpty()) {
                    append(" Params: ")
                    append(
                        tool.params.entries.joinToString(", ") { (key, param) ->
                            val marker = if (param.required) "required" else "optional"
                            "$key (${param.type}, $marker)"
                        },
                    )
                }
                if (tool.example.isNotEmpty()) {
                    append(" Example: ")
                    append(tool.example)
                }
                append("\n")
            }
        }
}
