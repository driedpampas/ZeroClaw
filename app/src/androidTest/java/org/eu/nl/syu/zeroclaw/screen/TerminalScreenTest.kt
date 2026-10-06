/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.screen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.eu.nl.syu.zeroclaw.model.ServiceState
import org.eu.nl.syu.zeroclaw.screen.helpers.fakeTerminalState
import org.eu.nl.syu.zeroclaw.ui.screen.terminal.StreamingState
import org.eu.nl.syu.zeroclaw.ui.screen.terminal.TerminalBlock
import org.eu.nl.syu.zeroclaw.ui.screen.terminal.TerminalContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose screen tests for [TerminalContent].
 *
 * Uses the stateless [TerminalContent] composable with fake state to verify
 * rendering of terminal blocks, input bar controls, loading indicators,
 * and error display.
 */
@RunWith(AndroidJUnit4::class)
class TerminalScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun terminalContent_rendersSystemBlock() {
        val state = fakeTerminalState()
        composeTestRule.setContent {
            TerminalContent(
                state = state,
                streamingState = StreamingState(),
                serviceState = ServiceState.RUNNING,
                onSubmit = {},
                onAttachImages = {},
                onRemoveImage = {},
                onCancelAgent = {},
                edgeMargin = 16.dp,
            )
        }
        composeTestRule
            .onNodeWithText("ZeroClaw Terminal v0.0.37 \u2014 Type /help for commands")
            .assertIsDisplayed()
    }

    @Test
    fun inputBar_hasSendButton() {
        composeTestRule.setContent {
            TerminalContent(
                state = fakeTerminalState(),
                streamingState = StreamingState(),
                serviceState = ServiceState.RUNNING,
                onSubmit = {},
                onAttachImages = {},
                onRemoveImage = {},
                onCancelAgent = {},
                edgeMargin = 16.dp,
            )
        }
        composeTestRule
            .onNodeWithContentDescription("Send")
            .assertIsDisplayed()
    }

    @Test
    fun inputBar_hasAttachButton() {
        composeTestRule.setContent {
            TerminalContent(
                state = fakeTerminalState(),
                streamingState = StreamingState(),
                serviceState = ServiceState.RUNNING,
                onSubmit = {},
                onAttachImages = {},
                onRemoveImage = {},
                onCancelAgent = {},
                edgeMargin = 16.dp,
            )
        }
        composeTestRule
            .onNodeWithContentDescription("Attach images")
            .assertIsDisplayed()
    }

    @Test
    fun spinner_showsWhenLoading() {
        val state = fakeTerminalState().copy(isLoading = true)
        composeTestRule.setContent {
            TerminalContent(
                state = state,
                streamingState = StreamingState(),
                serviceState = ServiceState.RUNNING,
                onSubmit = {},
                onAttachImages = {},
                onRemoveImage = {},
                onCancelAgent = {},
                edgeMargin = 16.dp,
            )
        }
        composeTestRule
            .onNodeWithText("Thinking\u2026")
            .assertIsDisplayed()
    }

    @Test
    fun errorBlock_showsErrorPrefix() {
        val state =
            fakeTerminalState().copy(
                blocks =
                    listOf(
                        TerminalBlock.Error(
                            id = 2,
                            timestamp = System.currentTimeMillis(),
                            message = "Connection refused",
                        ),
                    ),
            )
        composeTestRule.setContent {
            TerminalContent(
                state = state,
                streamingState = StreamingState(),
                serviceState = ServiceState.RUNNING,
                onSubmit = {},
                onAttachImages = {},
                onRemoveImage = {},
                onCancelAgent = {},
                edgeMargin = 16.dp,
            )
        }
        composeTestRule
            .onNodeWithText("Error: Connection refused")
            .assertIsDisplayed()
    }

    @Test
    fun terminalHeader_showsStatusDot() {
        composeTestRule.setContent {
            TerminalContent(
                state = fakeTerminalState(),
                streamingState = StreamingState(),
                serviceState = ServiceState.RUNNING,
                onSubmit = {},
                onAttachImages = {},
                onRemoveImage = {},
                onCancelAgent = {},
                edgeMargin = 16.dp,
            )
        }
        composeTestRule
            .onNodeWithContentDescription("ZeroClaw Terminal, status: running")
            .assertIsDisplayed()
    }
}
