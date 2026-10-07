/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [UiTreeSerializer].
 *
 * Verifies the compact format, depth and node caps, text truncation,
 * and stable hashing used by the same-screen stuck detector.
 */
@DisplayName("UiTreeSerializer")
class UiTreeSerializerTest {
    private fun button(label: String): UiNode =
        UiNode(
            className = "Button",
            resourceId = "com.app:id/ok",
            text = label,
            clickable = true,
            enabled = true,
            left = 0,
            top = 0,
            right = 100,
            bottom = 50,
        )

    @Test
    @DisplayName("serializes useful nodes in indexed format")
    fun `serializes useful nodes`() {
        val out = UiTreeSerializer.serialize(listOf(button("OK")), "com.app")
        assertTrue(out.contains("package=com.app"), out)
        assertTrue(out.contains("[0] Button"), out)
        assertTrue(out.contains("\"OK\""), out)
        assertTrue(out.contains("clickable"), out)
        assertTrue(out.contains("bounds=[50,25]"), out)
    }

    @Test
    @DisplayName("skips layout-only nodes")
    fun `skips layout-only nodes`() {
        val layout = UiNode(className = "LinearLayout")
        val out = UiTreeSerializer.serialize(listOf(layout))
        assertEquals("package=unknown", out.trim())
    }

    @Test
    @DisplayName("respects the depth cap")
    fun `respects depth cap`() {
        var deep: UiNode = button("leaf")
        repeat(10) { deep = UiNode(className = "Group", text = "lvl", children = listOf(deep)) }
        val capped = UiTreeSerializer.serialize(listOf(deep), maxDepth = 2)
        val full = UiTreeSerializer.serialize(listOf(deep), maxDepth = 12)
        assertTrue(capped.lines().size < full.lines().size)
    }

    @Test
    @DisplayName("caps node count and truncates long text")
    fun `caps nodes and truncates text`() {
        val many = List(500) { button("b$it") }
        val out = UiTreeSerializer.serialize(many)
        assertTrue(out.lines().size <= UiTreeSerializer.MAX_NODES + 1, "lines=${out.lines().size}")

        val long = button("x".repeat(500))
        val single = UiTreeSerializer.serialize(listOf(long))
        assertTrue(!single.contains("x".repeat(500)))
        assertTrue(single.contains("x".repeat(UiTreeSerializer.MAX_TEXT_LENGTH)))
    }

    @Test
    @DisplayName("stable hash is deterministic and screen-sensitive")
    fun `stable hash behavior`() {
        val first = UiTreeSerializer.serialize(listOf(button("OK")))
        val same = UiTreeSerializer.serialize(listOf(button("OK")))
        val other = UiTreeSerializer.serialize(listOf(button("Cancel")))
        assertEquals(UiTreeSerializer.stableHash(first), UiTreeSerializer.stableHash(same))
        assertNotEquals(UiTreeSerializer.stableHash(first), UiTreeSerializer.stableHash(other))
        assertEquals(64, UiTreeSerializer.stableHash(first).length)
    }
}
