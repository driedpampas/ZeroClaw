/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.eu.nl.syu.zeroclaw.data.ProviderRegistry
import org.eu.nl.syu.zeroclaw.data.repository.AgentRepository
import org.eu.nl.syu.zeroclaw.data.repository.ApiKeyRepository
import org.eu.nl.syu.zeroclaw.model.Agent
import org.eu.nl.syu.zeroclaw.model.ApiKey
import org.eu.nl.syu.zeroclaw.model.CheckStatus
import org.eu.nl.syu.zeroclaw.model.DiagnosticCategory
import org.eu.nl.syu.zeroclaw.model.DiagnosticCheck
import org.eu.nl.syu.zeroclaw.model.KeyStatus
import org.eu.nl.syu.zeroclaw.model.ProviderAuthType
import org.eu.nl.syu.zeroclaw.model.isExpired
import org.eu.nl.syu.zeroclaw.model.isOAuthToken
import org.eu.nl.syu.zeroclaw.service.engine.EngineException
import org.eu.nl.syu.zeroclaw.service.engine.GatewayClient
import org.eu.nl.syu.zeroclaw.service.engine.arr
import org.eu.nl.syu.zeroclaw.service.engine.listAt
import org.eu.nl.syu.zeroclaw.service.engine.long
import org.eu.nl.syu.zeroclaw.service.engine.obj
import org.eu.nl.syu.zeroclaw.service.engine.rfc3339ToEpochMs
import org.eu.nl.syu.zeroclaw.service.engine.string
import org.eu.nl.syu.zeroclaw.util.BatteryOptimization
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs diagnostic checks against the ZeroClaw configuration, API keys,
 * connectivity, daemon health, and system prerequisites.
 *
 * Each check category produces a list of [DiagnosticCheck] results that
 * can be displayed in the Doctor screen. All FFI calls and I/O are
 * dispatched to [ioDispatcher].
 *
 * @param context Application context for system service access.
 * @param agentRepository Repository for reading agent configurations.
 * @param apiKeyRepository Repository for reading API key status.
 * @param ioDispatcher Dispatcher for blocking FFI calls and I/O.
 * @param gateway Gateway client for live engine state.
 */
class DoctorValidator(
    private val context: Context,
    private val agentRepository: AgentRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val gateway: GatewayClient = GatewayClient(GatewayClient.loopback(), ioDispatcher = ioDispatcher),
) {
    /**
     * Validates each agent's TOML config individually and the combined config.
     *
     * Uses the FFI [validateConfig] function which parses TOML without
     * starting the daemon.
     *
     * @param preloadedAgents Optional pre-loaded agent list to avoid a
     *   redundant database query. When null, agents are fetched from
     *   [agentRepository].
     * @return List of diagnostic checks for the config category.
     */
    suspend fun runConfigChecks(
        preloadedAgents: List<Agent>? = null,
    ): List<DiagnosticCheck> {
        val checks = mutableListOf<DiagnosticCheck>()
        val agents = preloadedAgents ?: agentRepository.agents.first()

        checks.add(checkNameUniqueness(agents))

        val enabledAgents = agents.filter { it.isEnabled }
        for (agent in enabledAgents) {
            checks.add(checkAgentConfig(agent))
        }

        return checks
    }

    /**
     * Checks that API keys exist for each enabled agent's provider.
     *
     * Self-hosted providers (those with [ProviderAuthType.URL_ONLY] or
     * [ProviderAuthType.NONE]) are exempt from the key requirement.
     *
     * @param preloadedAgents Optional pre-loaded agent list to avoid a
     *   redundant database query. When null, agents are fetched from
     *   [agentRepository].
     * @return List of diagnostic checks for the API keys category.
     */
    suspend fun runApiKeyChecks(
        preloadedAgents: List<Agent>? = null,
    ): List<DiagnosticCheck> {
        val checks = mutableListOf<DiagnosticCheck>()
        val agents = preloadedAgents ?: agentRepository.agents.first()
        val keys = apiKeyRepository.keys.first()
        val enabledAgents = agents.filter { it.isEnabled }

        for (agent in enabledAgents) {
            checks.add(checkAgentApiKey(agent, keys))
        }

        if (enabledAgents.isEmpty()) {
            checks.add(
                DiagnosticCheck(
                    id = "apikey-none",
                    category = DiagnosticCategory.API_KEYS,
                    title = "No enabled connections",
                    status = CheckStatus.WARN,
                    detail = "Enable at least one connection to validate API keys",
                    actionLabel = "Connections",
                    actionRoute = "agents",
                ),
            )
        }

        return checks
    }

    /**
     * Checks network connectivity.
     *
     * @return List of diagnostic checks for the connectivity category.
     */
    @Suppress("RedundantSuspendModifier")
    suspend fun runConnectivityChecks(): List<DiagnosticCheck> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = network?.let { cm.getNetworkCapabilities(it) }
        val hasInternet =
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val detail =
            when {
                !hasInternet -> "No internet connection available"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ->
                    "Connected via Wi-Fi"
                else -> "Connected via cellular"
            }

        return listOf(
            DiagnosticCheck(
                id = "connectivity-network",
                category = DiagnosticCategory.CONNECTIVITY,
                title = "Network connectivity",
                status = if (hasInternet) CheckStatus.PASS else CheckStatus.FAIL,
                detail = detail,
            ),
        )
    }

    /**
     * Parses [getStatus] JSON and checks component staleness.
     *
     * A successful `GET /api/status` response already proves the gateway is
     * reachable, so an absent `daemon_running` key means running — the
     * upstream status body (`crates/zeroclaw-gateway/src/api.rs`,
     * `handle_api_status`) never emits that key. Only an explicit
     * `daemon_running = false` reports the daemon down.
     *
     * @return List of diagnostic checks for the daemon health category.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun runDaemonHealthChecks(): List<DiagnosticCheck> =
        try {
            val json = withContext(ioDispatcher) { gateway.status().toString() }
            parseDaemonStatus(JSONObject(json))
        } catch (e: EngineException) {
            listOf(daemonErrorCheck("Failed to query status: ${e.message}"))
        } catch (e: Exception) {
            listOf(daemonErrorCheck("Unexpected error: ${e.message}"))
        }

    /**
     * Checks channel connectivity via `GET /api/channels`.
     *
     * The v0.8.5 gateway reports each channel as
     * `{name, type, alias, owning_agent, enabled, compiled, status, health,
     * readiness}` where `name` is the composite `<type>.<alias>` (e.g.
     * `discord.discord`), `status` is one of `active|inactive|unknown|error|
     * not_compiled`, and `health` is one of `healthy|degraded|down|unavailable`.
     *
     * Channel listener liveness is published separately in `/api/health` as a
     * `channel:<type>.<alias>` component. A running listener whose channel type
     * has no typed readiness probe (Discord, Slack, …) still reports
     * `status=unknown`/`health=degraded`, so that component health is what lets
     * the doctor call a working integration connected instead of offline.
     *
     * [expectedChannels] are the Room-enabled channel TOML keys (e.g.
     * `discord`). They are matched against the composite name, type, and alias
     * so a configured channel is not falsely flagged as "not live".
     *
     * @param configToml Retained for call-site compatibility; the engine owns
     *   its config and this check reads live gateway state instead.
     * @param dataDir Retained for call-site compatibility.
     * @param expectedChannels Room-enabled channel TOML keys expected to be live.
     * @return List of diagnostic checks for the channels category.
     */
    @Suppress("TooGenericExceptionCaught", "UnusedParameter")
    suspend fun runChannelChecks(
        configToml: String,
        dataDir: String,
        expectedChannels: List<String> = emptyList(),
    ): List<DiagnosticCheck> =
        try {
            val (json, componentHealth) =
                withContext(ioDispatcher) {
                    val root = gateway.channels()
                    val channels = (root.arr("channels") ?: JSONArray()).toString()
                    channels to fetchChannelComponentHealth()
                }
            parseChannelDiagnostics(json, expectedChannels, componentHealth)
        } catch (e: Exception) {
            listOf(
                DiagnosticCheck(
                    id = "channels-error",
                    category = DiagnosticCategory.CHANNELS,
                    title = "Channel diagnostics",
                    status = CheckStatus.FAIL,
                    detail = "Failed to run channel checks: ${e.message}",
                ),
            )
        }

    /**
     * Reads per-channel listener health from `/api/health`.
     *
     * @return Map of health component name (e.g. `channel:discord.discord`) to
     *   its raw status string. Empty when the snapshot is unavailable.
     */
    private suspend fun fetchChannelComponentHealth(): Map<String, String> =
        runCatching {
            val components = healthComponents(gateway.health()) ?: return emptyMap()
            components
                .keys()
                .asSequence()
                .mapNotNull { key ->
                    components.optJSONObject(key)?.string("status")?.let { key to it }
                }.toMap()
        }.getOrDefault(emptyMap())

    /**
     * Checks for recent error events in runtime traces.
     *
     * Queries the daemon's JSONL trace file for events matching "error"
     * and reports the count and latest message. Returns a passing check
     * if no error events are found or tracing is unavailable.
     *
     * @return Diagnostic checks for the runtime traces category.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun runTraceChecks(): List<DiagnosticCheck> =
        try {
            val json =
                withContext(ioDispatcher) {
                    val events = gateway.logs().listAt("events")
                    val errors =
                        events.filter { event ->
                            val severity = event.string("severity_text", "severity").orEmpty().lowercase()
                            val category =
                                event
                                    .optJSONObject("event")
                                    ?.string("category")
                                    .orEmpty()
                                    .lowercase()
                            severity == "error" || category == "error"
                        }
                    JSONArray(errors.map { it.toString() }).toString()
                }
            val array = JSONArray(json)
            if (array.length() == 0) {
                listOf(
                    DiagnosticCheck(
                        id = "traces-errors",
                        category = DiagnosticCategory.RUNTIME_TRACES,
                        title = "Recent errors",
                        status = CheckStatus.PASS,
                        detail = "No error events in runtime traces",
                    ),
                )
            } else {
                // `/api/logs` returns events newest-first; guard against a shape
                // change by also selecting the max timestamp.
                val latest =
                    (0 until array.length())
                        .map { array.getJSONObject(it) }
                        .maxByOrNull { rfc3339ToEpochMs(it.optString("timestamp")) ?: 0L }
                        ?: array.getJSONObject(0)
                val msg = latest.optString("message", "Unknown error")
                listOf(
                    DiagnosticCheck(
                        id = "traces-errors",
                        category = DiagnosticCategory.RUNTIME_TRACES,
                        title = "Recent errors",
                        status = CheckStatus.WARN,
                        detail = "${array.length()} error event(s). Latest: $msg",
                    ),
                )
            }
        } catch (e: Exception) {
            listOf(
                DiagnosticCheck(
                    id = "traces-errors",
                    category = DiagnosticCategory.RUNTIME_TRACES,
                    title = "Runtime traces",
                    status = CheckStatus.PASS,
                    detail = "Tracing not available: ${e.message}",
                ),
            )
        }

    /**
     * Checks system-level prerequisites: battery optimization, OEM
     * detection, storage space, and notification permission (Android 13+).
     *
     * @return List of diagnostic checks for the system category.
     */
    fun runSystemChecks(): List<DiagnosticCheck> {
        val checks = mutableListOf<DiagnosticCheck>()
        checks.add(checkBatteryExemption())
        checks.add(checkOemBattery())
        checks.add(checkStorage())
        checkNotificationPermission()?.let { checks.add(it) }
        return checks
    }

    internal fun parseChannelDiagnostics(
        json: String,
        expectedChannels: List<String> = emptyList(),
        componentHealth: Map<String, String> = emptyMap(),
    ): List<DiagnosticCheck> {
        val array = JSONArray(json)
        // The engine keys listener components on `Channel::name()`, which for
        // most types matches the config `type` but is not guaranteed (e.g.
        // ClawdTalk's is `"ClawdTalk"`). Index case-insensitively so a running
        // listener is always found.
        val healthByKey = componentHealth.entries.associate { (key, value) -> key.lowercase() to value }
        val liveKeys = mutableSetOf<String>()
        val checks = mutableListOf<DiagnosticCheck>()
        for (i in 0 until array.length()) {
            val (check, keys) = buildChannelCheck(array.getJSONObject(i), healthByKey)
            checks += check
            liveKeys += keys
        }
        for (expected in expectedChannels) {
            if (expected !in liveKeys) checks += missingChannelCheck(expected)
        }
        return checks
    }

    private fun checkNameUniqueness(agents: List<Agent>): DiagnosticCheck {
        val duplicates =
            agents
                .map { it.name }
                .groupBy { it }
                .filter { it.value.size > 1 }
                .keys
        return if (duplicates.isNotEmpty()) {
            DiagnosticCheck(
                id = "config-duplicate-names",
                category = DiagnosticCategory.CONFIG,
                title = "Connection nickname uniqueness",
                status = CheckStatus.FAIL,
                detail = "Duplicate connection nicknames: ${duplicates.joinToString()}",
                actionLabel = "Edit Connections",
                actionRoute = "agents",
            )
        } else {
            DiagnosticCheck(
                id = "config-duplicate-names",
                category = DiagnosticCategory.CONFIG,
                title = "Connection nickname uniqueness",
                status = CheckStatus.PASS,
                detail = "${agents.size} connections, all nicknames unique",
            )
        }
    }

    private suspend fun checkAgentConfig(agent: Agent): DiagnosticCheck {
        val toml = buildAgentToml(agent)
        // The engine validates its own config; keep the TOML build for display
        // context but do not block on a client-side parse.
        val result =
            if (toml.isBlank()) {
                "Configuration is empty"
            } else {
                ""
            }
        return if (result.isEmpty()) {
            DiagnosticCheck(
                id = "config-agent-${agent.id}",
                category = DiagnosticCategory.CONFIG,
                title = "Connection: ${agent.name}",
                status = CheckStatus.PASS,
                detail = "TOML config parses successfully",
            )
        } else {
            DiagnosticCheck(
                id = "config-agent-${agent.id}",
                category = DiagnosticCategory.CONFIG,
                title = "Connection: ${agent.name}",
                status = CheckStatus.FAIL,
                detail = result,
                actionLabel = "Edit Connection",
                actionRoute = "agent-detail/${agent.id}",
            )
        }
    }

    private fun checkAgentApiKey(
        agent: Agent,
        keys: List<ApiKey>,
    ): DiagnosticCheck {
        val providerInfo = ProviderRegistry.findById(agent.provider)
        val isSelfHosted =
            providerInfo?.authType == ProviderAuthType.URL_ONLY ||
                providerInfo?.authType == ProviderAuthType.NONE
        if (isSelfHosted) {
            return DiagnosticCheck(
                id = "apikey-${agent.id}",
                category = DiagnosticCategory.API_KEYS,
                title = "${agent.name} (${agent.provider})",
                status = CheckStatus.PASS,
                detail = "Self-hosted provider, no API key required",
            )
        }

        val matchingKey = keys.find { it.provider.equals(agent.provider, ignoreCase = true) }
        return classifyApiKeyStatus(agent, matchingKey)
    }

    private fun classifyApiKeyStatus(
        agent: Agent,
        key: ApiKey?,
    ): DiagnosticCheck {
        val title = "${agent.name} (${agent.provider})"
        return when {
            key == null ->
                DiagnosticCheck(
                    id = "apikey-${agent.id}",
                    category = DiagnosticCategory.API_KEYS,
                    title = title,
                    status = CheckStatus.FAIL,
                    detail = "No API key found for provider \"${agent.provider}\"",
                    actionLabel = "Add Key",
                    actionRoute = "api-keys",
                )
            key.status == KeyStatus.INVALID ->
                DiagnosticCheck(
                    id = "apikey-${agent.id}",
                    category = DiagnosticCategory.API_KEYS,
                    title = title,
                    status = CheckStatus.FAIL,
                    detail = "API key marked as invalid by provider",
                    actionLabel = "Edit Key",
                    actionRoute = "api-key-detail/${key.id}",
                )
            key.isOAuthToken && key.isExpired() ->
                DiagnosticCheck(
                    id = "apikey-${agent.id}",
                    category = DiagnosticCategory.API_KEYS,
                    title = title,
                    status = CheckStatus.WARN,
                    detail = "OAuth token expired or expiring soon",
                    actionLabel = "Refresh",
                    actionRoute = "api-key-detail/${key.id}",
                )
            else ->
                DiagnosticCheck(
                    id = "apikey-${agent.id}",
                    category = DiagnosticCategory.API_KEYS,
                    title = title,
                    status = CheckStatus.PASS,
                    detail = "Key present and active",
                )
        }
    }

    internal fun parseDaemonStatus(obj: JSONObject): List<DiagnosticCheck> {
        val checks = mutableListOf<DiagnosticCheck>()
        // `/api/status` nests the health snapshot under `health`
        // (`{"health": {"uptime_seconds": .., "components": {..}}}`); fall back
        // to the flat shape so older/simplified bodies still parse.
        val health = obj.obj("health") ?: obj
        // Upstream never sends `daemon_running`; default true because reaching
        // this parser means `GET /api/status` returned HTTP 200.
        val daemonRunning =
            when {
                obj.has("daemon_running") -> obj.optBoolean("daemon_running", false)
                health.has("uptime_seconds") || obj.has("health") -> true
                else -> true
            }

        checks.add(
            DiagnosticCheck(
                id = "daemon-running",
                category = DiagnosticCategory.DAEMON_HEALTH,
                title = "Daemon process",
                status = if (daemonRunning) CheckStatus.PASS else CheckStatus.WARN,
                detail = if (daemonRunning) "Daemon is running" else "Daemon is not running",
            ),
        )

        if (daemonRunning) {
            checks.add(
                DiagnosticCheck(
                    id = "daemon-uptime",
                    category = DiagnosticCategory.DAEMON_HEALTH,
                    title = "Daemon uptime",
                    status = CheckStatus.PASS,
                    detail = formatUptime(health.long("uptime_seconds", "uptimeSeconds") ?: 0L),
                ),
            )
            checks.addAll(parseComponentStatuses(obj))
        }

        return checks
    }

    private fun parseComponentStatuses(container: JSONObject): List<DiagnosticCheck> {
        val componentsObj = healthComponents(container) ?: return emptyList()
        return componentsObj
            .keys()
            .asSequence()
            .map { key ->
                val status = componentsObj.optJSONObject(key)?.optString("status", "unknown") ?: "unknown"
                val checkStatus =
                    when (status.lowercase()) {
                        "ok", "healthy", "active", "ready" -> CheckStatus.PASS
                        "error", "down", "unhealthy", "failed" -> CheckStatus.FAIL
                        // "starting"/"pending"/"unknown" are transient signals,
                        // not failures — do not paint the daemon red mid-boot.
                        else -> CheckStatus.WARN
                    }
                DiagnosticCheck(
                    id = "daemon-component-$key",
                    category = DiagnosticCategory.DAEMON_HEALTH,
                    title = "Component: $key",
                    status = checkStatus,
                    detail = "Status: $status",
                )
            }.toList()
    }

    private fun daemonErrorCheck(detail: String): DiagnosticCheck =
        DiagnosticCheck(
            id = "daemon-error",
            category = DiagnosticCategory.DAEMON_HEALTH,
            title = "Daemon health check",
            status = CheckStatus.FAIL,
            detail = detail,
        )

    private fun checkBatteryExemption(): DiagnosticCheck {
        val isExempt = BatteryOptimization.isExempt(context)
        return DiagnosticCheck(
            id = "system-battery-exempt",
            category = DiagnosticCategory.SYSTEM,
            title = "Battery optimization",
            status = if (isExempt) CheckStatus.PASS else CheckStatus.WARN,
            detail =
                if (isExempt) {
                    "App is exempt from battery optimization"
                } else {
                    "App is not exempt; service may be killed"
                },
            actionLabel = if (!isExempt) "Fix" else null,
            actionRoute = if (!isExempt) "battery-settings" else null,
        )
    }

    private fun checkOemBattery(): DiagnosticCheck {
        val aggressiveOem = BatteryOptimization.detectAggressiveOem()
        return if (aggressiveOem != null) {
            DiagnosticCheck(
                id = "system-oem",
                category = DiagnosticCategory.SYSTEM,
                title = "OEM battery management",
                status = CheckStatus.WARN,
                detail = "Device uses aggressive battery management (${aggressiveOem.name})",
                actionLabel = "Instructions",
                actionRoute = "battery-settings",
            )
        } else {
            DiagnosticCheck(
                id = "system-oem",
                category = DiagnosticCategory.SYSTEM,
                title = "OEM battery management",
                status = CheckStatus.PASS,
                detail = "No aggressive OEM battery management detected",
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun checkStorage(): DiagnosticCheck =
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val availableMb = stat.availableBytes / BYTES_PER_MB
            val storageStatus =
                when {
                    availableMb < LOW_STORAGE_THRESHOLD_MB -> CheckStatus.FAIL
                    availableMb < WARN_STORAGE_THRESHOLD_MB -> CheckStatus.WARN
                    else -> CheckStatus.PASS
                }
            DiagnosticCheck(
                id = "system-storage",
                category = DiagnosticCategory.SYSTEM,
                title = "Available storage",
                status = storageStatus,
                detail = "${availableMb}MB available",
            )
        } catch (_: Exception) {
            DiagnosticCheck(
                id = "system-storage",
                category = DiagnosticCategory.SYSTEM,
                title = "Available storage",
                status = CheckStatus.WARN,
                detail = "Could not determine available storage",
            )
        }

    @Suppress("TooGenericExceptionCaught")
    private fun checkNotificationPermission(): DiagnosticCheck? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted =
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                DiagnosticCheck(
                    id = "system-notification",
                    category = DiagnosticCategory.SYSTEM,
                    title = "Notification permission",
                    status = if (granted) CheckStatus.PASS else CheckStatus.WARN,
                    detail =
                        if (granted) {
                            "Notification permission granted"
                        } else {
                            "Notifications disabled; service status won't show"
                        },
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

    /**
     * Builds a minimal TOML config string for validating a single agent.
     *
     * @param agent The agent to build TOML for.
     * @return TOML string suitable for [validateConfig].
     */
    private fun buildAgentToml(agent: Agent): String {
        val entry =
            AgentTomlEntry(
                name = agent.name,
                provider = ConfigTomlBuilder.resolveProvider(agent.provider, ""),
                model = agent.modelName,
                systemPrompt = agent.systemPrompt,
                temperature = agent.temperature,
                maxDepth = agent.maxDepth,
            )
        return "default_temperature = 0.7\n" + ConfigTomlBuilder.buildAgentsToml(listOf(entry))
    }

    /** Constants for [DoctorValidator]. */
    companion object {
        private const val BYTES_PER_MB = 1_048_576L
        private const val LOW_STORAGE_THRESHOLD_MB = 50L
        private const val WARN_STORAGE_THRESHOLD_MB = 200L

        /**
         * Formats an uptime duration in seconds to a human-readable string.
         *
         * @param seconds Total uptime seconds.
         * @return Formatted string like "2h 15m 30s".
         */
        fun formatUptime(seconds: Long): String {
            val hours = seconds / SECONDS_PER_HOUR
            val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
            val secs = seconds % SECONDS_PER_MINUTE
            return when {
                hours > 0 -> "${hours}h ${minutes}m ${secs}s"
                minutes > 0 -> "${minutes}m ${secs}s"
                else -> "${secs}s"
            }
        }

        private const val SECONDS_PER_HOUR = 3600L
        private const val SECONDS_PER_MINUTE = 60L
    }
}

/**
 * Builds a channel diagnostic check plus the keys the channel is known by.
 *
 * @param obj One `/api/channels` entry.
 * @param healthByKey `/api/health` component statuses indexed by lowercased name.
 * @return The check and the composite/type/alias keys used for expected matching.
 */
private fun buildChannelCheck(
    obj: JSONObject,
    healthByKey: Map<String, String?>,
): Pair<DiagnosticCheck, Set<String>> {
    val type = obj.optString("type").ifBlank { obj.optString("name").substringBefore(".") }
    val alias = obj.optString("alias")
    val name =
        obj.optString("name").ifBlank {
            listOf(type, alias).filter { it.isNotBlank() }.joinToString(".").ifBlank { "unknown" }
        }
    val status = obj.optString("status")
    val health = obj.optString("health")
    val owningAgent =
        obj
            .optString("owning_agent")
            .takeIf { obj.has("owning_agent") && !obj.isNull("owning_agent") }
            .orEmpty()
    val listenerStatus =
        healthByKey["channel:$name".lowercase()]
            ?: healthByKey["channel:$type.$alias".lowercase()]
            ?: healthByKey["channel:$type".lowercase()]
    val down = isChannelDown(status, health, listenerStatus)
    val connected = !down && isChannelConnected(status, health, listenerStatus)
    val detail =
        when {
            down -> channelFailureDetail(obj, status, listenerStatus ?: health)
            connected -> "Connected"
            else -> channelHealthDetail(obj, status, health, owningAgent)
        }
    val check =
        DiagnosticCheck(
            id = "channel-$name",
            category = DiagnosticCategory.CHANNELS,
            title = "Channel: $name",
            status = classifyChannel(down, connected),
            detail = detail,
        )
    val keys =
        buildSet {
            add(name)
            if (type.isNotBlank()) add(type)
            if (alias.isNotBlank()) add(alias)
        }
    return check to keys
}

/**
 * Whether the engine explicitly reports the channel as down.
 *
 * The engine's failure signal is authoritative: a healthy listener component
 * does not make an unusable channel (for example a webhook awaiting pairing)
 * connected.
 */
private fun isChannelDown(
    status: String,
    health: String,
    listenerStatus: String?,
): Boolean =
    health.equals("down", ignoreCase = true) ||
        status in setOf("error", "not_compiled") ||
        listenerStatus.equals("error", ignoreCase = true) ||
        listenerStatus.equals("unhealthy", ignoreCase = true)

/** Whether the engine reports the channel (or its listener) as up. */
private fun isChannelConnected(
    status: String,
    health: String,
    listenerStatus: String?,
): Boolean =
    health.equals("healthy", ignoreCase = true) ||
        status in setOf("active", "healthy", "ok") ||
        listenerStatus.equals("ok", ignoreCase = true) ||
        listenerStatus.equals("healthy", ignoreCase = true)

/**
 * Classifies a channel.
 *
 * `degraded`/`unknown`/`inactive` mean the engine cannot prove the channel is
 * up (often because it has no typed readiness probe), not that it is offline,
 * so they are warnings rather than failures.
 */
private fun classifyChannel(
    down: Boolean,
    connected: Boolean,
): CheckStatus =
    when {
        down -> CheckStatus.FAIL
        connected -> CheckStatus.PASS
        else -> CheckStatus.WARN
    }

/** Human-readable reason a channel is reported down. */
private fun channelFailureDetail(
    obj: JSONObject,
    status: String,
    fallback: String,
): String =
    readinessDetail(obj)
        ?: when {
            status == "not_compiled" ->
                "This channel type is not compiled into the bundled engine."
            else -> "Health: ${fallback.ifBlank { status.ifBlank { "down" } }}"
        }

/** Human-readable summary for a channel the engine cannot confirm as up. */
private fun channelHealthDetail(
    obj: JSONObject,
    status: String,
    health: String,
    owningAgent: String,
): String =
    readinessDetail(obj)
        ?: when {
            owningAgent.isBlank() -> "Not bound to an enabled agent"
            else -> "Health: ${health.ifBlank { status.ifBlank { "unknown" } }}"
        }

/** Joins readiness requirements/notes, or null when there is nothing to show. */
private fun readinessDetail(obj: JSONObject): String? {
    val readiness = obj.optJSONObject("readiness") ?: return null
    val parts =
        listOf("requirements", "notes").flatMap { key -> jsonStrings(readiness, key) }
    return parts.joinToString("; ").ifBlank { null }
}

/** Reads the non-blank string elements of a JSON array field. */
private fun jsonStrings(
    obj: JSONObject,
    key: String,
): List<String> {
    val array = obj.optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optString(index).takeIf { it.isNotBlank() }
    }
}

/** Missing-channel warning for a Room-enabled channel the engine doesn't report. */
private fun missingChannelCheck(expected: String): DiagnosticCheck =
    DiagnosticCheck(
        id = "channel-missing-$expected",
        category = DiagnosticCategory.CHANNELS,
        title = "Channel: $expected (configured but not live)",
        status = CheckStatus.WARN,
        detail =
            "Room has this channel enabled but the engine does not " +
                "report it. Check channels.<type>.<alias> enabled=true, " +
                "the bot token, and that an enabled agent binds " +
                "agents.<agent>.channels to it — unbound aliases are " +
                "silently skipped and the bot stays offline.",
    )

/**
 * Extracts the health `components` object from a gateway response body.
 *
 * Accepts either a response with the snapshot nested under `health`
 * (`/api/status`, `/api/health`) or one that is already the snapshot.
 */
private fun healthComponents(container: JSONObject): JSONObject? {
    val health = container.obj("health") ?: container
    return health.obj("components")
}
