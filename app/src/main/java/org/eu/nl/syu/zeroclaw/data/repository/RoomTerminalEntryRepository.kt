/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eu.nl.syu.zeroclaw.data.local.dao.TerminalEntryDao
import org.eu.nl.syu.zeroclaw.data.local.entity.TerminalEntryEntity
import org.eu.nl.syu.zeroclaw.data.local.entity.toModel
import org.eu.nl.syu.zeroclaw.model.TerminalEntry

/**
 * Room-backed [TerminalEntryRepository] implementation.
 *
 * The [append] method is suspend because the ViewModel needs the generated
 * ID back for immediate UI update. The [clear] method is fire-and-forget
 * via the provided [ioScope].
 *
 * @param dao The data access object for terminal entry operations.
 * @param ioScope Coroutine scope for background database writes.
 */
class RoomTerminalEntryRepository(
    private val dao: TerminalEntryDao,
    private val ioScope: CoroutineScope,
) : TerminalEntryRepository {
    override val entries: Flow<List<TerminalEntry>> =
        dao.observeAll().map { entities ->
            entities.map { it.toModel() }
        }

    override suspend fun append(
        content: String,
        entryType: String,
        imageUris: List<String>,
    ): TerminalEntry {
        val now = System.currentTimeMillis()
        val urisJson = if (imageUris.isEmpty()) "[]" else Json.encodeToString(imageUris)
        val entity =
            TerminalEntryEntity(
                content = content,
                entryType = entryType,
                timestamp = now,
                imageUris = urisJson,
            )
        val generatedId = dao.insert(entity)
        return TerminalEntry(
            id = generatedId,
            content = content,
            entryType = entryType,
            timestamp = now,
            imageUris = imageUris,
        )
    }

    override fun clear() {
        ioScope.launch {
            dao.deleteAll()
        }
    }
}
