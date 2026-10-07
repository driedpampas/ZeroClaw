/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton bridge between the agent loop and [ZeroClawAccessibilityService].
 *
 * Tools and [DeviceAgentController] call [execute] on a background thread;
 * the bridge posts the command to the service's main-thread handler and
 * blocks on a [CompletableFuture] until completion or timeout. Ported from
 * DroidClaw's `AccessibilityBridge` with the same main-thread deadlock guard.
 */
object DeviceControlBridge {
    private const val TAG = "DeviceControlBridge"

    /** Seconds before a command is reported as timed out. */
    const val COMMAND_TIMEOUT_SECONDS = 10L

    @Volatile
    private var serviceInstance: ZeroClawAccessibilityService? = null

    private val _connected = MutableStateFlow(false)

    /**
     * Whether the accessibility service is connected.
     *
     * Observed by the UI so the enable-accessibility prompt appears
     * only while the service is disabled (never enabled, or revoked),
     * and by the agent service for state transitions.
     */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * Called by [ZeroClawAccessibilityService.onServiceConnected].
     *
     * @param service The connected service instance.
     */
    fun register(service: ZeroClawAccessibilityService) {
        serviceInstance = service
        _connected.value = true
        Log.i(TAG, "Accessibility service registered")
    }

    /** Called by [ZeroClawAccessibilityService.onDestroy]. */
    fun unregister() {
        serviceInstance = null
        _connected.value = false
        Log.i(TAG, "Accessibility service unregistered")
    }

    /**
     * Whether the accessibility service is connected and usable.
     *
     * @return `true` when commands can be dispatched.
     */
    fun isConnected(): Boolean = serviceInstance != null

    /**
     * Executes [command] synchronously; must be called off the main thread.
     *
     * When called on the main thread, synchronous commands
     * (`GET_UI_TREE`, `CLICK_NODE`, `SET_TEXT`, `GLOBAL_ACTION`) run inline,
     * while gestures return a `dispatched` placeholder because their
     * callback also runs on the main thread and blocking would deadlock.
     *
     * @param command The command to execute.
     * @return The command outcome.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun execute(command: DeviceCommand): DeviceCommandResult {
        val service =
            serviceInstance
                ?: return DeviceCommandResult.error("SERVICE_NOT_CONNECTED")

        if (Looper.myLooper() == Looper.getMainLooper()) {
            val future = CompletableFuture<DeviceCommandResult>()
            try {
                service.executeCommand(command, future)
            } catch (e: Exception) {
                Log.w(TAG, "Command execution failed: ${e.javaClass.simpleName}")
                return DeviceCommandResult.error("DISPATCH_FAILED")
            }
            if (future.isDone) {
                return try {
                    future.get()
                } catch (e: Exception) {
                    Log.w(TAG, "Command failed: ${e.javaClass.simpleName}")
                    DeviceCommandResult.error("COMMAND_FAILED")
                }
            }
            return DeviceCommandResult.success(
                "{\"status\":\"dispatched\"}",
            )
        }

        val future = CompletableFuture<DeviceCommandResult>()
        Handler(Looper.getMainLooper()).post {
            try {
                service.executeCommand(command, future)
            } catch (e: Exception) {
                Log.w(TAG, "Command execution failed: ${e.javaClass.simpleName}")
                future.complete(DeviceCommandResult.error("DISPATCH_FAILED"))
            }
        }
        return try {
            future.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            Log.w(TAG, "Command timed out: ${command.type} (${e.javaClass.simpleName})")
            DeviceCommandResult.error("TIMEOUT")
        } catch (e: Exception) {
            Log.w(TAG, "Command interrupted: ${e.javaClass.simpleName}")
            DeviceCommandResult.error("INTERRUPTED")
        }
    }
}
