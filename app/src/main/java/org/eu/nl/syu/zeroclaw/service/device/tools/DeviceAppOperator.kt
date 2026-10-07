/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * A launchable application.
 *
 * @property label Human-readable app name.
 * @property packageName Application package for [DeviceAppOperator.openApp].
 */
data class AppEntry(
    val label: String,
    val packageName: String,
)

/**
 * Opens apps and lists launchable packages for the device tools.
 *
 * Separated from [DeviceToolExecutor] so tests can substitute a fake
 * without a [PackageManager].
 */
interface DeviceAppOperator {
    /**
     * Launches [packageName] via its launcher intent.
     *
     * @param packageName Target application package.
     * @return `true` when the launch intent resolved and was sent.
     */
    fun openApp(packageName: String): Boolean

    /**
     * Lists launchable apps, optionally filtered.
     *
     * @param query Case-insensitive label/package filter, or null for all.
     * @return Matching apps sorted by label.
     */
    fun listApps(query: String?): List<AppEntry>
}

/**
 * [DeviceAppOperator] backed by the platform [PackageManager].
 *
 * Launcher visibility on API 30+ relies on the `<queries>` manifest
 * entry for `ACTION_MAIN`/`CATEGORY_LAUNCHER` (no `QUERY_ALL_PACKAGES`
 * needed).
 *
 * @param context Any context; the application context is used.
 */
class PackageManagerAppOperator(
    context: Context,
) : DeviceAppOperator {
    private val appContext = context.applicationContext
    private val packageManager: PackageManager = appContext.packageManager

    @Suppress("TooGenericExceptionCaught")
    override fun openApp(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            val intent =
                packageManager.getLaunchIntentForPackage(packageName.trim())
                    ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun listApps(query: String?): List<AppEntry> {
        val launcher =
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val filter = query?.trim()?.lowercase().orEmpty()
        return packageManager
            .queryIntentActivities(launcher, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = info.loadLabel(packageManager)?.toString()?.ifBlank { pkg } ?: pkg
                AppEntry(label, pkg)
            }.filter { entry ->
                filter.isEmpty() ||
                    entry.label.lowercase().contains(filter) ||
                    entry.packageName.lowercase().contains(filter)
            }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .take(MAX_LIST_APPS)
    }

    /** App listing cap for [PackageManagerAppOperator]. */
    companion object {
        /** Hard cap on listed apps to bound observation size. */
        const val MAX_LIST_APPS = 100
    }
}
