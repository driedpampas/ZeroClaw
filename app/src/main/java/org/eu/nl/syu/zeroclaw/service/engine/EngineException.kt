/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.engine

import java.io.IOException

/**
 * Error raised by the gateway-native service layer.
 *
 * Replaces the former UniFFI `FfiException` hierarchy. [detail] carries the
 * engine- or transport-supplied message so callers can classify it (for example
 * to detect API-key rejections).
 *
 * @param detail Human-readable error detail.
 * @param cause Optional underlying cause.
 */
open class EngineException(
    val detail: String,
    cause: Throwable? = null,
) : IOException(detail, cause)

/**
 * Engine error indicating a lifecycle/state problem (not running, already
 * running, shutdown in progress).
 */
class EngineStateException(
    detail: String,
    cause: Throwable? = null,
) : EngineException(detail, cause)
