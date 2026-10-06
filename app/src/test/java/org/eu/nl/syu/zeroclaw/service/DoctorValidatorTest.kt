/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service

import io.mockk.mockk
import org.eu.nl.syu.zeroclaw.model.CheckStatus
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DoctorValidator] diagnostic parsing.
 *
 * Covers the daemon-status false-negative (upstream `/api/status` never sends
 * `daemon_running`) and the missing-channel warning for Room-enabled channels
 * the engine does not report.
 */
@DisplayName("DoctorValidator")
class DoctorValidatorTest {
    private val validator =
        DoctorValidator(
            context = mockk(relaxed = true),
            agentRepository = mockk(relaxed = true),
            apiKeyRepository = mockk(relaxed = true),
        )

    @Test
    @DisplayName("status without daemon_running key reports running")
    fun `status without daemon_running reports running`() {
        val checks = validator.parseDaemonStatus(JSONObject("""{"uptime_seconds": 42}"""))
        val running = checks.first { it.id == "daemon-running" }
        assertEquals(CheckStatus.PASS, running.status)
    }

    @Test
    @DisplayName("explicit daemon_running false still reports not running")
    fun `explicit false still reports not running`() {
        val checks = validator.parseDaemonStatus(JSONObject("""{"daemon_running": false}"""))
        val running = checks.first { it.id == "daemon-running" }
        assertEquals(CheckStatus.WARN, running.status)
    }

    @Test
    @DisplayName("healthy component status passes")
    fun `healthy component passes`() {
        val checks =
            validator.parseDaemonStatus(
                JSONObject(
                    """{"uptime_seconds": 1, "components": {"socket": {"status": "healthy"}}}""",
                ),
            )
        val component = checks.first { it.id == "daemon-component-socket" }
        assertEquals(CheckStatus.PASS, component.status)
    }

    @Test
    @DisplayName("engine-unknown expected channel yields warning")
    fun `missing expected channel warns`() {
        val checks =
            validator.parseChannelDiagnostics(
                """[{"name": "telegram", "status": "healthy"}]""",
                expectedChannels = listOf("telegram", "discord"),
            )
        assertTrue(checks.any { it.id == "channel-missing-discord" })
        val missing = checks.first { it.id == "channel-missing-discord" }
        assertEquals(CheckStatus.WARN, missing.status)
    }

    @Test
    @DisplayName("all expected channels live yields no missing warnings")
    fun `all live yields no missing warnings`() {
        val checks =
            validator.parseChannelDiagnostics(
                """[{"name": "discord", "status": "healthy"}]""",
                expectedChannels = listOf("discord"),
            )
        assertTrue(checks.none { it.id.startsWith("channel-missing-") })
    }

    @Test
    @DisplayName("agent alias parsed from dotted config path")
    fun `alias parsed from dotted path`() {
        assertEquals("main", DaemonServiceBridge.agentAliasFromConfigPath("agents.main.model"))
        assertEquals("default", DaemonServiceBridge.agentAliasFromConfigPath("agents.default"))
    }

    @Test
    @DisplayName("non-agent config path yields null alias")
    fun `non-agent path yields null`() {
        assertEquals(null, DaemonServiceBridge.agentAliasFromConfigPath("channels.discord.main"))
        assertEquals(null, DaemonServiceBridge.agentAliasFromConfigPath(null))
    }

    @Test
    @DisplayName("legacy guild_id folds into guild_ids")
    fun `guild_id folds into guild_ids`() {
        val folded = ConfigTomlBuilder.foldLegacyChannelKeys(mapOf("guild_id" to "123"))
        assertEquals("123", folded["guild_ids"])
    }
}
