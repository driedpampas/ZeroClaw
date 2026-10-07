/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.ui.screen.dashboard

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.eu.nl.syu.zeroclaw.service.device.DeviceAgentController
import org.eu.nl.syu.zeroclaw.service.device.DeviceAgentService
import org.eu.nl.syu.zeroclaw.service.device.DeviceAgentState
import org.eu.nl.syu.zeroclaw.service.device.DeviceControlBridge

/**
 * Dashboard card for the on-device accessibility agent.
 *
 * Self-contained: it collects [DeviceAgentController.state] directly and
 * drives [DeviceAgentService] via intents, so no [DashboardState] changes
 * are required. Shows an accessibility-setup prompt when
 * [DeviceControlBridge] is disconnected, a goal field with a Start button
 * when idle, Pause/Resume controls while running, and a Resume button
 * when paused (mirroring the notification Resume action).
 */
@Composable
fun DeviceControlCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val agentState by DeviceAgentController.state.collectAsStateWithLifecycle()
    val connected = remember(agentState) { DeviceControlBridge.isConnected() }
    var goal by remember { mutableStateOf("") }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "On-device agent",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = deviceAgentStatusText(agentState, connected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (!connected) {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    },
                    modifier =
                        Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .semantics {
                                contentDescription = "Open accessibility settings"
                            },
                ) {
                    Text("Enable in Accessibility settings")
                }
                return@Column
            }

            when (agentState) {
                DeviceAgentState.IDLE,
                DeviceAgentState.DONE,
                DeviceAgentState.STUCK,
                DeviceAgentState.ERROR,
                -> {
                    OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it },
                        label = { Text("Agent task") },
                        placeholder = { Text("e.g. Open Settings and turn on Wi-Fi") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                val startIntent =
                                    Intent(context, DeviceAgentService::class.java).apply {
                                        action = DeviceAgentService.ACTION_START
                                        putExtra(DeviceAgentService.EXTRA_GOAL, goal)
                                    }
                                context.startForegroundService(startIntent)
                            },
                            enabled = goal.isNotBlank(),
                            modifier =
                                Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .semantics {
                                        contentDescription = "Start agent task"
                                    },
                        ) {
                            Text("Start agent task")
                        }
                    }
                }
                DeviceAgentState.ACTIVE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                context.startService(
                                    Intent(context, DeviceAgentService::class.java).apply {
                                        action = DeviceAgentService.ACTION_PAUSE
                                    },
                                )
                            },
                            modifier =
                                Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .semantics {
                                        contentDescription = "Pause agent"
                                    },
                        ) {
                            Text("Pause agent")
                        }
                        TextButton(
                            onClick = {
                                context.startService(
                                    Intent(context, DeviceAgentService::class.java).apply {
                                        action = DeviceAgentService.ACTION_STOP
                                    },
                                )
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text("Stop agent")
                        }
                    }
                }
                DeviceAgentState.PAUSED -> {
                    Text(
                        text = "Paused — the screen may have changed. Resume re-reads it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                context.startService(
                                    Intent(context, DeviceAgentService::class.java).apply {
                                        action = DeviceAgentService.ACTION_RESUME
                                    },
                                )
                            },
                            modifier =
                                Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .semantics {
                                        contentDescription = "Resume agent"
                                    },
                        ) {
                            Text("Resume agent")
                        }
                        TextButton(
                            onClick = {
                                context.startService(
                                    Intent(context, DeviceAgentService::class.java).apply {
                                        action = DeviceAgentService.ACTION_STOP
                                    },
                                )
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text("Stop agent")
                        }
                    }
                }
            }
        }
    }
}

private fun deviceAgentStatusText(
    state: DeviceAgentState,
    connected: Boolean,
): String =
    when {
        !connected -> "Requires the ZeroClaw accessibility service."
        state == DeviceAgentState.ACTIVE -> "Running — touching the screen pauses it."
        state == DeviceAgentState.PAUSED -> "Paused — tap Resume to continue."
        state == DeviceAgentState.DONE -> "Last task finished."
        state == DeviceAgentState.STUCK -> "Last task stopped (no progress)."
        state == DeviceAgentState.ERROR -> "Last task hit an error."
        else -> "Idle. The border appears while running."
    }
