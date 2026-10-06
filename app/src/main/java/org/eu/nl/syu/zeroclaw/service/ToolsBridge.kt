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
import org.eu.nl.syu.zeroclaw.model.ToolSpec
import org.eu.nl.syu.zeroclaw.service.engine.GatewayClient
import org.eu.nl.syu.zeroclaw.service.engine.listAt
import org.eu.nl.syu.zeroclaw.service.engine.string

/**
 * Bridge between the Android UI layer and the gateway tools inventory.
 *
 * Reads `GET /api/tools`, which enumerates the engine's tool registry.
 *
 * @param ioDispatcher Dispatcher for blocking HTTP calls.
 * @param client Gateway client; defaults to the loopback engine gateway.
 */
class ToolsBridge(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val client: GatewayClient = GatewayClient(GatewayClient.loopback()),
) {
    /**
     * Lists all available tools based on daemon config and installed skills.
     *
     * @return List of all [ToolSpec] instances.
     * @throws IOException if the gateway is unreachable or returns an error.
     */
    @Throws(IOException::class)
    suspend fun listTools(): List<ToolSpec> =
        withContext(ioDispatcher) {
            client
                .tools()
                .listAt("tools")
                .map { tool ->
                    ToolSpec(
                        name = tool.string("name").orEmpty(),
                        description = tool.string("description").orEmpty(),
                        source = tool.string("source") ?: "builtin",
                        parametersJson =
                            tool.optJSONObject("parameters")?.toString()
                                ?: tool.optJSONArray("parameters")?.toString()
                                ?: "{}",
                        isActive = tool.optBoolean("active", true),
                        inactiveReason = tool.string("inactive_reason", "inactiveReason").orEmpty(),
                    )
                }
        }
}
