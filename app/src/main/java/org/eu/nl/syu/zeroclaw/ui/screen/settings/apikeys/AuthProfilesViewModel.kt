/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.ui.screen.settings.apikeys

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.eu.nl.syu.zeroclaw.ZeroClawApplication
import org.eu.nl.syu.zeroclaw.service.engine.EngineCli
import org.eu.nl.syu.zeroclaw.util.ErrorSanitizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Presentation model for a single auth profile displayed in the list.
 *
 * Maps from the raw FFI [FfiAuthProfile] record to a UI-friendly
 * representation with pre-formatted timestamp strings.
 *
 * @property id Profile ID in "provider:profile_name" format.
 * @property provider Provider name (e.g. "openai-codex", "gemini").
 * @property profileName Human-readable profile display name.
 * @property kind Profile kind label: "OAuth" or "Token".
 * @property isActive Whether this is the active profile for its provider.
 * @property expiryLabel Formatted expiry string, or null if no expiry.
 * @property createdLabel Formatted creation date string.
 * @property updatedLabel Formatted last-update date string.
 */
data class AuthProfileItem(
    val id: String,
    val provider: String,
    val profileName: String,
    val kind: String,
    val isActive: Boolean,
    val expiryLabel: String?,
    val createdLabel: String,
    val updatedLabel: String,
)

/**
 * UI state for the auth profiles screen.
 *
 * @param T The type of content data.
 */
sealed interface AuthProfilesUiState<out T> {
    /** Data is being loaded from the FFI layer. */
    data object Loading : AuthProfilesUiState<Nothing>

    /**
     * Loading or mutation failed.
     *
     * @property detail Human-readable error message.
     */
    data class Error(
        val detail: String,
    ) : AuthProfilesUiState<Nothing>

    /**
     * Data loaded successfully.
     *
     * @param T Content data type.
     * @property data The loaded content.
     */
    data class Content<T>(
        val data: T,
    ) : AuthProfilesUiState<T>
}

/**
 * ViewModel for the auth profiles management screen.
 *
 * Loads OAuth and token profiles from the FFI layer and exposes
 * list and delete operations. Profiles are mapped to [AuthProfileItem]
 * with formatted timestamps for display.
 *
 * @param application Application context used by [AndroidViewModel].
 */
class AuthProfilesViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val cli: EngineCli? = (application as? ZeroClawApplication)?.daemonBridge?.cli

    private val _uiState =
        MutableStateFlow<AuthProfilesUiState<List<AuthProfileItem>>>(
            AuthProfilesUiState.Loading,
        )

    /** Observable UI state for the auth profiles list. */
    val uiState: StateFlow<AuthProfilesUiState<List<AuthProfileItem>>> =
        _uiState.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)

    /**
     * One-shot snackbar message shown after a successful mutation.
     *
     * Collect with `collectAsStateWithLifecycle` and call [clearSnackbar]
     * after displaying.
     */
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    init {
        loadProfiles()
    }

    /** Reloads the auth profiles list from the native layer. */
    fun loadProfiles() {
        _uiState.value = AuthProfilesUiState.Loading
        viewModelScope.launch {
            loadProfilesInternal()
        }
    }

    /**
     * Removes an auth profile by provider and profile name.
     *
     * After successful removal, refreshes the profile list and
     * shows a snackbar confirmation.
     *
     * @param provider Provider name of the profile to remove.
     * @param profileName Display name of the profile to remove.
     */
    fun removeProfile(
        provider: String,
        profileName: String,
    ) {
        viewModelScope.launch {
            runMutation("Profile removed") {
                withContext(Dispatchers.IO) {
                    val runner = cli ?: throw IllegalStateException("Engine CLI unavailable")
                    val result =
                        runner.run(
                            "auth",
                            "logout",
                            "--model-provider",
                            provider,
                            "--profile",
                            profileName.ifBlank { "default" },
                        )
                    if (!result.isSuccess) {
                        throw IllegalStateException(result.stderr.ifBlank { "logout failed" })
                    }
                }
            }
        }
    }

    /** Clears the current snackbar message. */
    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadProfilesInternal() {
        try {
            val items =
                withContext(Dispatchers.IO) {
                    val runner = cli ?: return@withContext emptyList()
                    parseAuthProfiles(runner.run("auth", "list").stdout)
                }
            _uiState.value = AuthProfilesUiState.Content(items)
        } catch (e: Exception) {
            _uiState.value =
                AuthProfilesUiState.Error(
                    ErrorSanitizer.sanitizeForUi(e),
                )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runMutation(
        successMessage: String,
        block: suspend () -> Any?,
    ) {
        try {
            block()
            _snackbarMessage.value = successMessage
            loadProfilesInternal()
        } catch (e: Exception) {
            _snackbarMessage.value = ErrorSanitizer.sanitizeForUi(e)
        }
    }

    /** Utility functions for mapping FFI profiles to presentation models. */
    companion object {
        /** Date format pattern for displaying profile timestamps. */
        private const val DATE_FORMAT_PATTERN = "MMM d, yyyy HH:mm"

        /**
         * Formats an epoch-millisecond timestamp as a short date string.
         *
         * @param epochMs Epoch milliseconds to format.
         * @return Formatted date string.
         */
        internal fun formatTimestamp(epochMs: Long): String {
            val formatter = SimpleDateFormat(DATE_FORMAT_PATTERN, Locale.getDefault())
            return formatter.format(Date(epochMs))
        }

        /**
         * Parses `zeroclaw auth list` output into presentation models.
         *
         * The CLI prints a human-readable table; this parser is tolerant of
         * leading markers and returns an empty list when no profiles exist.
         *
         * @param output Raw stdout from the CLI.
         * @return Parsed profile items.
         */
        internal fun parseAuthProfiles(output: String): List<AuthProfileItem> {
            if (output.isBlank() || output.contains("No auth profiles", ignoreCase = true)) {
                return emptyList()
            }
            val now = formatTimestamp(System.currentTimeMillis())
            return output
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("-") }
                .mapNotNull { line ->
                    val active = line.contains("*") || line.contains("(active)")
                    val cleaned = line.replace("*", "").replace("(active)", "").trim()
                    val tokens = cleaned.split(Regex("\\s{2,}|\\t")).filter(String::isNotBlank)
                    if (tokens.isEmpty()) return@mapNotNull null
                    val provider = tokens.getOrNull(0)?.removeSuffix(":") ?: return@mapNotNull null
                    val profileName = tokens.getOrNull(1)?.removeSuffix(":") ?: "default"
                    AuthProfileItem(
                        id = "$provider:$profileName",
                        provider = provider,
                        profileName = profileName,
                        kind = if (cleaned.contains("oauth", ignoreCase = true)) "OAuth" else "Token",
                        isActive = active,
                        expiryLabel = null,
                        createdLabel = now,
                        updatedLabel = now,
                    )
                }.toList()
        }
    }
}
