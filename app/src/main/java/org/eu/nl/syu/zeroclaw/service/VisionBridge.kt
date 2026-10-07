/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eu.nl.syu.zeroclaw.model.ProcessedImage
import org.eu.nl.syu.zeroclaw.service.engine.EngineException

/**
 * Bridge for multimodal vision messages.
 *
 * The gateway-native transport (`/ws/chat`) is text-only, so direct-to-provider
 * vision calls are not available in this architecture. This bridge is retained
 * so call sites compile and fails with a clear, user-facing error until vision
 * is routed through an engine capability.
 *
 * @param ioDispatcher Dispatcher for blocking work.
 */
class VisionBridge(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Sends a vision (image + text) message.
     *
     * @param text Prompt text (retained for API compatibility; unused until vision transport exists).
     * @param images Images that would be sent (count reported in the error).
     * @throws EngineException always, until a gateway vision transport exists.
     */
    @Throws(EngineException::class)
    @Suppress("UnusedParameter")
    suspend fun send(
        text: String,
        images: List<ProcessedImage>,
    ): String =
        withContext(ioDispatcher) {
            throw EngineException(
                "Image messages are not supported by the gateway transport yet " +
                    "(${images.size} image(s) dropped)",
            )
        }
}
