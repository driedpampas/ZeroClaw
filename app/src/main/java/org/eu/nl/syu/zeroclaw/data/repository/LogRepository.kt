/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.data.repository

import kotlinx.coroutines.flow.Flow
import org.eu.nl.syu.zeroclaw.model.LogEntry
import org.eu.nl.syu.zeroclaw.model.LogSeverity

/**
 * Repository interface for application log entries.
 *
 * Implementations store log entries in a bounded buffer and provide
 * a [Flow] that emits the current entries on every change.
 */
interface LogRepository {
    /** Observable stream of all log entries, newest first. */
    val entries: Flow<List<LogEntry>>

    /**
     * Appends a new log entry.
     *
     * Thread-safe: may be called from any thread.
     *
     * @param severity Log severity level.
     * @param tag Source tag.
     * @param message Log message.
     */
    fun append(
        severity: LogSeverity,
        tag: String,
        message: String,
    )

    /** Clears all log entries. */
    fun clear()
}
