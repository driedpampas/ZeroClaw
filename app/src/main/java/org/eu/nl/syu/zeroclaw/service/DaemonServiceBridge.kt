/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.eu.nl.syu.zeroclaw.model.ComponentStatus
import org.eu.nl.syu.zeroclaw.model.ConnectedChannel
import org.eu.nl.syu.zeroclaw.model.DaemonStatus
import org.eu.nl.syu.zeroclaw.model.KeyRejectionEvent
import org.eu.nl.syu.zeroclaw.model.MemoryConflict
import org.eu.nl.syu.zeroclaw.model.MemoryHealthResult
import org.eu.nl.syu.zeroclaw.model.ServiceState
import org.eu.nl.syu.zeroclaw.service.engine.EngineChannelSync
import org.eu.nl.syu.zeroclaw.service.engine.EngineCli
import org.eu.nl.syu.zeroclaw.service.engine.EngineConfigSync
import org.eu.nl.syu.zeroclaw.service.engine.EngineException
import org.eu.nl.syu.zeroclaw.service.engine.EnginePaths
import org.eu.nl.syu.zeroclaw.service.engine.EngineProcessManager
import org.eu.nl.syu.zeroclaw.service.engine.EngineStateException
import org.eu.nl.syu.zeroclaw.service.engine.GatewayClient
import org.eu.nl.syu.zeroclaw.service.engine.listAt
import org.eu.nl.syu.zeroclaw.service.engine.long
import org.eu.nl.syu.zeroclaw.service.engine.obj
import org.eu.nl.syu.zeroclaw.service.engine.string
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge between the Android service layer and the bundled ZeroClaw engine.
 *
 * The engine runs as a managed child process; this class owns its lifecycle and
 * exposes observable [StateFlow]s for daemon state and health. All data calls
 * go through the loopback gateway via [GatewayClient].
 *
 * @param context Application context used to resolve engine paths.
 * @param ioDispatcher Dispatcher for blocking process and HTTP work.
 */
class DaemonServiceBridge(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val dataDir: String = appContext.filesDir.absolutePath
    private val paths = EnginePaths.from(appContext)
    private val engine = EngineProcessManager(appContext, paths, ioDispatcher)

    /** Gateway client targeting the loopback engine gateway. */
    internal val gateway = GatewayClient(GatewayClient.loopback(), ioDispatcher = ioDispatcher)

    /** Engine CLI runner for operations without an HTTP endpoint. */
    internal val cli = EngineCli(paths.executable(appContext), paths.configDir, ioDispatcher)

    /** Watches engine-owned config for dashboard/API edits. */
    val configSync = EngineConfigSync(paths, gateway, ioDispatcher)

    /**
     * Optional [EventBridge] for daemon event streaming.
     *
     * Set after construction from `ZeroClawApplication.onCreate`.
     */
    var eventBridge: EventBridge? = null

    private val _serviceState = MutableStateFlow(ServiceState.STOPPED)

    /** Current lifecycle state of the daemon. */
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _restartRequired = MutableStateFlow(false)

    /** Emits `true` when settings change while the daemon is running. */
    val restartRequired: StateFlow<Boolean> = _restartRequired.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /** Most recent error message from a failed operation. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _lastStatus = MutableStateFlow<DaemonStatus?>(null)

    /** Most recently fetched daemon health snapshot. */
    val lastStatus: StateFlow<DaemonStatus?> = _lastStatus.asStateFlow()

    private val _keyRejections = MutableSharedFlow<KeyRejectionEvent>(extraBufferCapacity = 1)

    /** Stream of API key rejection events detected during [send] operations. */
    val keyRejections: SharedFlow<KeyRejectionEvent> = _keyRejections.asSharedFlow()

    private val _memoryConflict = MutableStateFlow<MemoryConflict?>(null)

    /** Pending memory conflict that requires user acknowledgment before startup. */
    val memoryConflict: StateFlow<MemoryConflict?> = _memoryConflict.asStateFlow()

    private val _memoryHealthWarning = MutableStateFlow<String?>(null)

    /** Warning message when the post-startup memory health check fails. */
    val memoryHealthWarning: StateFlow<String?> = _memoryHealthWarning.asStateFlow()

    @Volatile
    private var conflictDeferred: CompletableDeferred<Boolean>? = null

    /**
     * Emits a memory conflict for the UI to display and suspends until resolved.
     *
     * @param conflict The detected conflict descriptor.
     * @return `true` if the user confirmed deletion, `false` to keep.
     */
    internal suspend fun awaitConflictResolution(conflict: MemoryConflict.StaleData): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        conflictDeferred = deferred
        _memoryConflict.value = conflict
        return deferred.await()
    }

    /** Sets a post-startup memory health warning. */
    internal fun setMemoryHealthWarning(reason: String) {
        _memoryHealthWarning.value = reason
    }

    /** Called by the UI when the user responds to the memory conflict dialog. */
    fun resolveMemoryConflict(shouldDelete: Boolean) {
        conflictDeferred?.complete(shouldDelete)
        conflictDeferred = null
        _memoryConflict.value = null
    }

    /** Dismisses the memory health warning banner. */
    fun dismissMemoryHealthWarning() {
        _memoryHealthWarning.value = null
    }

    /** Probes the gateway to sync [serviceState] without launching anything. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun syncState() {
        try {
            val status = pollStatus()
            if (status.running && _serviceState.value != ServiceState.RUNNING) {
                Log.i(TAG, "syncState: engine already running (uptime=${status.uptimeSeconds}s)")
                _serviceState.value = ServiceState.RUNNING
            }
        } catch (e: Exception) {
            Log.d(TAG, "syncState: engine not running (${e.message})")
        }
    }

    /**
     * Starts the engine process and waits for the gateway to become healthy.
     *
     * @param configToml Ignored: the engine owns `config.toml`; the app configures
     *   it through the gateway config API or dashboard. Retained for call-site
     *   compatibility.
     * @param host Gateway bind address (loopback only in the app).
     * @param port Gateway bind port.
     * @throws EngineException if the engine fails to launch or become healthy.
     */
    @Throws(EngineException::class)
    suspend fun start(
        configToml: String,
        host: String,
        port: UShort,
    ) {
        _serviceState.value = ServiceState.STARTING
        try {
            withContext(ioDispatcher) { engine.start(host, port.toInt()) }
            waitForHealthy()
            _lastError.value = null
            _serviceState.value = ServiceState.RUNNING
            _restartRequired.value = false
            eventBridge?.register()
        } catch (e: EngineException) {
            _lastError.value = e.detail
            _serviceState.value = ServiceState.ERROR
            throw e
        } catch (e: IOException) {
            val wrapped = EngineException(e.message ?: "engine start failed", e)
            _lastError.value = wrapped.detail
            _serviceState.value = ServiceState.ERROR
            throw wrapped
        }
    }

    private suspend fun waitForHealthy() {
        repeat(HEALTH_POLL_ATTEMPTS) {
            if (runCatching { gateway.health() }.isSuccess) return
            delay(HEALTH_POLL_DELAY_MS)
        }
        throw EngineStateException("Engine gateway did not become healthy")
    }

    /** Stops the running engine process. */
    @Throws(EngineException::class)
    suspend fun stop() {
        _serviceState.value = ServiceState.STOPPING
        eventBridge?.unregister()
        try {
            withContext(ioDispatcher) { engine.stop() }
            _lastError.value = null
            _serviceState.value = ServiceState.STOPPED
            _lastStatus.value = null
        } catch (e: IOException) {
            val wrapped = EngineException(e.message ?: "engine stop failed", e)
            _lastError.value = wrapped.detail
            _serviceState.value = ServiceState.ERROR
            throw wrapped
        }
    }

    /**
     * Fetches the current daemon health from the gateway and updates [lastStatus].
     *
     * @return Parsed [DaemonStatus] snapshot.
     * @throws EngineException if the gateway is unreachable.
     */
    @Throws(EngineException::class)
    suspend fun pollStatus(): DaemonStatus {
        val root =
            try {
                withContext(ioDispatcher) { gateway.status() }
            } catch (e: IOException) {
                throw EngineException(e.message ?: "gateway status unavailable", e)
            }
        val health = root.obj("health") ?: root
        val componentsObj = health.obj("components")
        val components = mutableMapOf<String, ComponentStatus>()
        if (componentsObj != null) {
            for (key in componentsObj.keys()) {
                val component = componentsObj.optJSONObject(key) ?: continue
                components[key] = ComponentStatus(name = key, status = component.string("status").orEmpty())
            }
        }
        val status =
            DaemonStatus(
                running = true,
                uptimeSeconds = health.long("uptime_seconds", "uptimeSeconds") ?: 0L,
                components = components,
            )
        _lastStatus.value = status
        return status
    }

    /**
     * Sends a message to the engine and returns the agent response.
     *
     * @param message The message text to send.
     * @return The agent's response string.
     * @throws EngineException if delivery or generation fails.
     */
    @Throws(EngineException::class)
    suspend fun send(message: String): String =
        try {
            withContext(ioDispatcher) {
                gateway.chatOnce(resolveAgentAlias(), sessionId = null, message = message)
            }
        } catch (e: EngineException) {
            throw e
        } catch (e: IOException) {
            val detail = e.message ?: "chat failed"
            val errorType = ApiKeyErrorClassifier.classify(detail)
            if (errorType != null) {
                _keyRejections.tryEmit(KeyRejectionEvent(detail = detail, errorType = errorType))
            }
            throw EngineException(detail, e)
        }

    /**
     * Ensures the workspace directory exists and migrates legacy files.
     *
     * Identity files are now scaffolded by the engine; this only guarantees the
     * directory survives upgrades.
     */
    @Throws(EngineException::class)
    suspend fun ensureWorkspace(
        agentName: String,
        userName: String,
        timezone: String,
        communicationStyle: String,
    ) {
        withContext(ioDispatcher) { migrateOldWorkspace() }
    }

    /**
     * Returns the list of channel names configured in the running engine.
     *
     * @throws EngineException if the gateway is unreachable.
     */
    @Throws(EngineException::class)
    suspend fun configuredChannelNames(): List<String> =
        withContext(ioDispatcher) {
            try {
                gateway
                    .channels()
                    .listAt("channels")
                    .mapNotNull { it.string("type") ?: it.string("name") }
                    .distinct()
            } catch (e: IOException) {
                throw EngineException(e.message ?: "channels unavailable", e)
            }
        }

    private fun migrateOldWorkspace() {
        val oldDir = File("$dataDir/zeroclaw/workspace")
        val newDir = File("$dataDir/workspace")
        newDir.mkdirs()
        if (!oldDir.isDirectory) return
        oldDir.listFiles()?.forEach { src ->
            if (src.isFile) {
                val dst = File(newDir, src.name)
                if (!dst.exists()) {
                    src.copyTo(dst)
                }
            }
        }
    }

    /**
     * Detects stale memory backend artifacts in the workspace.
     *
     * @param configuredBackend The active memory backend identifier
     *   (`"sqlite"`, `"markdown"`, or `"none"`).
     * @return [MemoryConflict.StaleData] when leftover files are found.
     */
    fun detectMemoryConflict(configuredBackend: String): MemoryConflict {
        val workspace = File("$dataDir/workspace")
        if (!workspace.isDirectory) return MemoryConflict.None

        val sqliteFiles = findSqliteFiles(workspace)
        val markdownFiles = findMemoryMarkdownFiles(workspace)

        val staleFiles =
            when (configuredBackend) {
                "sqlite" -> markdownFiles
                "markdown" -> sqliteFiles
                "none" -> sqliteFiles + markdownFiles
                else -> emptyList()
            }

        if (staleFiles.isEmpty()) return MemoryConflict.None

        return MemoryConflict.StaleData(
            currentBackend = configuredBackend,
            staleBackend = resolveStaleBackend(configuredBackend, sqliteFiles, markdownFiles),
            staleFileCount = staleFiles.size,
            staleSizeBytes = staleFiles.sumOf { it.length() },
        )
    }

    private fun resolveStaleBackend(
        configuredBackend: String,
        sqliteFiles: List<File>,
        markdownFiles: List<File>,
    ): String =
        when (configuredBackend) {
            "sqlite" -> "markdown"
            "markdown" -> "sqlite"
            "none" ->
                when {
                    sqliteFiles.isNotEmpty() && markdownFiles.isNotEmpty() -> "both"
                    sqliteFiles.isNotEmpty() -> "sqlite"
                    else -> "markdown"
                }
            else -> "unknown"
        }

    /** Deletes stale memory backend files identified by a prior conflict scan. */
    fun cleanupStaleMemory(conflict: MemoryConflict.StaleData) {
        val workspace = File("$dataDir/workspace")
        when (conflict.staleBackend) {
            "sqlite" -> findSqliteFiles(workspace).forEach { it.delete() }
            "markdown" -> findMemoryMarkdownFiles(workspace).forEach { it.delete() }
            "both" -> {
                findSqliteFiles(workspace).forEach { it.delete() }
                findMemoryMarkdownFiles(workspace).forEach { it.delete() }
            }
        }
    }

    /**
     * Probes whether the configured memory backend's storage is writable.
     *
     * @param configuredBackend The active memory backend identifier.
     * @return Health probe result.
     */
    @Suppress("TooGenericExceptionCaught")
    fun checkMemoryHealth(configuredBackend: String): MemoryHealthResult {
        if (configuredBackend == "none") return MemoryHealthResult.Healthy

        return try {
            val targetDir =
                when (configuredBackend) {
                    "markdown" -> File("$dataDir/workspace/memory")
                    else -> File("$dataDir/workspace")
                }
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return MemoryHealthResult.Unhealthy(
                    "Cannot create $configuredBackend storage directory",
                )
            }
            val probe = File(targetDir, ".health_probe")
            try {
                probe.writeText("ok")
                val readBack = probe.readText()
                if (readBack == "ok") {
                    MemoryHealthResult.Healthy
                } else {
                    MemoryHealthResult.Unhealthy("Read-back mismatch in $configuredBackend storage")
                }
            } finally {
                probe.delete()
            }
        } catch (e: Exception) {
            MemoryHealthResult.Unhealthy("$configuredBackend storage not writable: ${e.message}")
        }
    }

    private fun findSqliteFiles(workspace: File): List<File> {
        val extensions = setOf("db", "db-wal", "db-shm")
        val rootFiles =
            workspace
                .listFiles()
                ?.filter { it.isFile && it.extension in extensions }
                .orEmpty()
        val stateDir = File(workspace, "state")
        val stateFiles =
            stateDir
                .listFiles()
                ?.filter { it.isFile && it.extension in extensions }
                .orEmpty()
        return rootFiles + stateFiles
    }

    private fun findMemoryMarkdownFiles(workspace: File): List<File> {
        val memoryDir = File(workspace, "memory")
        if (!memoryDir.isDirectory) return emptyList()
        return memoryDir
            .listFiles()
            ?.filter { it.isFile && it.extension == "md" }
            .orEmpty()
    }

    /** Marks that a restart is required to apply settings changes. */
    fun markRestartRequired() {
        if (_serviceState.value == ServiceState.RUNNING) {
            _restartRequired.value = true
        }
    }

    /**
     * Hot-swaps the default provider and model by patching the engine config.
     *
     * On success, clears [restartRequired]. On failure, falls back to marking a
     * restart required.
     *
     * @param provider Provider ID (e.g. "anthropic", "openai").
     * @param model Model ID (e.g. "claude-sonnet-4-20250514").
     * @param apiKey Optional API key override (not applied here).
     * @return `true` if the config patch was saved.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun hotSwapProvider(
        provider: String,
        model: String,
        apiKey: String? = null,
    ): Boolean =
        withContext(ioDispatcher) {
            try {
                val alias = resolveAgentAlias()
                val operations =
                    JSONArray().apply {
                        put(JSONObject().put("op", "replace").put("path", "/agents/$alias/model").put("value", model))
                        if (provider.isNotBlank()) {
                            put(JSONObject().put("op", "replace").put("path", "/agents/$alias/provider").put("value", provider))
                        }
                    }
                gateway.patchConfig(operations)
                _restartRequired.value = false
                true
            } catch (e: Exception) {
                Log.w(TAG, "Hot-swap failed; restart required: ${e.message}")
                markRestartRequired()
                false
            }
        }

    /**
     * Writes the app's channels to the engine config and binds them to the
     * active agent.
     *
     * The engine owns `config.toml`; this is how the app, as manager and
     * configurator, applies channel configuration and sets
     * `agents.<alias>.channels`. Failures are reported, not thrown, so setup
     * can continue and the Doctor can surface the problem.
     *
     * @param channels Enabled channels with their full (including secret) values.
     * @return The sync outcome.
     */
    suspend fun syncChannels(
        channels: List<Pair<ConnectedChannel, Map<String, String>>>,
    ): EngineChannelSync.Result {
        val alias = resolveAgentAlias()
        val result = EngineChannelSync(gateway, ioDispatcher).syncChannels(channels, alias)
        if (result.applied) {
            Log.i(TAG, "Bound channels to agent '$alias': ${result.boundRefs}")
        } else {
            Log.w(TAG, "Channel sync not applied: ${result.error}")
        }
        return result
    }

    /**
     * Stops and re-starts the engine with a fresh configuration.
     *
     * @param configToml Retained for compatibility; the engine owns its config.
     * @param host Gateway host address.
     * @param port Gateway port.
     * @throws EngineException if either stop or start fails.
     */
    @Throws(EngineException::class)
    suspend fun restart(
        configToml: String,
        host: String,
        port: UShort,
    ) {
        stop()
        start(configToml, host, port)
    }

    /**
     * Resolves the alias of a configured agent for chat/cron calls.
     *
     * Prefers the engine-reported `agent_alias` from `/api/status`, then the
     * first `agents.<alias>` entry in `/api/config/list`.
     *
     * @return The active agent alias, the first configured alias, or `default`.
     */
    internal suspend fun resolveAgentAlias(): String {
        val status = runCatching { gateway.status() }.getOrNull()
        status?.string("agent_alias", "agentAlias")?.takeIf { it.isNotBlank() }?.let { return it }
        val config = runCatching { gateway.configList() }.getOrNull()
        val firstAgent =
            config
                ?.listAt("entries")
                ?.mapNotNull { agentAliasFromConfigPath(it.string("path")) }
                ?.firstOrNull()
        return firstAgent ?: DEFAULT_AGENT
    }

    /** Constants and config-path helpers for [DaemonServiceBridge]. */
    companion object {
        private const val TAG = "DaemonServiceBridge"
        private const val DEFAULT_AGENT = "default"
        private const val HEALTH_POLL_ATTEMPTS = 60
        private const val HEALTH_POLL_DELAY_MS = 250L

        /**
         * Extracts the agent alias from a `/api/config/list` entry path.
         *
         * Entry paths look like `agents.<alias>.<field>` (e.g.
         * `agents.main.model`). Returns `null` for non-agent paths or the
         * bare `agents` prefix.
         */
        internal fun agentAliasFromConfigPath(path: String?): String? {
            if (path == null || !path.startsWith("agents.")) return null
            return path.removePrefix("agents.")
                .substringBefore(".")
                .takeIf { it.isNotBlank() }
        }
    }
}
