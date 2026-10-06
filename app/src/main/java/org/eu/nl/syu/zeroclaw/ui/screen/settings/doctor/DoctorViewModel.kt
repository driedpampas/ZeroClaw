/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.ui.screen.settings.doctor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.eu.nl.syu.zeroclaw.ZeroClawApplication
import org.eu.nl.syu.zeroclaw.model.ApiKey
import org.eu.nl.syu.zeroclaw.model.AppSettings
import org.eu.nl.syu.zeroclaw.model.CheckStatus
import org.eu.nl.syu.zeroclaw.model.DiagnosticCheck
import org.eu.nl.syu.zeroclaw.model.DoctorSummary
import org.eu.nl.syu.zeroclaw.model.LogSeverity
import org.eu.nl.syu.zeroclaw.service.AgentTomlEntry
import org.eu.nl.syu.zeroclaw.service.ConfigTomlBuilder
import org.eu.nl.syu.zeroclaw.service.DoctorValidator
import org.eu.nl.syu.zeroclaw.service.GlobalTomlConfig
import org.eu.nl.syu.zeroclaw.util.LogSanitizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ViewModel for the ZeroClaw Doctor diagnostics screen.
 *
 * Orchestrates sequential execution of diagnostic check categories
 * and provides incremental UI updates as each category completes.
 *
 * @param application Application context for accessing repositories.
 */
class DoctorViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroClawApplication

    private val validator =
        DoctorValidator(
            context = application,
            agentRepository = app.agentRepository,
            apiKeyRepository = app.apiKeyRepository,
        )

    private val _checks = MutableStateFlow<List<DiagnosticCheck>>(emptyList())

    /** All diagnostic check results, incrementally populated as categories complete. */
    val checks: StateFlow<List<DiagnosticCheck>> = _checks.asStateFlow()

    private val _isRunning = MutableStateFlow(false)

    /** Whether diagnostic checks are currently executing. */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _summary = MutableStateFlow<DoctorSummary?>(null)

    /** Aggregated check summary, available after all checks complete. */
    val summary: StateFlow<DoctorSummary?> = _summary.asStateFlow()

    /**
     * Runs all diagnostic check categories sequentially.
     *
     * Each category's results are appended to [checks] as they complete,
     * providing incremental UI updates. The [summary] is computed after
     * all categories finish.
     *
     * Safe to call multiple times; resets state on each invocation.
     */
    fun runAllChecks() {
        if (_isRunning.value) return
        viewModelScope.launch {
            _isRunning.value = true
            _checks.value = emptyList()
            _summary.value = null

            val accumulated = mutableListOf<DiagnosticCheck>()
            val agents = app.agentRepository.agents.first()

            val configChecks = validator.runConfigChecks(preloadedAgents = agents)
            accumulated.addAll(configChecks)
            _checks.value = accumulated.toList()

            val apiKeyChecks = validator.runApiKeyChecks(preloadedAgents = agents)
            accumulated.addAll(apiKeyChecks)
            _checks.value = accumulated.toList()

            val connectivityChecks = validator.runConnectivityChecks()
            accumulated.addAll(connectivityChecks)
            _checks.value = accumulated.toList()

            val daemonChecks = validator.runDaemonHealthChecks()
            accumulated.addAll(daemonChecks)
            _checks.value = accumulated.toList()

            val channelChecks =
                validator.runChannelChecks(
                    configToml = buildCurrentToml(),
                    dataDir = app.filesDir.absolutePath,
                    expectedChannels =
                        app.channelConfigRepository.channels.first()
                            .filter { it.isEnabled }
                            .map { it.type.tomlKey },
                )
            accumulated.addAll(channelChecks)
            _checks.value = accumulated.toList()
            logDiagnosticProblems(channelChecks, CHANNEL_LOG_TAG)

            val traceChecks = validator.runTraceChecks()
            accumulated.addAll(traceChecks)
            _checks.value = accumulated.toList()
            logDiagnosticProblems(traceChecks, TRACE_LOG_TAG)

            val systemChecks = validator.runSystemChecks()
            accumulated.addAll(systemChecks)
            _checks.value = accumulated.toList()

            _summary.value = DoctorSummary.from(accumulated)
            _isRunning.value = false
        }
    }

    /**
     * Mirrors non-passing diagnostics into the app log stream.
     *
     * The engine reports channel problems through `/api/channels` and
     * `/api/health`, and runtime errors through `/api/logs`; neither is part of
     * the app's own log output. Without this, a channel that is offline or an
     * engine-side failure leaves no trace in the Logs screen. Failures are
     * logged as errors, warnings as warnings.
     *
     * @param checks Diagnostic results to mirror.
     * @param tag Log tag identifying the diagnostic source.
     */
    private fun logDiagnosticProblems(
        checks: List<DiagnosticCheck>,
        tag: String,
    ) {
        checks.forEach { check ->
            val message = LogSanitizer.sanitizeLogMessage("${check.title}: ${check.detail}")
            when (check.status) {
                CheckStatus.FAIL ->
                    app.logRepository.append(LogSeverity.ERROR, tag, message)
                CheckStatus.WARN ->
                    app.logRepository.append(LogSeverity.WARN, tag, message)
                else -> Unit
            }
        }
    }

    /**
     * Builds the full TOML config string from current settings, agents,
     * and channel configurations.
     *
     * Mirrors the TOML assembly in
     * [ZeroClawDaemonService][org.eu.nl.syu.zeroclaw.service.ZeroClawDaemonService]
     * so the FFI doctor check sees the same config the daemon would use.
     *
     * @return A valid TOML configuration string.
     */
    private suspend fun buildCurrentToml(): String {
        val settings = app.settingsRepository.settings.first()
        val effectiveSettings = resolveEffectiveDefaults(settings)
        val apiKey =
            app.apiKeyRepository.getByProviderFresh(
                effectiveSettings.defaultProvider,
            )
        val globalConfig = buildGlobalTomlConfig(effectiveSettings, apiKey)
        val baseToml = ConfigTomlBuilder.build(globalConfig)
        val channelsToml =
            ConfigTomlBuilder.buildChannelsToml(
                app.channelConfigRepository.getEnabledWithSecrets(),
            )
        val agentsToml = buildAgentsToml()
        return baseToml + channelsToml + agentsToml
    }

    /**
     * Derives effective default provider and model from the agent list.
     *
     * The first enabled agent with a non-blank provider and model name
     * overrides the DataStore values in [settings]. Mirrors the logic in
     * [ZeroClawDaemonService][org.eu.nl.syu.zeroclaw.service.ZeroClawDaemonService].
     *
     * @param settings Current application settings (may have stale defaults).
     * @return A copy of [settings] with provider and model overridden by the
     *   primary agent, or unchanged if no qualifying agent exists.
     */
    private suspend fun resolveEffectiveDefaults(
        settings: AppSettings,
    ): AppSettings {
        val agents = app.agentRepository.agents.first()
        val primary =
            agents.firstOrNull {
                it.isEnabled && it.provider.isNotBlank() && it.modelName.isNotBlank()
            } ?: return settings
        return settings.copy(
            defaultProvider = primary.provider,
            defaultModel = primary.modelName,
        )
    }

    /**
     * Converts [AppSettings] and resolved API key into a [GlobalTomlConfig].
     *
     * Comma-separated string fields in [AppSettings] are split into lists
     * for [GlobalTomlConfig] properties that expect `List<String>`. Mirrors
     * the logic in
     * [ZeroClawDaemonService][org.eu.nl.syu.zeroclaw.service.ZeroClawDaemonService].
     *
     * @param settings Current application settings.
     * @param apiKey Resolved API key for the default provider, or null.
     * @return A fully populated [GlobalTomlConfig].
     */
    @Suppress("LongMethod")
    private fun buildGlobalTomlConfig(
        settings: AppSettings,
        apiKey: ApiKey?,
    ): GlobalTomlConfig =
        GlobalTomlConfig(
            provider = settings.defaultProvider,
            model = settings.defaultModel,
            apiKey = apiKey?.key.orEmpty(),
            baseUrl = apiKey?.baseUrl.orEmpty(),
            temperature = settings.defaultTemperature,
            compactContext = settings.compactContext,
            costEnabled = settings.costEnabled,
            dailyLimitUsd = settings.dailyLimitUsd,
            monthlyLimitUsd = settings.monthlyLimitUsd,
            costWarnAtPercent = settings.costWarnAtPercent,
            providerRetries = settings.providerRetries,
            fallbackProviders = splitCsv(settings.fallbackProviders),
            memoryBackend = settings.memoryBackend,
            memoryAutoSave = settings.memoryAutoSave,
            identityJson = settings.identityJson,
            autonomyLevel = settings.autonomyLevel,
            workspaceOnly = settings.workspaceOnly,
            allowedCommands = splitCsv(settings.allowedCommands),
            forbiddenPaths = splitCsv(settings.forbiddenPaths),
            maxActionsPerHour = settings.maxActionsPerHour,
            maxCostPerDayCents = settings.maxCostPerDayCents,
            requireApprovalMediumRisk = settings.requireApprovalMediumRisk,
            blockHighRiskCommands = settings.blockHighRiskCommands,
            tunnelProvider = settings.tunnelProvider,
            tunnelCloudflareToken = settings.tunnelCloudflareToken,
            tunnelTailscaleFunnel = settings.tunnelTailscaleFunnel,
            tunnelTailscaleHostname = settings.tunnelTailscaleHostname,
            tunnelNgrokAuthToken = settings.tunnelNgrokAuthToken,
            tunnelNgrokDomain = settings.tunnelNgrokDomain,
            tunnelCustomCommand = settings.tunnelCustomCommand,
            tunnelCustomHealthUrl = settings.tunnelCustomHealthUrl,
            tunnelCustomUrlPattern = settings.tunnelCustomUrlPattern,
            gatewayHost = settings.host,
            gatewayPort = settings.port,
            gatewayRequirePairing = settings.gatewayRequirePairing,
            gatewayAllowPublicBind = settings.gatewayAllowPublicBind,
            gatewayPairedTokens = splitCsv(settings.gatewayPairedTokens),
            gatewayPairRateLimit = settings.gatewayPairRateLimit,
            gatewayWebhookRateLimit = settings.gatewayWebhookRateLimit,
            gatewayIdempotencyTtl = settings.gatewayIdempotencyTtl,
            schedulerEnabled = settings.schedulerEnabled,
            schedulerMaxTasks = settings.schedulerMaxTasks,
            schedulerMaxConcurrent = settings.schedulerMaxConcurrent,
            heartbeatEnabled = settings.heartbeatEnabled,
            heartbeatIntervalMinutes = settings.heartbeatIntervalMinutes,
            observabilityBackend = settings.observabilityBackend,
            observabilityOtelEndpoint = settings.observabilityOtelEndpoint,
            observabilityOtelServiceName = settings.observabilityOtelServiceName,
            modelRoutesJson = settings.modelRoutesJson,
            memoryHygieneEnabled = settings.memoryHygieneEnabled,
            memoryArchiveAfterDays = settings.memoryArchiveAfterDays,
            memoryPurgeAfterDays = settings.memoryPurgeAfterDays,
            memoryEmbeddingProvider = settings.memoryEmbeddingProvider,
            memoryEmbeddingModel = settings.memoryEmbeddingModel,
            memoryVectorWeight = settings.memoryVectorWeight,
            memoryKeywordWeight = settings.memoryKeywordWeight,
            composioEnabled = settings.composioEnabled,
            composioApiKey = settings.composioApiKey,
            composioEntityId = settings.composioEntityId,
            browserEnabled = settings.browserEnabled,
            browserAllowedDomains = splitCsv(settings.browserAllowedDomains),
            httpRequestEnabled = settings.httpRequestEnabled,
            httpRequestAllowedDomains =
                splitCsv(
                    settings.httpRequestAllowedDomains,
                ),
            webFetchEnabled = settings.webFetchEnabled,
            webFetchAllowedDomains = splitCsv(settings.webFetchAllowedDomains),
            webFetchBlockedDomains = splitCsv(settings.webFetchBlockedDomains),
            webFetchMaxResponseSize = settings.webFetchMaxResponseSize,
            webFetchTimeoutSecs = settings.webFetchTimeoutSecs,
            webSearchEnabled = settings.webSearchEnabled,
            webSearchProvider = settings.webSearchProvider,
            webSearchBraveApiKey = settings.webSearchBraveApiKey,
            webSearchMaxResults = settings.webSearchMaxResults,
            webSearchTimeoutSecs = settings.webSearchTimeoutSecs,
            securitySandboxEnabled = settings.securitySandboxEnabled,
            securitySandboxBackend = settings.securitySandboxBackend,
            securitySandboxFirejailArgs =
                splitCsv(
                    settings.securitySandboxFirejailArgs,
                ),
            securityResourcesMaxMemoryMb = settings.securityResourcesMaxMemoryMb,
            securityResourcesMaxCpuTimeSecs =
                settings.securityResourcesMaxCpuTimeSecs,
            securityResourcesMaxSubprocesses =
                settings.securityResourcesMaxSubprocesses,
            securityResourcesMemoryMonitoring =
                settings.securityResourcesMemoryMonitoring,
            securityAuditEnabled = settings.securityAuditEnabled,
            securityOtpEnabled = settings.securityOtpEnabled,
            securityOtpMethod = settings.securityOtpMethod,
            securityOtpTokenTtlSecs = settings.securityOtpTokenTtlSecs,
            securityOtpCacheValidSecs = settings.securityOtpCacheValidSecs,
            securityOtpGatedActions =
                splitCsv(
                    settings.securityOtpGatedActions,
                ),
            securityOtpGatedDomains =
                splitCsv(
                    settings.securityOtpGatedDomains,
                ),
            securityOtpGatedDomainCategories =
                splitCsv(
                    settings.securityOtpGatedDomainCategories,
                ),
            securityEstopEnabled = settings.securityEstopEnabled,
            securityEstopRequireOtpToResume =
                settings.securityEstopRequireOtpToResume,
            memoryQdrantUrl = settings.memoryQdrantUrl,
            memoryQdrantCollection = settings.memoryQdrantCollection,
            memoryQdrantApiKey = settings.memoryQdrantApiKey,
            embeddingRoutesJson = settings.embeddingRoutesJson,
            queryClassificationEnabled = settings.queryClassificationEnabled,
            proxyEnabled = settings.proxyEnabled,
            proxyHttpProxy = settings.proxyHttpProxy,
            proxyHttpsProxy = settings.proxyHttpsProxy,
            proxyAllProxy = settings.proxyAllProxy,
            proxyNoProxy = splitCsv(settings.proxyNoProxy),
            proxyScope = settings.proxyScope,
            proxyServiceSelectors = splitCsv(settings.proxyServiceSelectors),
            reliabilityBackoffMs = settings.reliabilityBackoffMs,
            reliabilityApiKeysJson = settings.reliabilityApiKeysJson,
        )

    /**
     * Resolves all enabled agents into TOML agent sections.
     *
     * @return TOML string with per-agent sections, or empty if no agents qualify.
     */
    private suspend fun buildAgentsToml(): String {
        val allAgents = app.agentRepository.agents.first()
        val entries =
            allAgents
                .filter {
                    it.isEnabled && it.provider.isNotBlank() && it.modelName.isNotBlank()
                }.map { agent ->
                    val agentKey =
                        app.apiKeyRepository.getByProviderFresh(
                            agent.provider,
                        )
                    AgentTomlEntry(
                        name = agent.name,
                        provider =
                            ConfigTomlBuilder.resolveProvider(
                                agent.provider,
                                agentKey?.baseUrl.orEmpty(),
                            ),
                        model = agent.modelName,
                        apiKey = agentKey?.key.orEmpty(),
                        systemPrompt = agent.systemPrompt,
                        temperature = agent.temperature,
                        maxDepth = agent.maxDepth,
                    )
                }
        return ConfigTomlBuilder.buildAgentsToml(entries)
    }

    /** Constants for [DoctorViewModel]. */
    private companion object {
        /** Log tag for channel diagnostics mirrored into the log stream. */
        private const val CHANNEL_LOG_TAG = "Doctor"

        /** Log tag for engine runtime-trace errors mirrored into the log stream. */
        private const val TRACE_LOG_TAG = "Engine"
    }
}

/**
 * Splits a comma-separated string into a trimmed, non-empty list of values.
 *
 * @param csv Comma-separated input string.
 * @return List of trimmed non-blank values.
 */
private fun splitCsv(csv: String): List<String> = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
