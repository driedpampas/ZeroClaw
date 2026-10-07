/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DeviceAgentController].
 *
 * The loop runs on real background threads (the production
 * `Dispatchers.IO` hop), so tests poll state with timeouts instead of
 * virtual time. Perceive/act/reason hooks are scripted fakes; the
 * Android bridge is never touched.
 */
@DisplayName("DeviceAgentController")
class DeviceAgentControllerTest {
    private val perceiveCount = AtomicInteger(0)
    private val actCount = AtomicInteger(0)
    private val screenSeq = AtomicInteger(0)

    @Volatile
    private var fixedScreen: Boolean = false

    @Volatile
    private var script: List<DeviceAction> = emptyList()

    /**
     * Step gate for pacing tests: a gated actor blocks each Act step
     * until the test sends one permit, so pause/resume/stop requests
     * land deterministically mid-task instead of racing a fast loop.
     */
    private var stepGate = Channel<Unit>(Channel.UNLIMITED)

    @BeforeEach
    fun setUp() {
        perceiveCount.set(0)
        actCount.set(0)
        screenSeq.set(0)
        fixedScreen = false
        script = emptyList()
        stepGate = Channel(Channel.UNLIMITED)
        DeviceAgentController.resetForTests()
        DeviceAgentController.perceiver = {
            perceiveCount.incrementAndGet()
            val n = if (fixedScreen) 0 else screenSeq.incrementAndGet()
            UiSnapshot("screen-$n", "hash-$n")
        }
        useUngatedActor()
        DeviceAgentController.reasoner =
            DeviceReasoner { _, _, step ->
                script.getOrElse(step) { DeviceAction.NoOp }
            }
    }

    /** Act hook that runs without blocking (for run-to-terminal tests). */
    private fun useUngatedActor() {
        DeviceAgentController.actor = {
            actCount.incrementAndGet()
            true
        }
    }

    /** Act hook that blocks each step until [releaseStep] is called. */
    private fun useGatedActor() {
        DeviceAgentController.actor = {
            actCount.incrementAndGet()
            stepGate.receive()
            true
        }
    }

    /** Lets one blocked Act step proceed. */
    private suspend fun releaseStep() {
        stepGate.send(Unit)
    }

    @AfterEach
    fun tearDown() {
        DeviceAgentController.stop()
        DeviceAgentController.resetForTests()
    }

    @Test
    @DisplayName("completes when the reasoner returns Finish")
    fun `completes on finish`() {
        script = listOf(DeviceAction.Tap(1, 2), DeviceAction.Finish("done"))
        runBlocking {
            withTimeout(TEST_TIMEOUT_MS) {
                DeviceAgentController.start("goal", maxSteps = 10, scope = this)
                awaitState(DeviceAgentState.DONE)
            }
        }
        assertEquals(1, DeviceAgentController.stepsExecuted)
        assertEquals(1, actCount.get())
    }

    @Test
    @DisplayName("max steps ends the task as stuck")
    fun `max steps ends stuck`() {
        script = emptyList()
        runBlocking {
            withTimeout(TEST_TIMEOUT_MS) {
                DeviceAgentController.start("goal", maxSteps = 3, scope = this)
                awaitState(DeviceAgentState.STUCK)
            }
        }
        assertEquals(3, DeviceAgentController.stepsExecuted)
    }

    @Test
    @DisplayName("same-screen detector fires before max steps")
    fun `same screen detector fires`() {
        fixedScreen = true
        script = emptyList()
        runBlocking {
            withTimeout(TEST_TIMEOUT_MS) {
                DeviceAgentController.start("goal", maxSteps = 20, scope = this)
                awaitState(DeviceAgentState.STUCK)
            }
        }
        assertEquals(DeviceAgentController.SAME_SCREEN_THRESHOLD, DeviceAgentController.stepsExecuted)
    }

    @Test
    @DisplayName("pause parks the loop and resume re-reads the screen")
    fun `pause parks and resume re-reads`() {
        useGatedActor()
        script =
            listOf(
                DeviceAction.Tap(1, 1),
                DeviceAction.Tap(2, 2),
                DeviceAction.Finish("done"),
            )
        runBlocking {
            withTimeout(TEST_TIMEOUT_MS) {
                DeviceAgentController.start("goal", maxSteps = 10, scope = this)
                awaitCount(actCount, 1)
                DeviceAgentController.requestPause(DeviceAgentController.REASON_USER_TOUCH)
                awaitState(DeviceAgentState.PAUSED)
                assertTrue(DeviceAgentController.isPaused)

                // Parked: the loop is blocked in the gated actor, so no
                // step completes and the state never leaves PAUSED.
                val perceivesAtPause = perceiveCount.get()
                delay(PARKED_OBSERVE_MS)
                assertEquals(0, DeviceAgentController.stepsExecuted)
                assertEquals(DeviceAgentState.PAUSED, DeviceAgentController.state.value)

                DeviceAgentController.resume()
                awaitState(DeviceAgentState.ACTIVE)
                releaseStep()
                awaitSteps(1)
                // Resuming re-reads the screen: the next iteration
                // perceives fresh state before finishing.
                releaseStep()
                awaitState(DeviceAgentState.DONE)
                assertTrue(perceiveCount.get() > perceivesAtPause)
            }
        }
        assertFalse(DeviceAgentController.isPaused)
    }

    @Test
    @DisplayName("stop while paused terminates without looping")
    fun `stop while paused terminates`() {
        useGatedActor()
        script = listOf(DeviceAction.Tap(1, 1), DeviceAction.Tap(2, 2))
        runBlocking {
            withTimeout(TEST_TIMEOUT_MS) {
                DeviceAgentController.start("goal", maxSteps = 50, scope = this)
                awaitCount(actCount, 1)
                DeviceAgentController.requestPause()
                awaitState(DeviceAgentState.PAUSED)
                DeviceAgentController.stop()
                assertEquals(DeviceAgentState.IDLE, DeviceAgentController.state.value)
                delay(PARKED_OBSERVE_MS)
                assertEquals(DeviceAgentState.IDLE, DeviceAgentController.state.value)
            }
        }
        assertFalse(DeviceAgentController.isPaused)
    }

    @Test
    @DisplayName("pause is idempotent and ignored when idle")
    fun `pause idempotent when idle`() {
        DeviceAgentController.requestPause()
        assertEquals(DeviceAgentState.IDLE, DeviceAgentController.state.value)

        useGatedActor()
        script = listOf(DeviceAction.Tap(1, 1), DeviceAction.Finish("done"))
        runBlocking {
            withTimeout(TEST_TIMEOUT_MS) {
                DeviceAgentController.start("goal", maxSteps = 50, scope = this)
                awaitCount(actCount, 1)
                DeviceAgentController.requestPause()
                DeviceAgentController.requestPause()
                awaitState(DeviceAgentState.PAUSED)
                DeviceAgentController.resume()
                DeviceAgentController.resume()
                awaitState(DeviceAgentState.ACTIVE)
                releaseStep()
                awaitState(DeviceAgentState.DONE)
            }
        }
    }

    private suspend fun awaitCount(
        counter: AtomicInteger,
        expected: Int,
    ) {
        val deadline = System.currentTimeMillis() + STATE_TIMEOUT_MS
        while (counter.get() < expected) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "Timed out waiting for count $expected, was ${counter.get()}",
                )
            }
            delay(POLL_MS)
        }
    }

    private suspend fun awaitState(expected: DeviceAgentState) {
        val deadline = System.currentTimeMillis() + STATE_TIMEOUT_MS
        while (DeviceAgentController.state.value != expected) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "Timed out waiting for $expected, was ${DeviceAgentController.state.value}",
                )
            }
            delay(POLL_MS)
        }
    }

    private suspend fun awaitSteps(steps: Int) {
        val deadline = System.currentTimeMillis() + STATE_TIMEOUT_MS
        while (DeviceAgentController.stepsExecuted < steps) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "Timed out waiting for $steps steps, " +
                        "was ${DeviceAgentController.stepsExecuted}",
                )
            }
            delay(POLL_MS)
        }
    }

    companion object {
        /** Per-test wall-clock budget in milliseconds. */
        private const val TEST_TIMEOUT_MS = 15_000L

        /** State-wait budget in milliseconds. */
        private const val STATE_TIMEOUT_MS = 8_000L

        /** State poll interval in milliseconds. */
        private const val POLL_MS = 25L

        /** Observation window proving a parked loop makes no progress. */
        private const val PARKED_OBSERVE_MS = 400L
    }
}
