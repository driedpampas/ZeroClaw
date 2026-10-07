/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.eu.nl.syu.zeroclaw.service.engine.GatewayClient
import org.eu.nl.syu.zeroclaw.util.LogSanitizer

/**
 * Events emitted while streaming an agent turn.
 */
sealed class StreamEvent {
    /** A chunk of thinking/reasoning tokens from the model. */
    data class ThinkingChunk(
        /** Thinking text chunk. */
        val text: String,
    ) : StreamEvent()

    /** A chunk of response content tokens from the model. */
    data class ResponseChunk(
        /** Response text chunk. */
        val text: String,
    ) : StreamEvent()

    /** The stream completed successfully. */
    data class Complete(
        /** Full accumulated response text. */
        val fullResponse: String,
    ) : StreamEvent()

    /** An error occurred during streaming. */
    data class Error(
        /** Human-readable error message. */
        val message: String,
    ) : StreamEvent()
}

/**
 * Bridges the gateway `/ws/chat` stream into the Kotlin reactive layer.
 *
 * @param client Gateway client; defaults to the loopback engine gateway.
 * @param ioDispatcher Dispatcher for the blocking stream call.
 */
class StreamingBridge(
    private val client: GatewayClient = GatewayClient(GatewayClient.loopback()),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _events =
        MutableSharedFlow<StreamEvent>(
            extraBufferCapacity = BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Observable stream of streaming events. */
    val events: SharedFlow<StreamEvent> = _events.asSharedFlow()

    /**
     * Sends one turn and emits [StreamEvent]s as frames arrive.
     *
     * @param agent Configured agent alias to run as.
     * @param message User message text.
     * @param sessionId Optional session id for memory continuity.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun send(
        agent: String,
        message: String,
        sessionId: String? = null,
    ) = withContext(ioDispatcher) {
        try {
            client.chatStream(agent, sessionId, message).collect { frame ->
                when (frame) {
                    is GatewayClient.ChatFrame.Chunk -> _events.emit(StreamEvent.ResponseChunk(frame.text))
                    is GatewayClient.ChatFrame.Thinking -> _events.emit(StreamEvent.ThinkingChunk(frame.text))
                    is GatewayClient.ChatFrame.Done -> _events.emit(StreamEvent.Complete(frame.fullResponse))
                    is GatewayClient.ChatFrame.Error -> _events.emit(StreamEvent.Error(frame.message))
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming error: ${LogSanitizer.sanitizeLogMessage(e.message ?: "unknown")}")
            _events.emit(StreamEvent.Error(e.message ?: "streaming failed"))
        }
    }

    /** Constants for [StreamingBridge]. */
    companion object {
        private const val TAG = "StreamingBridge"
        private const val BUFFER_CAPACITY = 256
    }
}
