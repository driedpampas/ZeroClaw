/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.engine

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eu.nl.syu.zeroclaw.model.ConnectedChannel
import org.eu.nl.syu.zeroclaw.service.ConfigTomlBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Applies the app's channel configuration to the running engine and binds the
 * channels to an agent.
 *
 * The engine owns `config.toml`, so the app cannot write it directly. Instead
 * this class uses the gateway config API: it reads the engine's property schema
 * (`GET /api/config/list`) to learn which `channels.<type>.<alias>.<field>`
 * paths and value kinds actually exist, writes the app's values for those
 * paths, and then sets `agents.<alias>.channels` to the bound channel
 * references.
 *
 * Only schema-known paths are written, so legacy or app-only keys that the
 * engine no longer models are ignored instead of failing the atomic patch, and
 * a channel is only referenced once its section is known to the engine —
 * avoiding dangling agent references.
 *
 * @param client Gateway client used to read and patch the engine config.
 * @param ioDispatcher Dispatcher for the blocking HTTP calls.
 */
class EngineChannelSync(
    private val client: GatewayClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Outcome of a channel sync attempt.
     *
     * @property boundRefs Channel references written to the agent, if any.
     * @property applied Whether the config patch was accepted by the engine.
     * @property error Failure detail when [applied] is false.
     */
    data class Result(
        val boundRefs: List<String>,
        val applied: Boolean,
        val error: String? = null,
    )

    /**
     * Writes the app's enabled channels to the engine and binds them to
     * [agentAlias].
     *
     * Never throws: transport and validation failures are returned in
     * [Result.error] so setup can continue and the Doctor can report the
     * problem.
     *
     * @param channels Enabled channels with their full (including secret) values.
     * @param agentAlias Agent alias to bind the channels to.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun syncChannels(
        channels: List<Pair<ConnectedChannel, Map<String, String>>>,
        agentAlias: String,
    ): Result =
        withContext(ioDispatcher) {
            try {
                val schemaKinds = fetchSchemaKinds()
                val ops = buildChannelOps(channels, schemaKinds, agentAlias)
                if (ops == null) {
                    return@withContext Result(emptyList(), false, "no engine-known channels to bind")
                }
                client.patchConfig(ops.operations)
                Result(ops.refs, true)
            } catch (e: Exception) {
                Log.w(TAG, "Channel sync failed: ${e.message}")
                Result(emptyList(), false, e.message)
            }
        }

    /** Reads the engine property schema as a `path -> kind` map. */
    private suspend fun fetchSchemaKinds(): Map<String, String> =
        client.configList()
            .listAt("entries")
            .mapNotNull { entry ->
                val path = entry.string("path") ?: return@mapNotNull null
                val kind = entry.string("kind") ?: return@mapNotNull null
                path to kind
            }.toMap()

    /**
     * Builds the JSON Patch operations that write the app's channel values and
     * bind them to [agentAlias].
     *
     * @param channels Channels and their values.
     * @param schemaKinds Engine schema as a `path -> kind` map.
     * @param agentAlias Target agent alias.
     * @return Operations plus bound refs, or null when no channel section is
     *   known to the engine.
     */
    internal fun buildChannelOps(
        channels: List<Pair<ConnectedChannel, Map<String, String>>>,
        schemaKinds: Map<String, String>,
        agentAlias: String,
    ): ChannelOps? {
        val operations = JSONArray()
        val refs = mutableListOf<String>()
        for ((channel, values) in channels) {
            val key = channel.type.tomlKey
            val prefix = "channels.$key.$key."
            // Only touch a channel the engine can model; an unknown section
            // would either be rejected or become a dangling agent reference.
            if (schemaKinds.keys.none { it.startsWith(prefix) }) continue
            refs += "$key.$key"
            val folded = ConfigTomlBuilder.foldLegacyChannelKeys(values)
            appendFieldOps(operations, prefix, folded, schemaKinds)
            // Enable last so required fields are materialized first.
            operations.put(op("add", "channels/$key/$key/enabled", true))
        }
        if (refs.isEmpty()) return null
        operations.put(op("add", "agents/$agentAlias/channels", JSONArray(refs)))
        return ChannelOps(operations, refs)
    }

    /** Appends write ops for the channel fields the engine schema recognizes. */
    private fun appendFieldOps(
        operations: JSONArray,
        prefix: String,
        values: Map<String, String>,
        schemaKinds: Map<String, String>,
    ) {
        for ((field, raw) in values) {
            val path = prefix + field
            val kind = schemaKinds[path]
            val value = if (raw.isBlank() || kind == null) null else jsonValue(raw, kind)
            if (value != null) operations.put(op("add", path.replace('.', '/'), value))
        }
    }

    /** Converts a stored string value to the JSON type the schema kind expects. */
    private fun jsonValue(raw: String, kind: String): Any? {
        val trimmed = raw.trim()
        return when (kind) {
            "bool" ->
                when {
                    trimmed.equals("true", ignoreCase = true) -> true
                    trimmed.equals("false", ignoreCase = true) -> false
                    else -> null
                }
            "integer" -> trimmed.toLongOrNull()
            "float" -> trimmed.toDoubleOrNull()
            "string-array" ->
                JSONArray(
                    trimmed
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                )
            else -> raw
        }
    }

    private fun op(
        operation: String,
        path: String,
        value: Any,
    ): JSONObject =
        JSONObject()
            .put("op", operation)
            .put("path", "/$path")
            .put("value", value)

    /**
     * Channel sync operations and the refs they bind.
     *
     * @property operations JSON Patch operations for `PATCH /api/config`.
     * @property refs Composite `<type>.<alias>` channel references.
     */
    internal data class ChannelOps(
        val operations: JSONArray,
        val refs: List<String>,
    )

    private companion object {
        private const val TAG = "EngineChannelSync"
    }
}
