/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.engine

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Detects configuration changes made outside the app.
 *
 * The engine owns `config.toml`, and the official web dashboard (or the gateway
 * config API) edits it directly. This watcher polls both the on-disk file
 * modification time and the gateway's merged config view
 * (`GET /api/config/list`) and emits a snapshot whenever either changes, so the
 * app can react without owning the config.
 *
 * @param paths Engine filesystem layout (for the config file mtime fast path).
 * @param client Gateway client.
 * @param ioDispatcher Dispatcher for blocking I/O and HTTP calls.
 */
class EngineConfigSync(
    private val paths: EnginePaths,
    private val client: GatewayClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Emits the current config list immediately, then again on every detected
     * change.
     *
     * @param pollIntervalMs Poll interval between change checks.
     */
    fun listen(pollIntervalMs: Long = DEFAULT_POLL_MS): Flow<JSONObject> =
        flow {
            var lastFileStamp = -1L
            var lastPayload: String? = null
            while (true) {
                val stamp = configFileStamp()
                val payload = runCatching { client.configList() }.getOrNull()
                val body = payload?.toString()
                if (lastPayload == null || stamp != lastFileStamp || body != lastPayload) {
                    if (payload != null) emit(payload)
                    lastFileStamp = stamp
                    lastPayload = body
                }
                delay(pollIntervalMs)
            }
        }.flowOn(ioDispatcher)

    /** Returns the gateway's current merged config view. */
    suspend fun current(): JSONObject = withContext(ioDispatcher) { client.configList() }

    private fun configFileStamp(): Long = File(paths.configDir, "config.toml").takeIf { it.isFile }?.lastModified() ?: -1L

    private companion object {
        private const val DEFAULT_POLL_MS = 3_000L
    }
}
