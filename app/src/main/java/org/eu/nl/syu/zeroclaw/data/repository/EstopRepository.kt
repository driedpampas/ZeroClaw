/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.data.repository

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eu.nl.syu.zeroclaw.service.engine.EngineCli

/**
 * Repository for emergency stop state, backed by the engine CLI.
 *
 * Exposes [engaged] as a [StateFlow] that updates every [POLL_INTERVAL_MS]
 * milliseconds. The estop state lives in the engine config/state, so it is
 * read and mutated through the bundled `zeroclaw estop` command.
 *
 * @param scope Coroutine scope for the polling loop.
 * @param ioDispatcher Dispatcher for blocking CLI calls.
 * @param cli Engine CLI runner; when null, polling reports "not engaged".
 */
class EstopRepository(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cli: EngineCli? = null,
) {
    private val polling = AtomicBoolean(false)

    private val _engaged = MutableStateFlow(false)

    /** Whether the emergency stop is currently engaged. */
    val engaged: StateFlow<Boolean> = _engaged.asStateFlow()

    private val _engagedAtMs = MutableStateFlow<Long?>(null)

    /** Epoch milliseconds when estop was last engaged. */
    val engagedAtMs: StateFlow<Long?> = _engagedAtMs.asStateFlow()

    /**
     * Starts polling estop status from the engine.
     *
     * Safe to call multiple times; only one polling loop runs.
     */
    @Suppress("TooGenericExceptionCaught")
    fun startPolling() {
        if (!polling.compareAndSet(false, true)) return
        scope.launch(ioDispatcher) {
            while (true) {
                try {
                    val result = cli?.run("estop", "status")
                    val output = (result?.stdout.orEmpty() + result?.stderr.orEmpty())
                    _engaged.value = isEngagedOutput(output)
                } catch (e: Exception) {
                    Log.w(TAG, "Estop poll failed: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Engages the emergency stop at the strongest level.
     *
     * @return `true` if successfully engaged.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun engage(): Boolean =
        withContext(ioDispatcher) {
            val runner = cli ?: return@withContext false
            try {
                val result = runner.run("estop", "--level", "kill-all")
                if (result.isSuccess) {
                    _engaged.value = true
                    true
                } else {
                    Log.e(TAG, "Failed to engage estop: ${result.stderr}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to engage estop: ${e.message}")
                false
            }
        }

    /**
     * Resumes from the emergency stop.
     *
     * @return `true` if successfully resumed.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun resume(): Boolean =
        withContext(ioDispatcher) {
            val runner = cli ?: return@withContext false
            try {
                val result = runner.run("estop", "resume")
                if (result.isSuccess) {
                    _engaged.value = false
                    _engagedAtMs.value = null
                    true
                } else {
                    Log.e(TAG, "Failed to resume estop: ${result.stderr}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume estop: ${e.message}")
                false
            }
        }

    private fun isEngagedOutput(output: String): Boolean {
        val lower = output.lowercase()
        if (lower.contains("disabled") || lower.contains("not engaged") || lower.contains("inactive")) {
            return false
        }
        return lower.contains("engaged") ||
            lower.contains("kill-all") ||
            lower.contains("network-kill") ||
            lower.contains("domain-block") ||
            lower.contains("tool-freeze")
    }

    /** Constants for [EstopRepository]. */
    companion object {
        private const val TAG = "EstopRepository"
        private const val POLL_INTERVAL_MS = 2000L
    }
}
