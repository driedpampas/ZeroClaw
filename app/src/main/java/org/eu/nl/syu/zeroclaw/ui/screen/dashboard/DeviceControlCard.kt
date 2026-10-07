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
 * Self-contained: it collects [DeviceAgentController.state] and
 * [DeviceControlBridge.connected] directly and drives
 * [DeviceAgentService] via intents, so no [DashboardState] changes are
 * required. The enable-accessibility prompt appears only while the
 * service is disabled — on first setup or after a system revocation —
 * and hides automatically once the service connects.
 */
@Composable
fun DeviceControlCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val agentState by DeviceAgentController.state.collectAsStateWithLifecycle()
    val connected by DeviceControlBridge.connected.collectAsStateWithLifecycle()
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

            if (!connected) {
                EnableAccessibilityBox()
            } else {
                AgentControls(
                    agentState = agentState,
                    goal = goal,
                    onGoalChange = { goal = it },
                )
            }
        }
    }
}

/**
 * Enable prompt shown only while the accessibility service is disabled.
 *
 * @param modifier Modifier applied to the prompt column.
 */
@Composable
private fun EnableAccessibilityBox(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier = modifier) {
        Text(
            text =
                "The on-device agent needs the ZeroClaw accessibility " +
                    "service to read the screen and tap, swipe, and type. " +
                    "If you did not turn it off, the system may have " +
                    "revoked it — re-enable it here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
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
    }
}

/**
 * Task controls shown while the accessibility service is connected.
 *
 * @param agentState Current agent lifecycle state.
 * @param goal Task description draft.
 * @param onGoalChange Callback for goal edits.
 * @param modifier Modifier applied to the controls column.
 */
@Composable
private fun AgentControls(
    agentState: DeviceAgentState,
    goal: String,
    onGoalChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier) {
        Text(
            text = deviceAgentStatusText(agentState),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        when (agentState) {
            DeviceAgentState.IDLE,
            DeviceAgentState.DONE,
            DeviceAgentState.STUCK,
            DeviceAgentState.ERROR,
            -> {
                OutlinedTextField(
                    value = goal,
                    onValueChange = onGoalChange,
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

private fun deviceAgentStatusText(state: DeviceAgentState): String =
    when (state) {
        DeviceAgentState.ACTIVE -> "Running — touching the screen pauses it."
        DeviceAgentState.PAUSED -> "Paused — tap Resume to continue."
        DeviceAgentState.DONE -> "Last task finished."
        DeviceAgentState.STUCK -> "Last task stopped (no progress)."
        DeviceAgentState.ERROR -> "Last task hit an error."
        DeviceAgentState.IDLE -> "Idle. The border appears while running."
    }
