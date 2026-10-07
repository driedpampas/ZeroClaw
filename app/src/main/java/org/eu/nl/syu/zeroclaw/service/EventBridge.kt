/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.eu.nl.syu.zeroclaw.data.repository.ActivityRepository
import org.eu.nl.syu.zeroclaw.model.ActivityType
import org.eu.nl.syu.zeroclaw.model.DaemonEvent
import org.eu.nl.syu.zeroclaw.service.engine.GatewayClient
import org.eu.nl.syu.zeroclaw.service.engine.rfc3339ToEpochMs
import org.json.JSONObject

/**
 * Bridges the engine's gateway event stream (`GET /api/events`, SSE) into the
 * Kotlin reactive layer.
 *
 * Events are parsed into [DaemonEvent] instances, emitted on a [SharedFlow] for
 * ViewModels, and persisted to [ActivityRepository] for the dashboard feed.
 *
 * @param activityRepository Repository for persisting events to the activity feed.
 * @param scope Coroutine scope that owns the SSE collection job.
 * @param client Gateway client; defaults to the loopback engine gateway.
 */
class EventBridge(
    private val activityRepository: ActivityRepository,
    private val scope: CoroutineScope,
    private val client: GatewayClient = GatewayClient(GatewayClient.loopback()),
) {
    private val _events =
        MutableSharedFlow<DaemonEvent>(
            extraBufferCapacity = BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Observable stream of daemon events parsed from the gateway event stream. */
    val events: SharedFlow<DaemonEvent> = _events.asSharedFlow()

    private val syntheticId = AtomicLong(0)

    @Volatile
    private var streamJob: Job? = null

    /**
     * Starts collecting the gateway event stream.
     *
     * Safe to call repeatedly; an existing collection is replaced.
     */
    fun register() {
        unregister()
        streamJob =
            scope.launch {
                runCatching {
                    client.events().collect { payload -> handleEvent(payload) }
                }.onFailure { error ->
                    Log.w(TAG, "Event stream ended: ${error.message}")
                }
            }
    }

    /** Stops collecting the gateway event stream. */
    fun unregister() {
        streamJob?.cancel()
        streamJob = null
    }

    private fun handleEvent(payload: JSONObject) {
        val event = parseEvent(payload) ?: return
        scope.launch {
            _events.emit(event)
            val (type, message) = event.toActivityRecord()
            activityRepository.record(type, message)
        }
    }

    private fun parseEvent(payload: JSONObject): DaemonEvent? {
        val eventObj = payload.optJSONObject("event") ?: JSONObject()
        val attributes = payload.optJSONObject("attributes") ?: JSONObject()
        val data = mutableMapOf<String, String>()
        payload.optString("message").takeIf { it.isNotBlank() }?.let { data["message"] = it }
        payload.optString("severity_text").takeIf { it.isNotBlank() }?.let { data["severity"] = it }
        for (source in listOf(eventObj, attributes)) {
            for (key in source.keys()) {
                data.putIfAbsent(key, source.optString(key, ""))
            }
        }
        val kind =
            eventObj.optString("category").takeIf { it.isNotBlank() }
                ?: payload.optString("kind").takeIf { it.isNotBlank() }
                ?: "event"
        val timestamp =
            rfc3339ToEpochMs(payload.optString("@timestamp").takeIf { it.isNotBlank() })
                ?: System.currentTimeMillis()
        val id =
            payload.optString("id").toLongOrNull()
                ?: syntheticId.incrementAndGet()
        return DaemonEvent(id = id, timestampMs = timestamp, kind = kind, data = data)
    }

    /** Constants for [EventBridge]. */
    companion object {
        private const val BUFFER_CAPACITY = 64
        private const val TAG = "EventBridge"
    }
}

/**
 * Maps a [DaemonEvent] to an [ActivityType] and human-readable message for persistence.
 *
 * @receiver The daemon event to convert.
 * @return Pair of [ActivityType] and formatted message string.
 */
private fun DaemonEvent.toActivityRecord(): Pair<ActivityType, String> = toActivityType() to toActivityMessage()

private fun DaemonEvent.toActivityType(): ActivityType =
    when (kind) {
        "error" -> ActivityType.DAEMON_ERROR
        else -> ActivityType.FFI_CALL
    }

private fun DaemonEvent.toActivityMessage(): String =
    when (kind) {
        "llm_request" -> "LLM Request: ${data["provider"]} / ${data["model"]}"
        "llm_response" -> "LLM Response: ${data["provider"]} (${data["duration_ms"]}ms)"
        "tool_call" -> "Tool: ${data["tool"]} (${data["duration_ms"]}ms)"
        "tool_call_start" -> "Tool Starting: ${data["tool"]}"
        "channel_message" -> "Channel: ${data["channel"]} (${data["direction"]})"
        "error" -> "Error: ${data["component"]} — ${sanitizeActivityMessage(data["message"])}"
        "heartbeat_tick" -> "Heartbeat"
        "turn_complete" -> "Turn Complete"
        "agent_start" -> "Agent Start: ${data["provider"]} / ${data["model"]}"
        "agent_end" -> "Agent End (${data["duration_ms"]}ms)"
        else -> data["message"]?.takeIf { it.isNotBlank() }?.let { "Event: $it" } ?: "Event: $kind"
    }

/** Maximum length for error messages recorded in the activity feed. */
private const val MAX_ACTIVITY_MESSAGE_LENGTH = 120

/** Pattern matching URLs in error messages. */
private val URL_PATTERN = Regex("""https?://\S+""")

/**
 * Truncates and strips URLs from an error message for activity feed display.
 *
 * @param msg Raw error message from the daemon event, or `null`.
 * @return Sanitised message safe for the activity feed.
 */
private fun sanitizeActivityMessage(msg: String?): String {
    if (msg.isNullOrBlank()) return "unknown"
    val stripped = msg.replace(URL_PATTERN, "[url]")
    return if (stripped.length > MAX_ACTIVITY_MESSAGE_LENGTH) {
        stripped.take(MAX_ACTIVITY_MESSAGE_LENGTH) + "..."
    } else {
        stripped
    }
}
