/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.eu.nl.syu.zeroclaw.service.device.tools.AndroidDeviceToolExecutor
import org.eu.nl.syu.zeroclaw.service.device.tools.PackageManagerAppOperator
import org.eu.nl.syu.zeroclaw.service.engine.GatewayClient

/**
 * Foreground service hosting the on-device agent loop.
 *
 * Keeps the Perceive → Reason → Act loop and the border overlay alive
 * when the user leaves the app. The service itself is never killed on
 * user touch — only the loop parks ([DeviceAgentState.PAUSED]) while
 * the notification switches to "Agent paused. Tap to resume."
 *
 * Overlay mapping: [DeviceAgentState.ACTIVE] shows the blue border,
 * [DeviceAgentState.PAUSED] shows the amber border, every other state
 * removes it.
 *
 * Control via intents: [ACTION_START] (with [EXTRA_GOAL]), [ACTION_PAUSE],
 * [ACTION_RESUME], [ACTION_STOP]. Reasoning reuses ZeroClaw's existing
 * LLM provider layer through the loopback gateway.
 */
class DeviceAgentService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var notifications: DeviceAgentNotificationManager

    @Volatile
    private var maxSteps: Int = DeviceAgentController.DEFAULT_MAX_STEPS

    override fun onCreate() {
        super.onCreate()
        notifications = DeviceAgentNotificationManager(this)
        notifications.createChannel()
        serviceScope.launch {
            DeviceAgentController.state.collect { state ->
                onAgentState(state)
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                maxSteps = intent.getIntExtra(EXTRA_MAX_STEPS, DeviceAgentController.DEFAULT_MAX_STEPS)
                val goal = intent.getStringExtra(EXTRA_GOAL).orEmpty()
                handleStart(goal)
            }
            ACTION_PAUSE -> DeviceAgentController.requestPause(DeviceAgentController.REASON_EXPLICIT)
            ACTION_RESUME -> DeviceAgentController.resume()
            ACTION_STOP -> handleStop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AgentOverlayManager.hide()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleStart(goal: String) {
        startForegroundCompat(notifications.buildNotification(DeviceAgentState.ACTIVE, 0, maxSteps))
        if (!DeviceControlBridge.isConnected()) {
            Log.w(TAG, "Accessibility service not connected; parking task")
        }
        wireReasoner()
        DeviceAgentController.toolExecutor =
            AndroidDeviceToolExecutor(PackageManagerAppOperator(this))
        DeviceAgentController.start(goal, maxSteps, serviceScope)
    }

    private fun handleStop() {
        DeviceAgentController.stop()
        AgentOverlayManager.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onAgentState(state: DeviceAgentState) {
        val steps = DeviceAgentController.stepsExecuted
        when (state) {
            DeviceAgentState.ACTIVE -> {
                AgentOverlayManager.show(this, AgentBorderView.BorderMode.ACTIVE)
                notifications.notify(state, steps, maxSteps)
            }
            DeviceAgentState.PAUSED -> {
                AgentOverlayManager.show(this, AgentBorderView.BorderMode.PAUSED)
                notifications.notify(state, steps, maxSteps)
            }
            DeviceAgentState.DONE,
            DeviceAgentState.STUCK,
            DeviceAgentState.ERROR,
            -> {
                AgentOverlayManager.hide()
                notifications.notify(state, steps, maxSteps)
            }
            DeviceAgentState.IDLE -> {
                AgentOverlayManager.hide()
            }
        }
    }

    /**
     * Wires the Reason step to ZeroClaw's existing LLM provider layer.
     *
     * The lambda calls the engine gateway chat endpoint using the
     * user's configured default agent, so provider selection and API
     * keys reuse the Connections settings. Failures degrade to an
     * empty response, which the reasoner maps to a no-op step.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun wireReasoner() {
        val gateway = GatewayClient(GatewayClient.loopback())
        DeviceAgentController.reasoner =
            GatewayDeviceReasoner { prompt ->
                try {
                    gateway.chatOnce(DEFAULT_AGENT_ALIAS, null, prompt)
                } catch (e: Exception) {
                    Log.w(TAG, "Reason chat failed: ${e.javaClass.simpleName}")
                    ""
                }
            }
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE was added in API 34; on older releases
        // the inlined constant would be unknown to the system, so use the 2-arg overload.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                DeviceAgentNotificationManager.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(DeviceAgentNotificationManager.NOTIFICATION_ID, notification)
        }
    }

    /** Intent actions and extras for [DeviceAgentService]. */
    companion object {
        private const val TAG = "DeviceAgentService"

        /** Starts an agent task; requires [EXTRA_GOAL]. */
        const val ACTION_START = "org.eu.nl.syu.zeroclaw.action.DEVICE_AGENT_START"

        /** Parks the loop (explicit pause). */
        const val ACTION_PAUSE = "org.eu.nl.syu.zeroclaw.action.DEVICE_AGENT_PAUSE"

        /** Resumes a parked loop and re-reads the screen. */
        const val ACTION_RESUME = "org.eu.nl.syu.zeroclaw.action.DEVICE_AGENT_RESUME"

        /** Stops the task and removes the overlay. */
        const val ACTION_STOP = "org.eu.nl.syu.zeroclaw.action.DEVICE_AGENT_STOP"

        /** Task description extra for [ACTION_START]. */
        const val EXTRA_GOAL = "extra_goal"

        /** Step-budget extra for [ACTION_START]. */
        const val EXTRA_MAX_STEPS = "extra_max_steps"

        /** Agent alias used for Reason calls via the gateway. */
        const val DEFAULT_AGENT_ALIAS = "default"
    }
}
