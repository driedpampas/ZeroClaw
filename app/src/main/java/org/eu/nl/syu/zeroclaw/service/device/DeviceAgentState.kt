/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

/**
 * Lifecycle states of the on-device agent.
 *
 * The agent is [IDLE] when no task is running. [ACTIVE] while the
 * Perceive → Reason → Act loop is iterating, [PAUSED] when the user
 * touched the screen (or explicitly paused) and the loop is blocked
 * waiting for resume. Terminal states are [DONE] (goal reached),
 * [STUCK] (max steps or same-screen detector fired), and [ERROR].
 */
enum class DeviceAgentState {
    /** No task running; no border overlay shown. */
    IDLE,

    /** Loop iterating; blue border overlay shown. */
    ACTIVE,

    /** Loop blocked on pause latch; amber border overlay shown. */
    PAUSED,

    /** Task finished normally; overlay removed. */
    DONE,

    /** Max steps or same-screen detector fired; overlay removed. */
    STUCK,

    /** Unrecoverable error; overlay removed. */
    ERROR,
}
