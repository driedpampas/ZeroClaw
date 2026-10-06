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
import org.eu.nl.syu.zeroclaw.model.ComponentHealth
import org.eu.nl.syu.zeroclaw.model.HealthDetail
import org.eu.nl.syu.zeroclaw.service.engine.GatewayClient
import org.eu.nl.syu.zeroclaw.service.engine.bool
import org.eu.nl.syu.zeroclaw.service.engine.long
import org.eu.nl.syu.zeroclaw.service.engine.obj
import org.eu.nl.syu.zeroclaw.service.engine.string

/**
 * Bridge between the Android UI layer and the gateway health API.
 *
 * Reads `GET /api/health`, which the gateway serves from the engine's in-process
 * health registry.
 *
 * @param ioDispatcher Dispatcher for blocking HTTP calls.
 * @param client Gateway client; defaults to the loopback engine gateway.
 */
class HealthBridge(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val client: GatewayClient = GatewayClient(GatewayClient.loopback()),
) {
    /**
     * Fetches structured health detail for all daemon components.
     *
     * @return Parsed [HealthDetail] snapshot.
     * @throws IOException if the gateway is unreachable or returns an error.
     */
    @Throws(IOException::class)
    suspend fun getHealthDetail(): HealthDetail =
        withContext(ioDispatcher) {
            val root = client.health()
            val health = root.obj("health") ?: root
            val componentsObj = health.obj("components")
            val components = mutableListOf<ComponentHealth>()
            if (componentsObj != null) {
                for (name in componentsObj.keys()) {
                    val component = componentsObj.optJSONObject(name) ?: continue
                    components +=
                        ComponentHealth(
                            name = name,
                            status = component.string("status").orEmpty(),
                            lastError = component.string("last_error", "lastError"),
                            restartCount = component.long("restart_count", "restartCount") ?: 0L,
                        )
                }
            }
            HealthDetail(
                daemonRunning = root.bool("daemon_running", "daemonRunning") ?: true,
                pid = health.long("pid") ?: 0L,
                uptimeSeconds = health.long("uptime_seconds", "uptimeSeconds") ?: 0L,
                components = components,
            )
        }
}
