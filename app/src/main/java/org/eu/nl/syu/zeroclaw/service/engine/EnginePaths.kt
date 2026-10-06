/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.engine

import android.content.Context
import java.io.File

/**
 * Canonical filesystem layout for the bundled ZeroClaw engine.
 *
 * The engine owns its own `config.toml` (managed through the web dashboard or
 * the gateway config API) and its `data/` directory. The app never rewrites the
 * config on the engine's behalf except for a one-time bootstrap on first run, so
 * dashboard edits are never clobbered.
 *
 * @property configDir Directory passed to the engine as `--config-dir`; contains
 *   `config.toml`, `data/`, and `shared/`.
 * @property webDir Extracted copy of the bundled web dashboard served by the
 *   gateway via `gateway.web_dist_dir`.
 * @property logFile Append-only engine stdout/stderr capture.
 */
data class EnginePaths(
    val configDir: File,
    val webDir: File,
    val logFile: File,
) {
    /** `config.toml` inside [configDir]. */
    val configFile: File get() = File(configDir, "config.toml")

    /** Engine data directory inside [configDir]. */
    val dataDir: File get() = File(configDir, "data")

    /**
     * Absolute path of the executable engine binary.
     *
     * The engine is packaged as `libzeroclaw_engine.so` in the APK's native
     * library directory. Android extracts native libraries with execute
     * permission, which is the only location from which an app may `exec` on
     * API 29+.
     */
    fun executable(context: Context): File {
        val dir = File(context.applicationInfo.nativeLibraryDir)
        return File(dir, ENGINE_BINARY_NAME)
    }

    companion object {
        /** Native-library filename of the bundled engine executable. */
        const val ENGINE_BINARY_NAME = "libzeroclaw_engine.so"

        /** Extracts the layout for [context]. */
        fun from(context: Context): EnginePaths {
            val base = context.filesDir
            return EnginePaths(
                configDir = File(base, "zeroclaw"),
                webDir = File(base, "zeroclaw-web"),
                logFile = File(base, "zeroclaw-engine.log"),
            )
        }
    }
}
