/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.eu.nl.syu.zeroclaw.MainActivity
import org.eu.nl.syu.zeroclaw.R

/**
 * Builds the ongoing notification for [DeviceAgentService].
 *
 * Active state shows step progress; paused state reads
 * "Agent paused. Tap to resume." with a Resume action so the user can
 * resume directly from the shade.
 *
 * @param context Application or service context.
 */
class DeviceAgentNotificationManager(
    private val context: Context,
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Creates the notification channel if absent. */
    fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.device_agent_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.device_agent_channel_description)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Builds the foreground notification for [state].
     *
     * @param state Current agent lifecycle state.
     * @param stepsExecuted Steps completed in the current task.
     * @param maxSteps Step budget for the current task.
     * @return A built [Notification] for `startForeground`.
     */
    fun buildNotification(
        state: DeviceAgentState,
        stepsExecuted: Int = 0,
        maxSteps: Int = DeviceAgentController.DEFAULT_MAX_STEPS,
    ): Notification {
        val contentIntent =
            PendingIntent.getActivity(
                context,
                REQUEST_CONTENT,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.device_agent_title))
                .setContentText(statusText(state, stepsExecuted, maxSteps))
                .setContentIntent(contentIntent)
                .setOngoing(state == DeviceAgentState.ACTIVE || state == DeviceAgentState.PAUSED)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(
                    NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE,
                )

        when (state) {
            DeviceAgentState.ACTIVE -> builder.addAction(actionPause())
            DeviceAgentState.PAUSED -> builder.addAction(actionResume())
            DeviceAgentState.IDLE,
            DeviceAgentState.DONE,
            DeviceAgentState.STUCK,
            DeviceAgentState.ERROR,
            -> builder.addAction(actionStop())
        }
        return builder.build()
    }

    /** Posts or updates the foreground notification. */
    fun notify(
        state: DeviceAgentState,
        stepsExecuted: Int = 0,
        maxSteps: Int = DeviceAgentController.DEFAULT_MAX_STEPS,
    ) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state, stepsExecuted, maxSteps))
    }

    private fun actionPause(): NotificationCompat.Action {
        val intent =
            PendingIntent.getService(
                context,
                REQUEST_PAUSE,
                Intent(context, DeviceAgentService::class.java).apply {
                    action = DeviceAgentService.ACTION_PAUSE
                },
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_stop,
            context.getString(R.string.device_agent_action_pause),
            intent,
        ).build()
    }

    private fun actionResume(): NotificationCompat.Action {
        val intent =
            PendingIntent.getService(
                context,
                REQUEST_RESUME,
                Intent(context, DeviceAgentService::class.java).apply {
                    action = DeviceAgentService.ACTION_RESUME
                },
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_retry,
            context.getString(R.string.device_agent_action_resume),
            intent,
        ).build()
    }

    private fun actionStop(): NotificationCompat.Action {
        val intent =
            PendingIntent.getService(
                context,
                REQUEST_STOP,
                Intent(context, DeviceAgentService::class.java).apply {
                    action = DeviceAgentService.ACTION_STOP
                },
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_stop,
            context.getString(R.string.device_agent_action_stop),
            intent,
        ).build()
    }

    private fun statusText(
        state: DeviceAgentState,
        stepsExecuted: Int,
        maxSteps: Int,
    ): String =
        when (state) {
            DeviceAgentState.ACTIVE ->
                context.getString(
                    R.string.device_agent_status_active,
                    stepsExecuted,
                    maxSteps,
                )
            DeviceAgentState.PAUSED ->
                context.getString(R.string.device_agent_status_paused)
            DeviceAgentState.DONE ->
                context.getString(R.string.device_agent_status_done)
            DeviceAgentState.STUCK ->
                context.getString(R.string.device_agent_status_stuck)
            DeviceAgentState.ERROR ->
                context.getString(R.string.device_agent_status_error)
            DeviceAgentState.IDLE ->
                context.getString(R.string.device_agent_status_idle)
        }

    /** Notification channel, ID, and request codes. */
    companion object {
        /** Notification channel for the on-device agent service. */
        const val CHANNEL_ID = "zeroclaw_device_agent"

        /** Ongoing notification ID for the agent service. */
        const val NOTIFICATION_ID = 2

        private const val REQUEST_CONTENT = 200
        private const val REQUEST_PAUSE = 201
        private const val REQUEST_RESUME = 202
        private const val REQUEST_STOP = 203
    }
}
