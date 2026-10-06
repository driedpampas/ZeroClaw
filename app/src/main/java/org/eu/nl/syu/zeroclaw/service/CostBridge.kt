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
import org.eu.nl.syu.zeroclaw.model.CostSummary
import org.eu.nl.syu.zeroclaw.service.engine.GatewayClient
import org.eu.nl.syu.zeroclaw.service.engine.int
import org.eu.nl.syu.zeroclaw.service.engine.long
import org.eu.nl.syu.zeroclaw.service.engine.obj

/**
 * Bridge between the Android UI layer and the gateway cost-tracking API.
 *
 * Reads `GET /api/cost`, which the engine computes from its cost ledger.
 *
 * @param ioDispatcher Dispatcher for blocking HTTP calls.
 * @param client Gateway client; defaults to the loopback engine gateway.
 */
class CostBridge(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val client: GatewayClient = GatewayClient(GatewayClient.loopback()),
) {
    /**
     * Fetches the aggregated cost summary for the current daemon session.
     *
     * @return Parsed [CostSummary] snapshot.
     * @throws IOException if the gateway is unreachable or returns an error.
     */
    @Throws(IOException::class)
    suspend fun getCostSummary(): CostSummary =
        withContext(ioDispatcher) {
            val root = client.cost()
            val cost = root.obj("cost") ?: root
            CostSummary(
                sessionCostUsd = cost.optDouble("session_cost_usd", 0.0),
                dailyCostUsd = cost.optDouble("daily_cost_usd", 0.0),
                monthlyCostUsd = cost.optDouble("monthly_cost_usd", 0.0),
                totalTokens = cost.long("total_tokens", "totalTokens") ?: 0L,
                requestCount = cost.int("request_count", "requestCount") ?: 0,
                modelBreakdownJson =
                    (cost.obj("by_model") ?: cost.obj("byModel"))?.toString() ?: "{}",
            )
        }
}
