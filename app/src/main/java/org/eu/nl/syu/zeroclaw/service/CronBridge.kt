/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eu.nl.syu.zeroclaw.model.CronJob
import org.eu.nl.syu.zeroclaw.service.engine.GatewayClient
import org.eu.nl.syu.zeroclaw.service.engine.bool
import org.eu.nl.syu.zeroclaw.service.engine.listAt
import org.eu.nl.syu.zeroclaw.service.engine.obj
import org.eu.nl.syu.zeroclaw.service.engine.rfc3339ToEpochMs
import org.eu.nl.syu.zeroclaw.service.engine.string
import org.json.JSONObject

/**
 * Bridge between the Android UI layer and the gateway cron API.
 *
 * Maps `GET/POST /api/cron` and `PATCH/DELETE /api/cron/{id}`. Adding a job
 * requires the alias of a configured agent, which is resolved from
 * `/api/status` (falling back to `default`).
 *
 * @param ioDispatcher Dispatcher for blocking HTTP calls.
 * @param client Gateway client; defaults to the loopback engine gateway.
 */
class CronBridge(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val client: GatewayClient = GatewayClient(GatewayClient.loopback()),
) {
    /** Lists all cron jobs registered with the running daemon. */
    @Throws(IOException::class)
    suspend fun listJobs(): List<CronJob> =
        withContext(ioDispatcher) {
            client.cron().listAt("jobs").map { it.toModel() }
        }

    /** Retrieves a single cron job by its identifier, or null when absent. */
    @Throws(IOException::class)
    suspend fun getJob(id: String): CronJob? = listJobs().firstOrNull { it.id == id }

    /** Adds a new recurring cron job with the given expression and command. */
    @Throws(IOException::class)
    suspend fun addJob(
        expression: String,
        command: String,
    ): CronJob = add(schedule = expression, command = command, oneShot = false)

    /** Adds a one-shot job that fires once after the given delay (e.g. "5m"). */
    @Throws(IOException::class)
    suspend fun addOneShot(
        delay: String,
        command: String,
    ): CronJob = add(schedule = delay, command = command, oneShot = true)

    /** Adds a one-shot job that fires at a specific RFC 3339 timestamp. */
    @Throws(IOException::class)
    suspend fun addJobAt(
        timestampRfc3339: String,
        command: String,
    ): CronJob = add(schedule = timestampRfc3339, command = command, oneShot = true)

    /** Adds a fixed-interval repeating cron job. */
    @Throws(IOException::class)
    suspend fun addJobEvery(
        intervalMs: ULong,
        command: String,
    ): CronJob = add(schedule = "${intervalMs}ms", command = command, oneShot = false)

    /** Removes a cron job by its identifier. */
    @Throws(IOException::class)
    suspend fun removeJob(id: String) {
        withContext(ioDispatcher) { client.delete("/api/cron/${encode(id)}") }
    }

    /** Pauses a cron job so it will not fire until resumed. */
    @Throws(IOException::class)
    suspend fun pauseJob(id: String) {
        setEnabled(id, enabled = false)
    }

    /** Resumes a previously paused cron job. */
    @Throws(IOException::class)
    suspend fun resumeJob(id: String) {
        setEnabled(id, enabled = true)
    }

    private suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        withContext(ioDispatcher) {
            client.patchObject("/api/cron/${encode(id)}", JSONObject().put("enabled", enabled))
        }
    }

    private suspend fun add(
        schedule: String,
        command: String,
        oneShot: Boolean,
    ): CronJob =
        withContext(ioDispatcher) {
            val body =
                JSONObject().apply {
                    put("agent", resolveAgentAlias())
                    put("schedule", schedule)
                    put("command", command)
                    put("job_type", "shell")
                    if (oneShot) put("delete_after_run", true)
                }
            val response = client.postJson("/api/cron", body)
            (response.obj("job") ?: response).toModel()
        }

    /**
     * Resolves the alias of a configured agent.
     *
     * The cron API refuses jobs without an owning agent. Prefers the gateway's
     * active alias, then the first configured agent, then `default`.
     */
    private suspend fun resolveAgentAlias(): String {
        val status = runCatching { client.status() }.getOrNull()
        status?.string("agent_alias", "agentAlias")?.takeIf { it.isNotBlank() }?.let { return it }
        val config = runCatching { client.configList() }.getOrNull()
        val firstAgent =
            config
                ?.listAt("entries")
                ?.firstOrNull { it.string("path")?.startsWith("agents.") == true }
                ?.string("path")
                ?.removePrefix("agents.")
        return firstAgent ?: DEFAULT_AGENT
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun JSONObject.toModel(): CronJob =
        CronJob(
            id = string("id").orEmpty(),
            expression = string("expression", "schedule").orEmpty(),
            command = string("command").orEmpty(),
            nextRunMs = rfc3339ToEpochMs(string("next_run", "nextRun")) ?: 0L,
            lastRunMs = rfc3339ToEpochMs(string("last_run", "lastRun")),
            lastStatus = string("last_status", "lastStatus"),
            paused = !(bool("enabled") ?: true),
            oneShot =
                bool("delete_after_run", "deleteAfterRun") == true ||
                    string("job_type", "jobType") in setOf("one_shot", "oneshot", "one-shot"),
        )

    /** Constants for [CronBridge]. */
    companion object {
        private const val DEFAULT_AGENT = "default"
    }
}
