/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.eu.nl.syu.zeroclaw.ZeroClawApplication
import org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository
import org.eu.nl.syu.zeroclaw.model.AppSettings
import org.eu.nl.syu.zeroclaw.model.OfficialPlugins
import org.eu.nl.syu.zeroclaw.model.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the settings screen hierarchy.
 *
 * Exposes the current [AppSettings] as a [StateFlow] and provides
 * methods for updating individual settings via the repository.
 *
 * @param application Application context for accessing the settings repository.
 */
@Suppress("TooManyFunctions")
class SettingsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = (application as ZeroClawApplication).settingsRepository
    private val onboardingRepository = (application as ZeroClawApplication).onboardingRepository
    private val daemonBridge = (application as ZeroClawApplication).daemonBridge
    private val agentRepository = (application as ZeroClawApplication).agentRepository

    /** Current application settings, collected as state. */
    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = AppSettings(),
        )

    /** Whether a daemon restart is required to apply settings changes. */
    val restartRequired: StateFlow<Boolean> = daemonBridge.restartRequired

    /**
     * Updates a daemon-affecting setting and marks a restart as required
     * if the daemon is currently running.
     */
    private fun updateDaemonSetting(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch {
            repository.block()
            daemonBridge.markRestartRequired()
        }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setHost */
    fun updateHost(host: String) {
        updateDaemonSetting { setHost(host) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setPort */
    fun updatePort(port: Int) {
        updateDaemonSetting { setPort(port) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setAutoStartOnBoot */
    fun updateAutoStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoStartOnBoot(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setDefaultProvider */
    fun updateDefaultProvider(provider: String) {
        updateDaemonSetting { setDefaultProvider(provider) }
        viewModelScope.launch { agentRepository.updatePrimaryAgentProvider(provider) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setDefaultModel */
    fun updateDefaultModel(model: String) {
        updateDaemonSetting { setDefaultModel(model) }
        viewModelScope.launch { agentRepository.updatePrimaryAgentModel(model) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setDefaultTemperature */
    fun updateDefaultTemperature(temperature: Float) {
        updateDaemonSetting { setDefaultTemperature(temperature) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setCompactContext */
    fun updateCompactContext(enabled: Boolean) {
        updateDaemonSetting { setCompactContext(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setCostEnabled */
    fun updateCostEnabled(enabled: Boolean) {
        updateDaemonSetting { setCostEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setDailyLimitUsd */
    fun updateDailyLimitUsd(limit: Float) {
        updateDaemonSetting { setDailyLimitUsd(limit) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMonthlyLimitUsd */
    fun updateMonthlyLimitUsd(limit: Float) {
        updateDaemonSetting { setMonthlyLimitUsd(limit) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setCostWarnAtPercent */
    fun updateCostWarnAtPercent(percent: Int) {
        updateDaemonSetting { setCostWarnAtPercent(percent) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setProviderRetries */
    fun updateProviderRetries(retries: Int) {
        updateDaemonSetting { setProviderRetries(retries) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setFallbackProviders */
    fun updateFallbackProviders(providers: String) {
        updateDaemonSetting { setFallbackProviders(providers) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMemoryBackend */
    fun updateMemoryBackend(backend: String) {
        updateDaemonSetting { setMemoryBackend(backend) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMemoryAutoSave */
    fun updateMemoryAutoSave(enabled: Boolean) {
        updateDaemonSetting { setMemoryAutoSave(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setIdentityJson */
    fun updateIdentityJson(json: String) {
        updateDaemonSetting { setIdentityJson(json) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setAutonomyLevel */
    fun updateAutonomyLevel(level: String) {
        updateDaemonSetting { setAutonomyLevel(level) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setWorkspaceOnly */
    fun updateWorkspaceOnly(enabled: Boolean) {
        updateDaemonSetting { setWorkspaceOnly(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setAllowedCommands */
    fun updateAllowedCommands(commands: String) {
        updateDaemonSetting { setAllowedCommands(commands) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setForbiddenPaths */
    fun updateForbiddenPaths(paths: String) {
        updateDaemonSetting { setForbiddenPaths(paths) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMaxActionsPerHour */
    fun updateMaxActionsPerHour(max: Int) {
        updateDaemonSetting { setMaxActionsPerHour(max) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMaxCostPerDayCents */
    fun updateMaxCostPerDayCents(cents: Int) {
        updateDaemonSetting { setMaxCostPerDayCents(cents) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setRequireApprovalMediumRisk */
    fun updateRequireApprovalMediumRisk(required: Boolean) {
        updateDaemonSetting { setRequireApprovalMediumRisk(required) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setBlockHighRiskCommands */
    fun updateBlockHighRiskCommands(blocked: Boolean) {
        updateDaemonSetting { setBlockHighRiskCommands(blocked) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setTunnelProvider */
    fun updateTunnelProvider(provider: String) {
        updateDaemonSetting { setTunnelProvider(provider) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setTunnelCloudflareToken */
    fun updateTunnelCloudflareToken(token: String) {
        updateDaemonSetting { setTunnelCloudflareToken(token) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setTunnelTailscaleFunnel */
    fun updateTunnelTailscaleFunnel(enabled: Boolean) {
        updateDaemonSetting { setTunnelTailscaleFunnel(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setTunnelTailscaleHostname */
    fun updateTunnelTailscaleHostname(hostname: String) {
        updateDaemonSetting { setTunnelTailscaleHostname(hostname) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setTunnelNgrokAuthToken */
    fun updateTunnelNgrokAuthToken(token: String) {
        updateDaemonSetting { setTunnelNgrokAuthToken(token) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setTunnelNgrokDomain */
    fun updateTunnelNgrokDomain(domain: String) {
        updateDaemonSetting { setTunnelNgrokDomain(domain) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setTunnelCustomCommand */
    fun updateTunnelCustomCommand(command: String) {
        updateDaemonSetting { setTunnelCustomCommand(command) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setTunnelCustomHealthUrl */
    fun updateTunnelCustomHealthUrl(url: String) {
        updateDaemonSetting { setTunnelCustomHealthUrl(url) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setTunnelCustomUrlPattern */
    fun updateTunnelCustomUrlPattern(pattern: String) {
        updateDaemonSetting { setTunnelCustomUrlPattern(pattern) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setGatewayRequirePairing */
    fun updateGatewayRequirePairing(required: Boolean) {
        updateDaemonSetting { setGatewayRequirePairing(required) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setGatewayAllowPublicBind */
    fun updateGatewayAllowPublicBind(allowed: Boolean) {
        updateDaemonSetting { setGatewayAllowPublicBind(allowed) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setGatewayPairedTokens */
    fun updateGatewayPairedTokens(tokens: String) {
        updateDaemonSetting { setGatewayPairedTokens(tokens) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setGatewayPairRateLimit */
    fun updateGatewayPairRateLimit(limit: Int) {
        updateDaemonSetting { setGatewayPairRateLimit(limit) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setGatewayWebhookRateLimit */
    fun updateGatewayWebhookRateLimit(limit: Int) {
        updateDaemonSetting { setGatewayWebhookRateLimit(limit) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setGatewayIdempotencyTtl */
    fun updateGatewayIdempotencyTtl(seconds: Int) {
        updateDaemonSetting { setGatewayIdempotencyTtl(seconds) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSchedulerEnabled */
    fun updateSchedulerEnabled(enabled: Boolean) {
        updateDaemonSetting { setSchedulerEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSchedulerMaxTasks */
    fun updateSchedulerMaxTasks(max: Int) {
        updateDaemonSetting { setSchedulerMaxTasks(max) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSchedulerMaxConcurrent */
    fun updateSchedulerMaxConcurrent(max: Int) {
        updateDaemonSetting { setSchedulerMaxConcurrent(max) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setHeartbeatEnabled */
    fun updateHeartbeatEnabled(enabled: Boolean) {
        updateDaemonSetting { setHeartbeatEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setHeartbeatIntervalMinutes */
    fun updateHeartbeatIntervalMinutes(minutes: Int) {
        updateDaemonSetting { setHeartbeatIntervalMinutes(minutes) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setObservabilityBackend */
    fun updateObservabilityBackend(backend: String) {
        updateDaemonSetting { setObservabilityBackend(backend) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setObservabilityOtelEndpoint */
    fun updateObservabilityOtelEndpoint(endpoint: String) {
        updateDaemonSetting { setObservabilityOtelEndpoint(endpoint) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setObservabilityOtelServiceName */
    fun updateObservabilityOtelServiceName(name: String) {
        updateDaemonSetting { setObservabilityOtelServiceName(name) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setModelRoutesJson */
    fun updateModelRoutesJson(json: String) {
        updateDaemonSetting { setModelRoutesJson(json) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMemoryHygieneEnabled */
    fun updateMemoryHygieneEnabled(enabled: Boolean) {
        updateDaemonSetting { setMemoryHygieneEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMemoryArchiveAfterDays */
    fun updateMemoryArchiveAfterDays(days: Int) {
        updateDaemonSetting { setMemoryArchiveAfterDays(days) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMemoryPurgeAfterDays */
    fun updateMemoryPurgeAfterDays(days: Int) {
        updateDaemonSetting { setMemoryPurgeAfterDays(days) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMemoryEmbeddingProvider */
    fun updateMemoryEmbeddingProvider(provider: String) {
        updateDaemonSetting { setMemoryEmbeddingProvider(provider) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMemoryEmbeddingModel */
    fun updateMemoryEmbeddingModel(model: String) {
        updateDaemonSetting { setMemoryEmbeddingModel(model) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMemoryVectorWeight */
    fun updateMemoryVectorWeight(weight: Float) {
        updateDaemonSetting { setMemoryVectorWeight(weight) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMemoryKeywordWeight */
    fun updateMemoryKeywordWeight(weight: Float) {
        updateDaemonSetting { setMemoryKeywordWeight(weight) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setComposioEnabled */
    fun updateComposioEnabled(enabled: Boolean) {
        updateDaemonSetting { setComposioEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setComposioApiKey */
    fun updateComposioApiKey(key: String) {
        updateDaemonSetting { setComposioApiKey(key) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setComposioEntityId */
    fun updateComposioEntityId(entityId: String) {
        updateDaemonSetting { setComposioEntityId(entityId) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setBrowserEnabled */
    fun updateBrowserEnabled(enabled: Boolean) {
        updateDaemonSetting { setBrowserEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setBrowserAllowedDomains */
    fun updateBrowserAllowedDomains(domains: String) {
        updateDaemonSetting { setBrowserAllowedDomains(domains) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setHttpRequestEnabled */
    fun updateHttpRequestEnabled(enabled: Boolean) {
        updateDaemonSetting { setHttpRequestEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setHttpRequestAllowedDomains */
    fun updateHttpRequestAllowedDomains(domains: String) {
        updateDaemonSetting { setHttpRequestAllowedDomains(domains) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setHttpRequestMaxResponseSize */
    fun updateHttpRequestMaxResponseSize(size: Int) {
        updateDaemonSetting { setHttpRequestMaxResponseSize(size) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setHttpRequestTimeoutSecs */
    fun updateHttpRequestTimeoutSecs(secs: Int) {
        updateDaemonSetting { setHttpRequestTimeoutSecs(secs) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setWebFetchEnabled */
    fun updateWebFetchEnabled(enabled: Boolean) {
        updateDaemonSetting { setWebFetchEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setWebFetchAllowedDomains */
    fun updateWebFetchAllowedDomains(domains: String) {
        updateDaemonSetting { setWebFetchAllowedDomains(domains) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setWebFetchBlockedDomains */
    fun updateWebFetchBlockedDomains(domains: String) {
        updateDaemonSetting { setWebFetchBlockedDomains(domains) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setWebFetchMaxResponseSize */
    fun updateWebFetchMaxResponseSize(size: Int) {
        updateDaemonSetting { setWebFetchMaxResponseSize(size) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setWebFetchTimeoutSecs */
    fun updateWebFetchTimeoutSecs(secs: Int) {
        updateDaemonSetting { setWebFetchTimeoutSecs(secs) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setWebSearchEnabled */
    fun updateWebSearchEnabled(enabled: Boolean) {
        updateDaemonSetting { setWebSearchEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setWebSearchProvider */
    fun updateWebSearchProvider(provider: String) {
        updateDaemonSetting { setWebSearchProvider(provider) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setWebSearchBraveApiKey */
    fun updateWebSearchBraveApiKey(key: String) {
        updateDaemonSetting { setWebSearchBraveApiKey(key) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setWebSearchMaxResults */
    fun updateWebSearchMaxResults(max: Int) {
        updateDaemonSetting { setWebSearchMaxResults(max) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setWebSearchTimeoutSecs */
    fun updateWebSearchTimeoutSecs(secs: Int) {
        updateDaemonSetting { setWebSearchTimeoutSecs(secs) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setTranscriptionEnabled */
    fun updateTranscriptionEnabled(enabled: Boolean) {
        updateDaemonSetting { setTranscriptionEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setTranscriptionApiUrl */
    fun updateTranscriptionApiUrl(url: String) {
        updateDaemonSetting { setTranscriptionApiUrl(url) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setTranscriptionModel */
    fun updateTranscriptionModel(model: String) {
        updateDaemonSetting { setTranscriptionModel(model) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setTranscriptionLanguage */
    fun updateTranscriptionLanguage(language: String) {
        updateDaemonSetting { setTranscriptionLanguage(language) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setTranscriptionMaxDurationSecs */
    fun updateTranscriptionMaxDurationSecs(secs: Int) {
        updateDaemonSetting { setTranscriptionMaxDurationSecs(secs) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMultimodalMaxImages */
    fun updateMultimodalMaxImages(max: Int) {
        updateDaemonSetting { setMultimodalMaxImages(max) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMultimodalMaxImageSizeMb */
    fun updateMultimodalMaxImageSizeMb(mb: Int) {
        updateDaemonSetting { setMultimodalMaxImageSizeMb(mb) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMultimodalAllowRemoteFetch */
    fun updateMultimodalAllowRemoteFetch(enabled: Boolean) {
        updateDaemonSetting { setMultimodalAllowRemoteFetch(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSecuritySandboxEnabled */
    fun updateSecuritySandboxEnabled(enabled: Boolean?) {
        updateDaemonSetting { setSecuritySandboxEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSecuritySandboxBackend */
    fun updateSecuritySandboxBackend(backend: String) {
        updateDaemonSetting { setSecuritySandboxBackend(backend) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSecuritySandboxFirejailArgs */
    fun updateSecuritySandboxFirejailArgs(args: String) {
        updateDaemonSetting { setSecuritySandboxFirejailArgs(args) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSecurityResourcesMaxMemoryMb */
    fun updateSecurityResourcesMaxMemoryMb(mb: Int) {
        updateDaemonSetting { setSecurityResourcesMaxMemoryMb(mb) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSecurityResourcesMaxCpuTimeSecs */
    fun updateSecurityResourcesMaxCpuTimeSecs(secs: Int) {
        updateDaemonSetting { setSecurityResourcesMaxCpuTimeSecs(secs) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSecurityResourcesMaxSubprocesses */
    fun updateSecurityResourcesMaxSubprocesses(max: Int) {
        updateDaemonSetting { setSecurityResourcesMaxSubprocesses(max) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSecurityResourcesMemoryMonitoring */
    fun updateSecurityResourcesMemoryMonitoring(enabled: Boolean) {
        updateDaemonSetting { setSecurityResourcesMemoryMonitoring(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSecurityAuditEnabled */
    fun updateSecurityAuditEnabled(enabled: Boolean) {
        updateDaemonSetting { setSecurityAuditEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSecurityOtpEnabled */
    fun updateSecurityOtpEnabled(enabled: Boolean) {
        updateDaemonSetting { setSecurityOtpEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSecurityOtpMethod */
    fun updateSecurityOtpMethod(method: String) {
        updateDaemonSetting { setSecurityOtpMethod(method) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSecurityOtpTokenTtlSecs */
    fun updateSecurityOtpTokenTtlSecs(secs: Int) {
        updateDaemonSetting { setSecurityOtpTokenTtlSecs(secs) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSecurityOtpCacheValidSecs */
    fun updateSecurityOtpCacheValidSecs(secs: Int) {
        updateDaemonSetting { setSecurityOtpCacheValidSecs(secs) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSecurityOtpGatedActions */
    fun updateSecurityOtpGatedActions(actions: String) {
        updateDaemonSetting { setSecurityOtpGatedActions(actions) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSecurityOtpGatedDomains */
    fun updateSecurityOtpGatedDomains(domains: String) {
        updateDaemonSetting { setSecurityOtpGatedDomains(domains) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSecurityOtpGatedDomainCategories */
    fun updateSecurityOtpGatedDomainCategories(categories: String) {
        updateDaemonSetting { setSecurityOtpGatedDomainCategories(categories) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSecurityEstopEnabled */
    fun updateSecurityEstopEnabled(enabled: Boolean) {
        updateDaemonSetting { setSecurityEstopEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setSecurityEstopRequireOtpToResume */
    fun updateSecurityEstopRequireOtpToResume(required: Boolean) {
        updateDaemonSetting { setSecurityEstopRequireOtpToResume(required) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMemoryQdrantUrl */
    fun updateMemoryQdrantUrl(url: String) {
        updateDaemonSetting { setMemoryQdrantUrl(url) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMemoryQdrantCollection */
    fun updateMemoryQdrantCollection(collection: String) {
        updateDaemonSetting { setMemoryQdrantCollection(collection) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setMemoryQdrantApiKey */
    fun updateMemoryQdrantApiKey(key: String) {
        updateDaemonSetting { setMemoryQdrantApiKey(key) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setEmbeddingRoutesJson */
    fun updateEmbeddingRoutesJson(json: String) {
        updateDaemonSetting { setEmbeddingRoutesJson(json) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setQueryClassificationEnabled */
    fun updateQueryClassificationEnabled(enabled: Boolean) {
        updateDaemonSetting { setQueryClassificationEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setProxyEnabled */
    fun updateProxyEnabled(enabled: Boolean) {
        updateDaemonSetting { setProxyEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setProxyHttpProxy */
    fun updateProxyHttpProxy(proxy: String) {
        updateDaemonSetting { setProxyHttpProxy(proxy) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setProxyHttpsProxy */
    fun updateProxyHttpsProxy(proxy: String) {
        updateDaemonSetting { setProxyHttpsProxy(proxy) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setProxyAllProxy */
    fun updateProxyAllProxy(proxy: String) {
        updateDaemonSetting { setProxyAllProxy(proxy) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setProxyNoProxy */
    fun updateProxyNoProxy(noProxy: String) {
        updateDaemonSetting { setProxyNoProxy(noProxy) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setProxyScope */
    fun updateProxyScope(scope: String) {
        updateDaemonSetting { setProxyScope(scope) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setProxyServiceSelectors */
    fun updateProxyServiceSelectors(selectors: String) {
        updateDaemonSetting { setProxyServiceSelectors(selectors) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setReliabilityBackoffMs */
    fun updateReliabilityBackoffMs(ms: Long) {
        updateDaemonSetting { setReliabilityBackoffMs(ms) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setReliabilityApiKeysJson */
    fun updateReliabilityApiKeysJson(json: String) {
        updateDaemonSetting { setReliabilityApiKeysJson(json) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setLockEnabled */
    fun updateLockEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setLockEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setLockTimeoutMinutes */
    fun updateLockTimeoutMinutes(minutes: Int) {
        viewModelScope.launch { repository.setLockTimeoutMinutes(minutes) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setPinHash */
    fun updatePinHash(hash: String) {
        viewModelScope.launch { repository.setPinHash(hash) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setPluginRegistryUrl */
    fun updatePluginRegistryUrl(url: String) {
        viewModelScope.launch { repository.setPluginRegistryUrl(url) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setPluginSyncEnabled */
    fun updatePluginSyncEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setPluginSyncEnabled(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setPluginSyncIntervalHours */
    fun updatePluginSyncIntervalHours(hours: Int) {
        viewModelScope.launch { repository.setPluginSyncIntervalHours(hours) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setLastPluginSyncTimestamp */
    fun updateLastPluginSyncTimestamp(timestamp: Long) {
        viewModelScope.launch { repository.setLastPluginSyncTimestamp(timestamp) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setStripThinkingTags */
    fun updateStripThinkingTags(enabled: Boolean) {
        viewModelScope.launch { repository.setStripThinkingTags(enabled) }
    }

    /** @see org.eu.nl.syu.zeroclaw.data.repository.SettingsRepository.setTheme */
    fun updateTheme(theme: ThemeMode) {
        viewModelScope.launch { repository.setTheme(theme) }
    }

    /**
     * Updates the enabled state of an official plugin in [AppSettings].
     *
     * Dispatches to the correct setting based on the [OfficialPlugins]
     * constant. Vision has no enable toggle (always active), so toggling
     * it is a no-op.
     *
     * @param pluginId One of the [OfficialPlugins] constant IDs.
     * @param enabled New enabled state.
     */
    fun updateOfficialPluginEnabled(
        pluginId: String,
        enabled: Boolean,
    ) {
        when (pluginId) {
            OfficialPlugins.WEB_SEARCH -> updateWebSearchEnabled(enabled)
            OfficialPlugins.WEB_FETCH -> updateWebFetchEnabled(enabled)
            OfficialPlugins.HTTP_REQUEST -> updateHttpRequestEnabled(enabled)
            OfficialPlugins.COMPOSIO -> updateComposioEnabled(enabled)
            OfficialPlugins.TRANSCRIPTION -> updateTranscriptionEnabled(enabled)
            OfficialPlugins.QUERY_CLASSIFICATION -> updateQueryClassificationEnabled(enabled)
            else -> {}
        }
    }

    /**
     * Resets onboarding completion state so the setup wizard is shown again.
     *
     * Clears the AIEOS identity JSON so the wizard generates a fresh
     * identity document. Existing API keys and other settings are preserved.
     */
    fun resetOnboarding() {
        viewModelScope.launch {
            repository.setIdentityJson("")
            daemonBridge.markRestartRequired()
            onboardingRepository.reset()
        }
    }

    /** Constants for [SettingsViewModel]. */
    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
