// Copyright 2026 ZeroClaw Community, MIT License

package org.eu.nl.syu.zeroclaw.service

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eu.nl.syu.zeroclaw.model.MemoryEntry
import org.eu.nl.syu.zeroclaw.service.engine.GatewayClient
import org.eu.nl.syu.zeroclaw.service.engine.listAt
import org.eu.nl.syu.zeroclaw.service.engine.string

/**
 * Bridge between the Android UI layer and the gateway memory API.
 *
 * Reads `GET /api/memory` and deletes through `DELETE /api/memory/{key}`. The
 * engine truncates returned content to a safe size before sending it, matching
 * the dashboard.
 *
 * @param ioDispatcher Dispatcher for blocking HTTP calls.
 * @param client Gateway client; defaults to the loopback engine gateway.
 */
class MemoryBridge(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val client: GatewayClient = GatewayClient(GatewayClient.loopback()),
) {
    /**
     * Lists memory entries, optionally filtered by category.
     *
     * @param category Optional category filter (e.g. "core", "daily", "conversation").
     * @param limit Maximum number of entries to return.
     * @param sessionId Optional session ID (currently unused by the gateway API).
     * @return List of [MemoryEntry] instances.
     * @throws IOException if the gateway is unreachable or returns an error.
     */
    @Throws(IOException::class)
    suspend fun listMemories(
        category: String? = null,
        limit: UInt = DEFAULT_LIMIT,
        sessionId: String? = null,
    ): List<MemoryEntry> = fetch(query = null, category = category, limit = limit)

    /**
     * Searches memory entries by keyword query.
     *
     * @param query Search keyword.
     * @param limit Maximum number of results to return.
     * @param sessionId Optional session ID (currently unused by the gateway API).
     * @return List of [MemoryEntry] instances ranked by relevance.
     * @throws IOException if the gateway is unreachable or returns an error.
     */
    @Throws(IOException::class)
    suspend fun recallMemory(
        query: String,
        limit: UInt = DEFAULT_LIMIT,
        sessionId: String? = null,
    ): List<MemoryEntry> = fetch(query = query, category = null, limit = limit)

    private suspend fun fetch(
        query: String?,
        category: String?,
        limit: UInt,
    ): List<MemoryEntry> =
        withContext(ioDispatcher) {
            val params = mutableListOf<String>()
            if (!query.isNullOrBlank()) params += "query=${encode(query)}"
            if (!category.isNullOrBlank()) params += "category=${encode(category)}"
            val path = if (params.isEmpty()) "/api/memory" else "/api/memory?${params.joinToString("&")}"
            client
                .get(path)
                .listAt("entries")
                .take(limit.toInt())
                .map { entry ->
                    MemoryEntry(
                        id = entry.string("id").orEmpty(),
                        key = entry.string("key").orEmpty(),
                        content = entry.string("content").orEmpty(),
                        category = entry.string("category").orEmpty(),
                        timestamp = entry.string("timestamp").orEmpty(),
                        score = entry.optDouble("score").takeUnless { it.isNaN() },
                    )
                }
        }

    /**
     * Deletes a memory entry by key.
     *
     * @param key The key of the memory entry to delete.
     * @return `true` if the entry was found and deleted, `false` otherwise.
     * @throws IOException if the gateway is unreachable or returns an error.
     */
    @Throws(IOException::class)
    suspend fun forgetMemory(key: String): Boolean =
        withContext(ioDispatcher) {
            runCatching { client.delete("/api/memory/${encode(key)}") }.isSuccess
        }

    /**
     * Returns the total number of memory entries.
     *
     * @return Total count of memory entries.
     * @throws IOException if the gateway is unreachable or returns an error.
     */
    @Throws(IOException::class)
    suspend fun memoryCount(): UInt =
        withContext(ioDispatcher) {
            val root = client.get("/api/memory")
            root.optInt("count", -1).takeIf { it >= 0 }?.toUInt()
                ?: root.listAt("entries").size.toUInt()
        }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    /** Constants for [MemoryBridge]. */
    companion object {
        /** Default maximum number of memory entries to retrieve. */
        private const val DEFAULT_LIMIT_INT = 100

        /** Default limit for memory queries. */
        val DEFAULT_LIMIT: UInt = DEFAULT_LIMIT_INT.toUInt()
    }
}
