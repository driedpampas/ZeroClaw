/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.engine

import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed client for the ZeroClaw gateway HTTP/WebSocket API.
 *
 * This replaces the previous in-process UniFFI binding. All calls target the
 * loopback gateway launched by [EngineProcessManager]; the engine owns the
 * configuration and exposes it through the `/api/config` endpoints, so dashboard edits and
 * app edits share one source of truth.
 *
 * @param baseUrl Gateway origin, e.g. `http://127.0.0.1:42617`.
 * @param httpClient Shared OkHttp client; defaults to a private client suited
 *   for loopback (short connect timeout, no retry spam).
 * @param ioDispatcher Dispatcher for blocking HTTP calls.
 */
class GatewayClient(
    private val baseUrl: String,
    private val httpClient: OkHttpClient = defaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** WebSocket origin derived from [baseUrl]. */
    val webSocketBaseUrl: String
        get() = baseUrl.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")

    // ---- Health / status -------------------------------------------------

    /** `GET /api/health`. */
    suspend fun health(): JSONObject = get("/api/health")

    /** `GET /api/status`. */
    suspend fun status(): JSONObject = get("/api/status")

    /** `GET /api/version/check`. */
    suspend fun versionCheck(): JSONObject = get("/api/version/check")

    /** `GET /api/channels`. */
    suspend fun channels(): JSONObject = get("/api/channels")

    /** `GET /api/tools`. */
    suspend fun tools(): JSONObject = get("/api/tools")

    /** `GET /api/memory`. */
    suspend fun memory(): JSONObject = get("/api/memory")

    /** `GET /api/cron`. */
    suspend fun cron(): JSONObject = get("/api/cron")

    /** `GET /api/cost`. */
    suspend fun cost(): JSONObject = get("/api/cost")

    /** `GET /api/skills/bundles`. */
    suspend fun skillBundles(): JSONObject = get("/api/skills/bundles")

    /** `GET /api/logs`. */
    suspend fun logs(): JSONObject = get("/api/logs")

    // ---- Config ----------------------------------------------------------

    /** `GET /api/config/status` — whether onboarding is required. */
    suspend fun configStatus(): JSONObject = get("/api/config/status")

    /** `GET /api/config/list` — full property list plus drift report. */
    suspend fun configList(): JSONObject = get("/api/config/list")

    /** `GET /api/config/drift` — in-memory vs on-disk differences. */
    suspend fun configDrift(): JSONObject = get("/api/config/drift")

    /** `GET /api/config/prop?path=...` — read a single property. */
    suspend fun configProp(path: String): JSONObject =
        get("/api/config/prop?path=${encode(path)}")

    /** `PUT /api/config/prop` — set a single property. */
    suspend fun setConfigProp(
        path: String,
        value: Any?,
        comment: String? = null,
    ): JSONObject {
        val body =
            JSONObject().apply {
                put("path", path)
                put("value", value ?: JSONObject.NULL)
                if (comment != null) put("comment", comment)
            }
        return putJson("/api/config/prop", body)
    }

    /**
     * `PATCH /api/config` — apply an atomic JSON Patch (RFC 6902) batch.
     *
     * @param operations Array of `{op, path[, value]}` objects.
     */
    suspend fun patchConfig(operations: JSONArray): JSONObject = patchJson("/api/config", operations)

    // ---- Events (SSE) ----------------------------------------------------

    /**
     * Streams `/api/events` server-sent events as raw JSON payloads.
     *
     * The flow completes when the caller cancels collection or the connection
     * closes; transport errors surface as [GatewayException] on the flow.
     */
    fun events(): Flow<JSONObject> =
        callbackFlow {
            val request =
                Request
                    .Builder()
                    .url("$baseUrl/api/events")
                    .header("Accept", "text/event-stream")
                    .get()
                    .build()
            val call = httpClient.newCall(request)
            val source = mutableListOf<Response>()
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        close(GatewayException("event stream failed: ${e.message}", cause = e))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        if (!response.isSuccessful) {
                            close(GatewayException(httpErrorCode(response), response.body?.string()))
                            return
                        }
                        source += response
                        Thread {
                            response.body?.source()?.use { body -> readSse(body) { trySend(it) } }
                            close()
                        }.apply { isDaemon = true }.start()
                    }
                },
            )
            awaitClose {
                source.forEach { runCatching { it.close() } }
                call.cancel()
            }
        }

    private fun readSse(
        source: BufferedSource,
        emit: (JSONObject) -> Unit,
    ) {
        try {
            var data = StringBuilder()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) {
                            runCatching { JSONObject(data.toString()) }.onSuccess(emit)
                            data = StringBuilder()
                        }
                    }
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "SSE reader closed: ${e.message}")
        }
    }

    // ---- Chat (WebSocket) ------------------------------------------------

    /** One decoded `/ws/chat` server frame. */
    sealed interface ChatFrame {
        data class Chunk(val text: String) : ChatFrame

        data class Thinking(val text: String) : ChatFrame

        data class ToolCall(val id: String?, val name: String?, val args: String?) : ChatFrame

        data class ToolResult(val id: String?, val name: String?, val output: String?) : ChatFrame

        data class Error(val message: String) : ChatFrame

        data class Done(val fullResponse: String) : ChatFrame
    }

    /**
     * Opens `/ws/chat`, sends one turn, and streams decoded frames until `done`.
     *
     * @param agent Configured agent alias (required by the gateway).
     * @param sessionId Optional session id for memory continuity.
     * @param message User message text.
     */
    fun chatStream(
        agent: String,
        sessionId: String?,
        message: String,
    ): Flow<ChatFrame> =
        callbackFlow {
            val query =
                buildString {
                    append("agent=").append(encode(agent))
                    if (!sessionId.isNullOrBlank()) append("&session_id=").append(encode(sessionId))
                }
            val request = Request.Builder().url("$webSocketBaseUrl/ws/chat?$query").build()
            val socket =
                httpClient.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            val payload =
                                JSONObject()
                                    .put("type", "message")
                                    .put("content", message)
                            webSocket.send(payload.toString())
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            val frame = decodeFrame(text)
                            if (frame != null) trySend(frame)
                            if (frame is ChatFrame.Done || frame is ChatFrame.Error) {
                                webSocket.close(1000, "done")
                            }
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            close(GatewayException("chat socket failed: ${t.message}", cause = t))
                        }

                        override fun onClosed(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            close()
                        }
                    },
                )
            awaitClose {
                runCatching { socket.close(1000, "client closed") }
            }
        }

    /**
     * Sends one chat turn and returns the assistant's full response.
     *
     * @throws GatewayException when the engine reports an error frame.
     */
    suspend fun chatOnce(
        agent: String,
        sessionId: String?,
        message: String,
    ): String {
        val terminal =
            chatStream(agent, sessionId, message)
                .first { it is ChatFrame.Done || it is ChatFrame.Error }
        return when (terminal) {
            is ChatFrame.Done -> terminal.fullResponse
            is ChatFrame.Error -> throw GatewayException(terminal.message)
            else -> ""
        }
    }

    private fun decodeFrame(text: String): ChatFrame? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return when (json.optString("type")) {
            "chunk" -> ChatFrame.Chunk(json.optString("content"))
            "thinking" -> ChatFrame.Thinking(json.optString("content"))
            "tool_call" ->
                ChatFrame.ToolCall(
                    json.optString("id").takeIf { it.isNotBlank() },
                    json.optString("name").takeIf { it.isNotBlank() },
                    json.opt("args")?.toString(),
                )
            "tool_result" ->
                ChatFrame.ToolResult(
                    json.optString("id").takeIf { it.isNotBlank() },
                    json.optString("name").takeIf { it.isNotBlank() },
                    json.opt("output")?.toString(),
                )
            "error" -> ChatFrame.Error(json.optString("message", "chat error"))
            "done" -> ChatFrame.Done(json.optString("full_response"))
            else -> null
        }
    }

    // ---- Low-level helpers ----------------------------------------------

    /** Performs `GET path` and parses the body as a JSON object. */
    suspend fun get(path: String): JSONObject = withContext(ioDispatcher) { execute(request("GET", path, null)) }

    /** Performs `DELETE path` and parses the (possibly empty) body as JSON. */
    suspend fun delete(path: String): JSONObject = withContext(ioDispatcher) { execute(request("DELETE", path, null)) }

    private suspend fun putJson(
        path: String,
        body: JSONObject,
    ): JSONObject = withContext(ioDispatcher) { execute(request("PUT", path, body.toString())) }

    /** `POST path` with a JSON object body. */
    suspend fun postJson(
        path: String,
        body: JSONObject,
    ): JSONObject = withContext(ioDispatcher) { execute(request("POST", path, body.toString())) }

    /** `PATCH path` with a JSON object body (resource patch, not JSON Patch). */
    suspend fun patchObject(
        path: String,
        body: JSONObject,
    ): JSONObject = withContext(ioDispatcher) { execute(request("PATCH", path, body.toString())) }

    private suspend fun patchJson(
        path: String,
        body: JSONArray,
    ): JSONObject = withContext(ioDispatcher) { execute(request("PATCH", path, body.toString())) }

    private fun request(
        method: String,
        path: String,
        body: String?,
    ): Request =
        Request
            .Builder()
            .url("$baseUrl$path")
            .header("Accept", "application/json")
            .apply {
                when (method) {
                    "GET" -> get()
                    "DELETE" -> delete()
                    "PUT" -> put((body ?: "{}").toRequestBody(jsonMediaType))
                    "PATCH" -> patch((body ?: "[]").toRequestBody(jsonMediaType))
                    "POST" -> post((body ?: "{}").toRequestBody(jsonMediaType))
                }
            }.build()

    private fun execute(request: Request): JSONObject =
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GatewayException(httpErrorCode(response), text)
            }
            when {
                text.isBlank() -> JSONObject()
                else -> runCatching { JSONObject(text) }.getOrElse { JSONObject().put("value", text) }
            }
        }

    private fun httpErrorCode(response: Response): String = "HTTP ${response.code}"

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    /** Gateway returned a non-2xx response; [body] carries the error payload. */
    class GatewayException(
        message: String,
        val body: String? = null,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    companion object {
        private const val TAG = "GatewayClient"

        /** Loopback origin for the default engine port. */
        fun loopback(port: Int = EngineProcessManager.DEFAULT_PORT): String = "http://127.0.0.1:$port"

        private fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
    }
}
