/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eu.nl.syu.zeroclaw.model.Skill
import org.eu.nl.syu.zeroclaw.service.engine.EngineCli
import org.eu.nl.syu.zeroclaw.service.engine.GatewayClient
import org.eu.nl.syu.zeroclaw.service.engine.listAt
import org.eu.nl.syu.zeroclaw.service.engine.obj
import org.eu.nl.syu.zeroclaw.service.engine.string
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge between the Android UI layer and skills.
 *
 * Listing and removal use the gateway bundle API
 * (`/api/skills/bundles/{alias}/skills`). Installation from a URL or path is
 * only exposed on the engine CLI, so it is delegated to [EngineCli].
 *
 * @param ioDispatcher Dispatcher for blocking HTTP calls.
 * @param client Gateway client; defaults to the loopback engine gateway.
 * @param cli Engine CLI runner for operations without an HTTP endpoint.
 */
class SkillsBridge(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val client: GatewayClient = GatewayClient(GatewayClient.loopback()),
    private val cli: EngineCli? = null,
) {
    private data class SkillRef(
        val bundle: String,
        val name: String,
        val skill: Skill,
    )

    /** Lists all skills across every configured bundle. */
    @Throws(IOException::class)
    suspend fun listSkills(): List<Skill> = refs().map { it.skill }

    /**
     * Installs a skill from a URL or local path via the engine CLI.
     *
     * @throws IOException when no CLI runner is configured or the command fails.
     */
    @Throws(IOException::class)
    suspend fun installSkill(source: String) {
        val runner =
            cli ?: throw IOException("Skill installation requires the engine CLI runner")
        val result = runner.installSkill(source)
        if (!result.isSuccess) {
            throw IOException(result.stderr.ifBlank { "skills install failed (exit ${result.exitCode})" })
        }
    }

    /** Removes an installed skill by name from its owning bundle. */
    @Throws(IOException::class)
    suspend fun removeSkill(name: String) {
        val ref =
            refs().firstOrNull { it.name == name }
                ?: throw IOException("Skill not found: $name")
        withContext(ioDispatcher) {
            client.delete("/api/skills/bundles/${encode(ref.bundle)}/skills/${encode(ref.name)}")
        }
    }

    private suspend fun refs(): List<SkillRef> =
        withContext(ioDispatcher) {
            val bundles = client.skillBundles().listAt("bundles")
            bundles.flatMap { bundle ->
                val alias = bundle.string("alias").orEmpty()
                if (alias.isBlank()) return@flatMap emptyList()
                client
                    .get("/api/skills/bundles/${encode(alias)}/skills")
                    .listAt("skills")
                    .mapNotNull { entry ->
                        val name = entry.string("name") ?: return@mapNotNull null
                        SkillRef(
                            bundle = entry.string("bundle") ?: alias,
                            name = name,
                            skill = entry.toSkill(name),
                        )
                    }
            }
        }

    private fun JSONObject.toSkill(name: String): Skill {
        val frontmatter = obj("frontmatter") ?: JSONObject()
        val tags = frontmatter.stringList("tags")
        val toolNames =
            frontmatter
                .stringList("tools")
                .ifEmpty { frontmatter.stringList("allowed-tools", "allowed_tools") }
        return Skill(
            name = name,
            description = frontmatter.string("description").orEmpty(),
            version = frontmatter.string("version").orEmpty(),
            author = frontmatter.string("author").orEmpty(),
            tags = tags,
            toolCount = toolNames.size,
            toolNames = toolNames,
        )
    }

    private fun JSONObject.stringList(vararg keys: String): List<String> {
        for (key in keys) {
            when (val value = opt(key)) {
                is JSONArray -> return (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
                is String -> if (value.isNotBlank()) return value.split(",").map(String::trim)
            }
        }
        return emptyList()
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
