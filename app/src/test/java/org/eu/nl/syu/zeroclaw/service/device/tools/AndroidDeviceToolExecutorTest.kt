/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device.tools

import org.eu.nl.syu.zeroclaw.service.device.DeviceAction
import org.eu.nl.syu.zeroclaw.service.device.DeviceCommand
import org.eu.nl.syu.zeroclaw.service.device.DeviceCommandResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AndroidDeviceToolExecutor].
 *
 * Uses a fake [DeviceAppOperator] and a scripted gesture runner so no
 * Android framework calls are made.
 */
@DisplayName("AndroidDeviceToolExecutor")
class AndroidDeviceToolExecutorTest {
    private val opened = mutableListOf<String>()
    private var apps: List<AppEntry> = emptyList()
    private var gestureSuccess = true
    private var lastCommand: DeviceCommand? = null

    private val operator =
        object : DeviceAppOperator {
            override fun openApp(packageName: String): Boolean {
                opened.add(packageName)
                return packageName.isNotBlank()
            }

            override fun listApps(query: String?): List<AppEntry> =
                if (query.isNullOrBlank()) {
                    apps
                } else {
                    apps.filter { it.label.contains(query, ignoreCase = true) }
                }
        }

    private val executor =
        AndroidDeviceToolExecutor(
            appOperator = operator,
            gestureRunner = { command ->
                lastCommand = command
                DeviceCommandResult(success = gestureSuccess)
            },
        )

    @Test
    @DisplayName("gestures dispatch through the runner")
    fun `gestures dispatch`() {
        suspend fun check() {
            val outcome = executor.execute(DeviceAction.Tap(10, 20))
            assertTrue(outcome.success)
            assertEquals(DeviceCommand.Type.TAP, lastCommand?.type)
            assertNull(outcome.observation)
        }
        runBlocking { check() }
    }

    @Test
    @DisplayName("gesture failure propagates without observation")
    fun `gesture failure propagates`() {
        gestureSuccess = false
        runBlocking {
            val outcome = executor.execute(DeviceAction.Swipe(1, 2, 3, 4))
            assertFalse(outcome.success)
            assertNull(outcome.observation)
        }
    }

    @Test
    @DisplayName("open_app delegates to the app operator")
    fun `open app delegates`() {
        runBlocking {
            val outcome = executor.execute(DeviceAction.OpenApp("com.example.app"))
            assertTrue(outcome.success)
            assertEquals(listOf("com.example.app"), opened)
            assertNull(outcome.observation)
        }
    }

    @Test
    @DisplayName("list_apps returns label lines as observation")
    fun `list apps observation`() {
        apps =
            listOf(
                AppEntry("Maps", "com.example.maps"),
                AppEntry("Mail", "com.example.mail"),
            )
        runBlocking {
            val outcome = executor.execute(DeviceAction.ListApps(null))
            assertTrue(outcome.success)
            val observation = outcome.observation.orEmpty()
            assertTrue(observation.contains("Maps (com.example.maps)"), observation)
            assertTrue(observation.contains("Mail (com.example.mail)"), observation)
        }
    }

    @Test
    @DisplayName("empty list_apps still succeeds with a note")
    fun `empty list note`() {
        apps = emptyList()
        runBlocking {
            val outcome = executor.execute(DeviceAction.ListApps("zzz"))
            assertTrue(outcome.success)
            assertTrue(outcome.observation.orEmpty().contains("No launchable apps"), outcome.observation)
        }
    }

    @Test
    @DisplayName("list_apps observation is capped")
    fun `observation capped`() {
        apps = List(80) { AppEntry("App$it", "com.example.app$it") }
        runBlocking {
            val observation = executor.execute(DeviceAction.ListApps(null)).observation.orEmpty()
            val appLines = observation.lines().filter { it.contains("com.example.app") }
            assertEquals(AndroidDeviceToolExecutor.MAX_OBSERVATION_APPS, appLines.size)
        }
    }

    @Test
    @DisplayName("noop and finish succeed without dispatch")
    fun `noop finish succeed`() {
        runBlocking {
            assertTrue(executor.execute(DeviceAction.NoOp).success)
            assertTrue(executor.execute(DeviceAction.Finish()).success)
            assertNull(lastCommand)
        }
    }
}
