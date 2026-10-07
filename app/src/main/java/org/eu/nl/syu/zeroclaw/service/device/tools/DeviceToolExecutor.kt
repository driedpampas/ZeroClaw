/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device.tools

import org.eu.nl.syu.zeroclaw.service.device.DeviceAction
import org.eu.nl.syu.zeroclaw.service.device.DeviceCommand
import org.eu.nl.syu.zeroclaw.service.device.DeviceCommandResult
import org.eu.nl.syu.zeroclaw.service.device.DeviceControlBridge

/**
 * Outcome of executing one [DeviceAction].
 *
 * @property success Whether the tool succeeded.
 * @property observation Optional text for the next Reason step
 *   (e.g. the `list_apps` listing). Only data-returning tools set
 *   this; gestures never do.
 */
data class ToolOutcome(
    val success: Boolean,
    val observation: String? = null,
)

/**
 * Executes [DeviceAction]s for the agent loop.
 *
 * Gesture actions dispatch through [DeviceControlBridge]; app actions
 * go through [DeviceAppOperator]. Injectable so tests can substitute
 * scripted outcomes without Android.
 */
interface DeviceToolExecutor {
    /**
     * Executes [action].
     *
     * @param action The action chosen by the Reason step.
     * @return The outcome, with [ToolOutcome.observation] set for
     *   data-returning tools only.
     */
    suspend fun execute(action: DeviceAction): ToolOutcome
}

/**
 * Gesture-only executor used until a [Context][android.content.Context]
 * is available to build the full Android executor.
 *
 * App actions ([DeviceAction.OpenApp], [DeviceAction.ListApps]) report
 * failure with an explanatory observation instead of crashing.
 */
object BridgeDeviceToolExecutor : DeviceToolExecutor {
    override suspend fun execute(action: DeviceAction): ToolOutcome =
        when (action) {
            is DeviceAction.Tap,
            is DeviceAction.Swipe,
            is DeviceAction.Type,
            is DeviceAction.ClickNode,
            is DeviceAction.Global,
            -> {
                val command = DeviceCommand.fromAction(action)
                if (command == null) {
                    ToolOutcome(false)
                } else {
                    ToolOutcome(DeviceControlBridge.execute(command).success)
                }
            }
            is DeviceAction.OpenApp,
            is DeviceAction.ListApps,
            -> ToolOutcome(false, "App tools unavailable: agent service not started")
            DeviceAction.NoOp,
            is DeviceAction.Finish,
            -> ToolOutcome(true)
        }
}

/**
 * Full on-device executor: gestures via the accessibility bridge, app
 * actions via [DeviceAppOperator].
 *
 * @param appOperator Opens apps and lists launchable packages.
 * @param gestureRunner Dispatches gesture commands; defaults to the
 *   live [DeviceControlBridge].
 */
class AndroidDeviceToolExecutor(
    private val appOperator: DeviceAppOperator,
    private val gestureRunner: suspend (DeviceCommand) -> DeviceCommandResult = {
        DeviceControlBridge.execute(it)
    },
) : DeviceToolExecutor {
    override suspend fun execute(action: DeviceAction): ToolOutcome =
        when (action) {
            is DeviceAction.Tap,
            is DeviceAction.Swipe,
            is DeviceAction.Type,
            is DeviceAction.ClickNode,
            is DeviceAction.Global,
            -> executeGesture(action)
            is DeviceAction.OpenApp -> ToolOutcome(appOperator.openApp(action.packageName))
            is DeviceAction.ListApps -> executeListApps(action)
            DeviceAction.NoOp, is DeviceAction.Finish -> ToolOutcome(true)
        }

    private suspend fun executeGesture(action: DeviceAction): ToolOutcome {
        val command = DeviceCommand.fromAction(action) ?: return ToolOutcome(true)
        return ToolOutcome(gestureRunner(command).success)
    }

    private fun executeListApps(action: DeviceAction.ListApps): ToolOutcome {
        val apps = appOperator.listApps(action.query)
        if (apps.isEmpty()) {
            return ToolOutcome(true, "No launchable apps matched.")
        }
        val lines =
            apps.take(MAX_OBSERVATION_APPS).joinToString("\n") { entry ->
                "${entry.label.take(MAX_LABEL_CHARS)} (${entry.packageName})"
            }
        return ToolOutcome(true, "Launchable apps:\n$lines")
    }

    /** Observation size caps for [AndroidDeviceToolExecutor]. */
    companion object {
        /** Max apps included in a list_apps observation. */
        const val MAX_OBSERVATION_APPS = 50

        /** Max label characters per app line. */
        const val MAX_LABEL_CHARS = 40
    }
}
