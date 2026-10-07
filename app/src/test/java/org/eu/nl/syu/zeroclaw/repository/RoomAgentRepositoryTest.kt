/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.repository

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.eu.nl.syu.zeroclaw.data.local.dao.AgentDao
import org.eu.nl.syu.zeroclaw.data.local.entity.AgentEntity
import org.eu.nl.syu.zeroclaw.data.local.entity.toEntity
import org.eu.nl.syu.zeroclaw.data.repository.RoomAgentRepository
import org.eu.nl.syu.zeroclaw.model.Agent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RoomAgentRepositoryTest {
    private lateinit var dao: AgentDao
    private lateinit var repo: RoomAgentRepository

    @BeforeEach
    fun setUp() {
        dao = mockk(relaxUnitFun = true)
        every { dao.observeAll() } returns flowOf(emptyList())
        repo = RoomAgentRepository(dao)
    }

    @Test
    fun `agents flow maps entities to domain models`() =
        runTest {
            val entity = makeEntity("a1", "Alpha")
            every { dao.observeAll() } returns flowOf(listOf(entity))
            repo = RoomAgentRepository(dao)

            repo.agents.test {
                val agents = awaitItem()
                assertEquals(1, agents.size)
                assertEquals("Alpha", agents[0].name)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `getById returns mapped model`() =
        runTest {
            coEvery { dao.getById("a1") } returns makeEntity("a1", "Alpha")

            val result = repo.getById("a1")
            assertEquals("Alpha", result?.name)
        }

    @Test
    fun `getById returns null when not found`() =
        runTest {
            coEvery { dao.getById("missing") } returns null

            assertNull(repo.getById("missing"))
        }

    @Test
    fun `save delegates to dao upsert`() =
        runTest {
            val agent =
                Agent(
                    id = "a1",
                    name = "Alpha",
                    provider = "Test",
                    modelName = "model",
                )
            repo.save(agent)

            coVerify { dao.upsert(agent.toEntity()) }
        }

    @Test
    fun `delete delegates to dao deleteById`() =
        runTest {
            repo.delete("a1")

            coVerify { dao.deleteById("a1") }
        }

    @Test
    fun `toggleEnabled delegates to dao toggleEnabled`() =
        runTest {
            repo.toggleEnabled("a1")

            coVerify { dao.toggleEnabled("a1") }
        }

    private fun makeEntity(
        id: String,
        name: String,
    ): AgentEntity =
        AgentEntity(
            id = id,
            name = name,
            provider = "Test",
            modelName = "model",
            isEnabled = true,
            systemPrompt = "",
            channelsJson = "[]",
        )
}
