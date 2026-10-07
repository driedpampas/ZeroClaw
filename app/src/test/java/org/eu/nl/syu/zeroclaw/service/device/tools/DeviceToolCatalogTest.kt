/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device.tools

import org.eu.nl.syu.zeroclaw.service.device.DeviceAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DeviceToolCatalog].
 *
 * Verifies the catalog is well-formed and stays in sync with the
 * [DeviceAction] parser: every documented example must parse to a
 * real action, so the model can only request executable tools.
 */
@DisplayName("DeviceToolCatalog")
class DeviceToolCatalogTest {
    @Test
    @DisplayName("tool names are unique and non-blank")
    fun `names unique`() {
        val names = DeviceToolCatalog.tools.map { it.name }
        assertTrue(names.isNotEmpty())
        assertTrue(names.all { it.isNotBlank() })
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    @DisplayName("every tool has a description and example")
    fun `descriptions and examples present`() {
        for (tool in DeviceToolCatalog.tools) {
            assertTrue(tool.description.isNotBlank(), tool.name)
            assertTrue(tool.example.isNotBlank(), tool.name)
        }
    }

    @Test
    @DisplayName("every example parses to an executable action")
    fun `examples parse`() {
        for (tool in DeviceToolCatalog.tools) {
            val action = DeviceAction.parse(tool.example)
            assertNotEquals(
                DeviceAction.NoOp,
                action,
                "Example for ${tool.name} must parse: ${tool.example}",
            )
        }
    }

    @Test
    @DisplayName("prompt section lists every tool")
    fun `prompt section complete`() {
        val section = DeviceToolCatalog.promptSection()
        for (tool in DeviceToolCatalog.tools) {
            assertTrue(section.contains(tool.name), tool.name)
            assertTrue(section.contains(tool.example), tool.name)
        }
    }
}
