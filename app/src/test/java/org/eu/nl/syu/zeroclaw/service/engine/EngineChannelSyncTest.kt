/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.engine

import org.eu.nl.syu.zeroclaw.model.ChannelType
import org.eu.nl.syu.zeroclaw.model.ConnectedChannel
import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineChannelSyncTest {
    private val sync = EngineChannelSync(GatewayClient("http://127.0.0.1:1"))

    private val discordSchema =
        mapOf(
            "channels.discord.discord.enabled" to "bool",
            "channels.discord.discord.bot_token" to "string",
            "channels.discord.discord.guild_ids" to "string-array",
            "channels.discord.discord.mention_only" to "bool",
        )

    private fun discord(values: Map<String, String>): List<Pair<ConnectedChannel, Map<String, String>>> =
        listOf(
            ConnectedChannel(id = "1", type = ChannelType.DISCORD, isEnabled = true) to values,
        )

    @Test
    fun `writes schema-known channel fields and binds the agent`() {
        val ops =
            sync.buildChannelOps(
                discord(
                    mapOf(
                        "bot_token" to "secret-token",
                        "guild_ids" to "111, 222",
                        "mention_only" to "true",
                        // Not in the engine schema — must be ignored, not written.
                        "allowed_users" to "42",
                    ),
                ),
                discordSchema,
                "main",
            )
        requireNotNull(ops)

        val paths = (0 until ops.operations.length()).map { ops.operations.getJSONObject(it).getString("path") }
        assertTrue(paths.contains("/channels/discord/discord/bot_token"))
        assertTrue(paths.contains("/channels/discord/discord/guild_ids"))
        assertTrue(paths.contains("/channels/discord/discord/mention_only"))
        assertTrue(paths.contains("/channels/discord/discord/enabled"))
        assertTrue(paths.none { it.contains("allowed_users") })

        val guildIds = opValue(ops.operations, "/channels/discord/discord/guild_ids") as JSONArray
        assertEquals(listOf("111", "222"), (0 until guildIds.length()).map { guildIds.getString(it) })
        assertEquals(true, opValue(ops.operations, "/channels/discord/discord/mention_only"))

        val bound = opValue(ops.operations, "/agents/main/channels") as JSONArray
        assertEquals(listOf("discord.discord"), (0 until bound.length()).map { bound.getString(it) })
        assertEquals(listOf("discord.discord"), ops.refs)
    }

    @Test
    fun `unknown channel section is skipped`() {
        val ops = sync.buildChannelOps(discord(mapOf("bot_token" to "x")), emptyMap(), "main")
        assertNull(ops)
    }

    private fun opValue(
        operations: JSONArray,
        path: String,
    ): Any? =
        (0 until operations.length())
            .map { operations.getJSONObject(it) }
            .first { it.getString("path") == path }
            .get("value")
}
