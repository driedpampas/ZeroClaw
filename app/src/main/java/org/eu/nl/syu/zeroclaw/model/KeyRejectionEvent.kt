/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.model

import org.eu.nl.syu.zeroclaw.service.KeyErrorType

/**
 * Represents a provider rejecting an API key during an FFI operation.
 *
 * Emitted by [DaemonServiceBridge][org.eu.nl.syu.zeroclaw.service.DaemonServiceBridge]
 * when the daemon's [send][org.eu.nl.syu.zeroclaw.service.DaemonServiceBridge.send]
 * method encounters an authentication or rate-limit error.
 *
 * @property detail The original error message from the FFI layer.
 * @property errorType Classification of the rejection.
 * @property timestamp Epoch milliseconds when the rejection was detected.
 */
data class KeyRejectionEvent(
    val detail: String,
    val errorType: KeyErrorType,
    val timestamp: Long = System.currentTimeMillis(),
)
