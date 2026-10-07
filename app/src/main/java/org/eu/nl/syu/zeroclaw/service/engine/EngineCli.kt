/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.engine

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs one-shot `zeroclaw` CLI commands against the managed config directory.
 *
 * Most app operations go through the gateway HTTP API, but a few (skill
 * install, onboarding/quickstart, doctor) are only exposed on the CLI. This
 * runner executes the same bundled binary with `--config-dir` and captures
 * stdout/stderr.
 *
 * @param executable Bundled engine binary.
 * @param configDir Engine config directory.
 * @param ioDispatcher Dispatcher for the blocking process wait.
 */
class EngineCli(
    private val executable: File,
    private val configDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Result of a one-shot CLI invocation. */
    data class Result(
        /** Process exit code; 0 means success. */
        val exitCode: Int,
        /** Captured standard output. */
        val stdout: String,
        /** Captured standard error. */
        val stderr: String,
    ) {
        /** Whether the command exited successfully. */
        val isSuccess: Boolean get() = exitCode == 0
    }

    /**
     * Runs `zeroclaw --config-dir <dir> <args...>` and captures output.
     *
     * @param args Subcommand and arguments.
     * @param timeoutSeconds Maximum wall time before the process is destroyed.
     * @throws IOException when the binary cannot be launched.
     */
    @Throws(IOException::class)
    suspend fun run(
        vararg args: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): Result =
        withContext(ioDispatcher) {
            val command = mutableListOf(executable.absolutePath, "--config-dir", configDir.absolutePath)
            command += args
            val builder = ProcessBuilder(command).redirectErrorStream(false)
            builder.directory(configDir)
            builder.environment()["HOME"] = configDir.parentFile?.absolutePath ?: configDir.absolutePath
            val process = builder.start()
            process.outputStream.close()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IOException("zeroclaw ${args.joinToString(" ")} timed out after ${timeoutSeconds}s")
            }
            Result(process.exitValue(), stdout, stderr)
        }

    /** Installs a skill from `source` (URL, path, or `bundle/name`). */
    suspend fun installSkill(source: String): Result = run("skills", "install", source)

    /** Removes an installed skill by name. */
    suspend fun removeSkill(name: String): Result = run("skills", "remove", name)

    /** Factory helpers for [EngineCli]. */
    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 120L

        /** Builds a CLI runner for [context] using the standard engine layout. */
        fun from(context: Context): EngineCli {
            val paths = EnginePaths.from(context)
            return EngineCli(
                executable = paths.executable(context),
                configDir = paths.configDir,
            )
        }
    }
}
