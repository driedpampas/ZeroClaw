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
    @DisplayName("nested /api/status health snapshot parses components and uptime")
    fun `nested health snapshot parses`() {
        val checks =
            validator.parseDaemonStatus(
                JSONObject(
                    """{"health": {"uptime_seconds": 7, "components": {"socket": {"status": "ok"}}}}""",
                ),
            )
        assertEquals(CheckStatus.PASS, checks.first { it.id == "daemon-component-socket" }.status)
        assertEquals("7s", checks.first { it.id == "daemon-uptime" }.detail)
    }

    @Test
    @DisplayName("starting component is a warning, not a failure")
    fun `starting component warns`() {
        val checks =
            validator.parseDaemonStatus(
                JSONObject(
                    """{"health": {"components": {"channel:discord.discord": {"status": "starting"}}}}""",
                ),
            )
        assertEquals(CheckStatus.WARN, checks.first { it.id == "daemon-component-channel:discord.discord" }.status)
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
    @DisplayName("composite channel name matches bare expected type")
    fun `composite name matches expected type`() {
        val checks =
            validator.parseChannelDiagnostics(
                """[{"name": "discord.discord", "type": "discord", "alias": "discord", "status": "unknown", "health": "degraded"}]""",
                expectedChannels = listOf("discord"),
            )
        assertTrue(checks.none { it.id.startsWith("channel-missing-") })
    }

    @Test
    @DisplayName("running listener makes a degraded channel connected")
    fun `listener health makes degraded channel pass`() {
        val checks =
            validator.parseChannelDiagnostics(
                """[{"name": "discord.discord", "type": "discord", "alias": "discord", "status": "unknown", "health": "degraded", "owning_agent": null, "readiness": {"requirements": ["Bind this channel to an enabled agent."], "notes": []}}]""",
                expectedChannels = listOf("discord"),
                componentHealth = mapOf("channel:discord.discord" to "ok"),
            )
        val channel = checks.first { it.id == "channel-discord.discord" }
        assertEquals(CheckStatus.PASS, channel.status)
        assertEquals("Connected", channel.detail)
    }

    @Test
    @DisplayName("degraded channel without listener is a warning, not offline")
    fun `degraded channel warns not fails`() {
        val checks =
            validator.parseChannelDiagnostics(
                """[{"name": "discord.discord", "type": "discord", "alias": "discord", "status": "unknown", "health": "degraded", "owning_agent": null, "readiness": {"requirements": ["Bind this channel to an enabled agent."], "notes": []}}]""",
                expectedChannels = listOf("discord"),
            )
        val channel = checks.first { it.id == "channel-discord.discord" }
        assertEquals(CheckStatus.WARN, channel.status)
        assertEquals("Bind this channel to an enabled agent.", channel.detail)
    }

    @Test
    @DisplayName("down channel fails with the listener reason")
    fun `down channel fails`() {
        val checks =
            validator.parseChannelDiagnostics(
                """[{"name": "discord.discord", "type": "discord", "alias": "discord", "status": "error", "health": "down"}]""",
                componentHealth = mapOf("channel:discord.discord" to "error"),
            )
        assertEquals(CheckStatus.FAIL, checks.first { it.id == "channel-discord.discord" }.status)
    }

    @Test
    @DisplayName("healthy listener does not mask an engine-reported down channel")
    fun `healthy listener does not mask down channel`() {
        // Webhook awaiting pairing: readiness is error/down while its supervised
        // listener component is ok. The failure must win.
        val checks =
            validator.parseChannelDiagnostics(
                """[{"name": "webhook.main", "type": "webhook", "alias": "main", "status": "error", "health": "down"}]""",
                componentHealth = mapOf("channel:webhook.main" to "ok"),
            )
        assertEquals(CheckStatus.FAIL, checks.first { it.id == "channel-webhook.main" }.status)
    }

    @Test
    @DisplayName("listener component lookup is case-insensitive")
    fun `listener component lookup ignores case`() {
        val checks =
            validator.parseChannelDiagnostics(
                """[{"name": "clawdtalk.clawdtalk", "type": "clawdtalk", "alias": "clawdtalk", "status": "unknown", "health": "degraded", "owning_agent": "main"}]""",
                componentHealth = mapOf("channel:ClawdTalk.clawdtalk" to "ok"),
            )
        assertEquals(CheckStatus.PASS, checks.first { it.id == "channel-clawdtalk.clawdtalk" }.status)
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
