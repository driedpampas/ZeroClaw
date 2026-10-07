/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DeviceAction].
 *
 * Verifies LLM JSON parsing, swipe duration clamping, command mapping,
 * and that event names carry no payload (never screen contents).
 */
@DisplayName("DeviceAction")
class DeviceActionTest {
    @Test
    @DisplayName("parses tap coordinates")
    fun `parses tap coordinates`() {
        val action = DeviceAction.parse("{\"action\":\"tap\",\"x\":100,\"y\":200}")
        assertEquals(DeviceAction.Tap(100, 200), action)
    }

    @Test
    @DisplayName("parses swipe with duration")
    fun `parses swipe with duration`() {
        val action = DeviceAction.parse(
            "{\"action\":\"swipe\",\"x1\":1,\"y1\":2,\"x2\":3,\"y2\":4,\"duration_ms\":500}",
        )
        assertEquals(DeviceAction.Swipe(1, 2, 3, 4, 500), action)
    }

    @Test
    @DisplayName("parses type and click selectors")
    fun `parses type and click selectors`() {
        assertEquals(
            DeviceAction.Type("hello", "com.app:id/field"),
            DeviceAction.parse("{\"action\":\"type\",\"text\":\"hello\",\"resource_id\":\"com.app:id/field\"}"),
        )
        assertEquals(
            DeviceAction.ClickNode("com.app:id/btn", null),
            DeviceAction.parse("{\"action\":\"click\",\"resource_id\":\"com.app:id/btn\"}"),
        )
    }

    @Test
    @DisplayName("parses global actions and finish")
    fun `parses global actions and finish`() {
        assertEquals(
            DeviceAction.Global(DeviceAction.GLOBAL_ACTION_BACK),
            DeviceAction.parse("{\"action\":\"back\"}"),
        )
        assertEquals(
            DeviceAction.Finish("done"),
            DeviceAction.parse("{\"action\":\"finish\",\"summary\":\"done\"}"),
        )
    }

    @Test
    @DisplayName("malformed input yields NoOp instead of throwing")
    fun `malformed input yields NoOp`() {
        assertEquals(DeviceAction.NoOp, DeviceAction.parse("not json"))
        assertEquals(DeviceAction.NoOp, DeviceAction.parse("{\"action\":\"tap\"}"))
        assertEquals(DeviceAction.NoOp, DeviceAction.parse("{\"action\":\"unknown\"}"))
        assertEquals(DeviceAction.NoOp, DeviceAction.parse("{\"action\":\"type\",\"text\":\"\"}"))
    }

    @Test
    @DisplayName("swipe duration is clamped to the accepted range")
    fun `swipe duration is clamped`() {
        assertEquals(50, DeviceAction.Swipe(0, 0, 1, 1, 1).safeDurationMs)
        assertEquals(5_000, DeviceAction.Swipe(0, 0, 1, 1, 99_999).safeDurationMs)
        assertEquals(300, DeviceAction.Swipe(0, 0, 1, 1, 300).safeDurationMs)
    }

    @Test
    @DisplayName("event names carry no payload")
    fun `event names carry no payload`() {
        val actions: List<DeviceAction> =
            listOf(
                DeviceAction.Tap(11, 22),
                DeviceAction.Swipe(1, 2, 3, 4),
                DeviceAction.Type("secret-text"),
                DeviceAction.ClickNode(null, "secret-label"),
                DeviceAction.Global(1),
                DeviceAction.Finish("summary"),
                DeviceAction.NoOp,
            )
        for (action in actions) {
            assertTrue(action.eventName.startsWith("ACTION_"), action.eventName)
            assertTrue(!action.eventName.contains("secret"), action.eventName)
            assertTrue(!action.eventName.contains("11"), action.eventName)
        }
    }

    @Test
    @DisplayName("fromAction maps gestures and skips NoOp and Finish")
    fun `fromAction maps gestures`() {
        assertEquals(DeviceCommand.Type.TAP, DeviceCommand.fromAction(DeviceAction.Tap(1, 2))?.type)
        assertEquals(DeviceCommand.Type.SWIPE, DeviceCommand.fromAction(DeviceAction.Swipe(1, 2, 3, 4))?.type)
        assertEquals(
            DeviceCommand.Type.SET_TEXT,
            DeviceCommand.fromAction(DeviceAction.Type("x"))?.type,
        )
        assertNull(DeviceCommand.fromAction(DeviceAction.NoOp))
        assertNull(DeviceCommand.fromAction(DeviceAction.Finish()))
    }
}
