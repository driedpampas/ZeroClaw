/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.eu.nl.syu.zeroclaw.model.AppSettings
import org.eu.nl.syu.zeroclaw.model.ThemeMode
import org.eu.nl.syu.zeroclaw.navigation.ZeroClawAppShell
import org.eu.nl.syu.zeroclaw.ui.theme.ZeroClawTheme

/**
 * Main entry point for the ZeroClaw Android application.
 *
 * Sets up edge-to-edge display and delegates all UI to
 * [ZeroClawAppShell] which manages navigation, the adaptive
 * navigation bar, and all screens.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // FLAG_SECURE blocks screenshots/screen recording (and adb screencap),
        // protecting configured secrets. Skip it in debuggable builds so tooling
        // and developers can capture the UI; release builds keep the protection.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        setContent {
            val app = application as ZeroClawApplication
            val settings by app.settingsRepository.settings
                .collectAsStateWithLifecycle(
                    initialValue = AppSettings(),
                )
            val darkTheme =
                when (settings.theme) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            ZeroClawTheme(darkTheme = darkTheme) {
                @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
                val windowSizeClass = calculateWindowSizeClass(this@MainActivity)
                ZeroClawAppShell(
                    windowWidthSizeClass = windowSizeClass.widthSizeClass,
                )
            }
        }
    }
}
