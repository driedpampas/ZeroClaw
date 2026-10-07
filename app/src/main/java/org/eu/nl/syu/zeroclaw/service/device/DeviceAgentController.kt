/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Compact screen snapshot for one Perceive step.
 *
 * @property serialized Compact UI-tree text for the LLM (never logged).
 * @property hash Stable hash for same-screen stuck detection.
 */
data class UiSnapshot(
    val serialized: String,
    val hash: String,
)

/**
 * Perceive → Reason → Act loop for on-device agent tasks.
 *
 * Ports DroidClaw's agent-loop control (max-iteration cap, cancellation
 * checkpoints, sequential tool execution) into ZeroClaw's architecture:
 *
 * - Perceive via [DeviceControlBridge] UI-tree reads.
 * - Reason via the injected [DeviceReasoner] (default: [GatewayDeviceReasoner]
 *   over ZeroClaw's existing LLM provider layer).
 * - Act via [DeviceControlBridge] gesture dispatch.
 *
 * Pause handling: [requestPause] flips a `@Volatile` flag checked at the
 * top of every iteration and immediately before gesture dispatch. While
 * paused the loop blocks on a [CountDownLatch] (no busy spin, no step
 * consumption). [resume] releases the latch; the next iteration re-reads
 * the screen, so user interaction during the pause is picked up.
 *
 * Stuck protection: [DEFAULT_MAX_STEPS] caps total iterations and a
 * same-screen detector stops the loop when the screen hash is unchanged
 * for [SAME_SCREEN_THRESHOLD] consecutive perceives. Both stay armed
 * across pause/resume because counters are only mutated on real steps.
 *
 * Only structured [DeviceEvent]s (no screen contents, no typed text) are
 * emitted on [events] and logged.
 */
@Suppress("TooManyFunctions")
object DeviceAgentController {
    private const val TAG = "DeviceAgentController"

    /** Default step budget, matching DroidClaw's max-iteration default. */
    const val DEFAULT_MAX_STEPS = 20

    /** Consecutive identical screens before the task is declared stuck. */
    const val SAME_SCREEN_THRESHOLD = 3

    /** Pause-latch poll quantum while waiting for resume/stop. */
    private const val PAUSE_AWAIT_QUANTUM_MS = 200L

    /** Pause reason when the user touches the screen. */
    const val REASON_USER_TOUCH = "user_touch"

    /** Pause reason for explicit pause requests. */
    const val REASON_EXPLICIT = "explicit"

    private val _state = MutableStateFlow(DeviceAgentState.IDLE)
    private val _events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 32)

    /** Observable agent lifecycle state. */
    val state: StateFlow<DeviceAgentState> = _state.asStateFlow()

    /** Structured loop events (safe to persist; no screen contents). */
    val events: SharedFlow<DeviceEvent> = _events.asSharedFlow()

    /** Set while the loop must stay parked. Checked every iteration. */
    @Volatile
    var isPaused: Boolean = false
        private set

    /** Steps executed in the current task. */
    @Volatile
    var stepsExecuted: Int = 0
        private set

    private val pauseLock = Any()

    @Volatile
    private var pauseLatch: CountDownLatch? = null

    @Volatile
    private var pauseReason: String = REASON_EXPLICIT

    @Volatile
    private var stopRequested: Boolean = false

    @Volatile
    private var loopJob: Job? = null

    private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Perceive hook; defaults to a live bridge read. */
    @Volatile
    var perceiver: suspend () -> UiSnapshot = { livePerceive() }

    /** Act hook; defaults to live gesture dispatch. */
    @Volatile
    var actor: suspend (DeviceAction) -> Boolean = { liveAct(it) }

    /** Reason hook; replaced by tests with scripted actions. */
    @Volatile
    var reasoner: DeviceReasoner = DeviceReasoner { _, _, _ -> DeviceAction.NoOp }

    /**
     * Starts a task in the background.
     *
     * No-op when a task is already [DeviceAgentState.ACTIVE] or
     * [DeviceAgentState.PAUSED]. Resets pause state, step counter, and
     * stuck-detector state before launching.
     *
     * @param goal Task description for the Reason step.
     * @param maxSteps Step budget before the stuck detector fires.
     * @param scope Scope hosting the loop (defaults to an internal scope).
     */
    fun start(
        goal: String,
        maxSteps: Int = DEFAULT_MAX_STEPS,
        scope: CoroutineScope = internalScope,
    ) {
        synchronized(pauseLock) {
            val current = _state.value
            if (current == DeviceAgentState.ACTIVE || current == DeviceAgentState.PAUSED) {
                Log.i(TAG, "start ignored: already $current")
                return
            }
            isPaused = false
            stopRequested = false
            stepsExecuted = 0
            pauseLatch = null
            _state.value = DeviceAgentState.ACTIVE
        }
        emit(DeviceEvent.TaskStarted(maxSteps.coerceAtLeast(1)))
        Log.i(TAG, "TASK_STARTED")
        loopJob?.cancel()
        loopJob =
            scope.launch(Dispatchers.IO) {
                runLoop(goal, maxSteps.coerceAtLeast(1))
            }
    }

    /**
     * Requests a pause; the loop parks within one iteration.
     *
     * Idempotent: repeated calls while paused are no-ops. Does not
     * consume steps or disturb the stuck-detector counters.
     *
     * @param reason [REASON_USER_TOUCH] or [REASON_EXPLICIT].
     */
    fun requestPause(reason: String = REASON_EXPLICIT) {
        synchronized(pauseLock) {
            if (_state.value != DeviceAgentState.ACTIVE) return
            if (isPaused) return
            isPaused = true
            pauseReason = reason
            pauseLatch = CountDownLatch(1)
            _state.value = DeviceAgentState.PAUSED
        }
        emit(DeviceEvent.TaskPaused(reason))
        Log.i(TAG, "TASK_PAUSED reason=$reason")
    }

    /**
     * Resumes a paused task.
     *
     * Releases the pause latch; the loop's next iteration re-reads the
     * screen ([perceiver]) before reasoning, so changes the user made
     * while paused are observed. No-op unless currently paused.
     */
    fun resume() {
        synchronized(pauseLock) {
            if (_state.value != DeviceAgentState.PAUSED) return
            isPaused = false
            _state.value = DeviceAgentState.ACTIVE
            pauseLatch?.countDown()
            pauseLatch = null
        }
        emit(DeviceEvent.TaskResumed)
        Log.i(TAG, "TASK_RESUMED")
    }

    /**
     * Stops the current task and returns to [DeviceAgentState.IDLE].
     *
     * Releases a held pause latch so a parked loop cannot linger or
     * spin forever.
     */
    fun stop() {
        val steps = stepsExecuted
        synchronized(pauseLock) {
            stopRequested = true
            isPaused = false
            pauseLatch?.countDown()
            pauseLatch = null
            loopJob?.cancel()
            loopJob = null
            if (_state.value == DeviceAgentState.ACTIVE || _state.value == DeviceAgentState.PAUSED) {
                _state.value = DeviceAgentState.IDLE
            }
        }
        emit(DeviceEvent.TaskStopped(steps))
        Log.i(TAG, "TASK_STOPPED steps=$steps")
    }

    /**
     * Resets hooks and state for tests.
     *
     * Must only be called from unit tests when no task is running.
     */
    fun resetForTests() {
        synchronized(pauseLock) {
            loopJob?.cancel()
            loopJob = null
            isPaused = false
            stopRequested = false
            stepsExecuted = 0
            pauseLatch = null
            _state.value = DeviceAgentState.IDLE
            perceiver = { UiSnapshot("", "empty") }
            actor = { true }
            reasoner = DeviceReasoner { _, _, _ -> DeviceAction.NoOp }
        }
        // Drain stale events best-effort; replay cache is absent so
        // collectors only see events emitted after (re)subscription.
    }

    private suspend fun runLoop(
        goal: String,
        maxSteps: Int,
    ) {
        val tracker = SameScreenTracker()
        try {
            while (stepsExecuted < maxSteps && !stopRequested) {
                if (!runOneIteration(goal, tracker)) return
            }
            if (!stopRequested && _state.value == DeviceAgentState.ACTIVE) {
                markStuck()
            }
        } finally {
            synchronized(pauseLock) {
                loopJob = null
                if (_state.value == DeviceAgentState.ACTIVE && stopRequested) {
                    _state.value = DeviceAgentState.IDLE
                }
            }
        }
    }

    /**
     * Runs one Perceive → Reason → Act iteration.
     *
     * @return `false` when a terminal state was reached and the loop
     *   must stop; `true` to continue.
     */
    // Guard clauses keep each pause/stop checkpoint explicit; the
    // alternative (nested conditionals) hurts the pause audit.
    @Suppress("ReturnCount")
    private suspend fun runOneIteration(
        goal: String,
        tracker: SameScreenTracker,
    ): Boolean {
        awaitIfPaused()
        if (stopRequested) return false
        val snapshot = perceiveOrFail() ?: return false
        if (stopRequested) return false
        if (tracker.isStuck(snapshot.hash)) {
            markStuck()
            return false
        }
        val action = reasonOrNoOp(snapshot.serialized, goal)
        // Pause checkpoint before dispatching the next gesture: a gesture
        // already in flight completes, but no new gesture starts while
        // paused.
        awaitIfPaused()
        if (stopRequested) return false
        if (action is DeviceAction.Finish) {
            markDone()
            return false
        }
        dispatch(action)
        stepsExecuted++
        return true
    }

    /** Perceives the screen; reports failure and returns null on error. */
    // Hooks are injectable lambdas that may throw anything; the loop must
    // degrade to a structured TASK_ERROR, never crash (cancellation still rethrown).
    @Suppress("TooGenericExceptionCaught")
    private suspend fun perceiveOrFail(): UiSnapshot? =
        try {
            perceiver()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Perceive failed: ${e.javaClass.simpleName}")
            fail("PERCEIVE_FAILED")
            null
        }

    /** Reasons the next action; degrades to [DeviceAction.NoOp]. */
    // Model output handling must never throw; malformed responses become no-ops.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun reasonOrNoOp(
        screen: String,
        goal: String,
    ): DeviceAction =
        try {
            reasoner.reason(screen, goal, stepsExecuted)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DeviceAction.NoOp
        }

    /** Logs and dispatches one action; act errors never stop the loop. */
    // Injectable act hooks may throw anything; one failed gesture must not abort the task.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun dispatch(action: DeviceAction) {
        emit(DeviceEvent.ActionDispatched(action.eventName, stepsExecuted))
        Log.i(TAG, "${action.eventName} step=$stepsExecuted")
        if (DeviceCommand.fromAction(action) == null) return
        try {
            actor(action)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Act failed: ${e.javaClass.simpleName}")
        }
    }

    private fun markDone() {
        _state.value = DeviceAgentState.DONE
        emit(DeviceEvent.TaskCompleted(stepsExecuted))
        Log.i(TAG, "TASK_COMPLETED steps=$stepsExecuted")
    }

    private fun markStuck() {
        _state.value = DeviceAgentState.STUCK
        emit(DeviceEvent.TaskStuck(stepsExecuted))
        Log.i(TAG, "TASK_STUCK steps=$stepsExecuted")
    }

    /**
     * Same-screen stuck detector.
     *
     * Reports stuck when the screen hash is unchanged for
     * [SAME_SCREEN_THRESHOLD] consecutive perceives. Counters only
     * advance on real perceives, so pausing never disturbs detection.
     */
    private class SameScreenTracker {
        private var lastHash: String? = null
        private var sameCount = 0

        /** Records [hash]; returns true when the screen is stuck. */
        fun isStuck(hash: String): Boolean {
            if (hash == lastHash) {
                sameCount++
            } else {
                sameCount = 0
                lastHash = hash
            }
            return sameCount >= SAME_SCREEN_THRESHOLD
        }
    }

    private fun awaitIfPaused() {
        while (isPaused && !stopRequested) {
            val latch =
                synchronized(pauseLock) {
                    pauseLatch
                } ?: return
            try {
                latch.await(PAUSE_AWAIT_QUANTUM_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun fail(detail: String) {
        _state.value = DeviceAgentState.ERROR
        emit(DeviceEvent.TaskError(detail))
        Log.i(TAG, "TASK_ERROR detail=$detail")
    }

    private fun emit(event: DeviceEvent) {
        _events.tryEmit(event)
    }

    private fun livePerceive(): UiSnapshot {
        val result = DeviceControlBridge.execute(DeviceCommand.getUiTree())
        val payload = result.payload ?: ""
        return UiSnapshot(payload, UiTreeSerializer.stableHash(payload))
    }

    private fun liveAct(action: DeviceAction): Boolean {
        val command = DeviceCommand.fromAction(action) ?: return true
        return DeviceControlBridge.execute(command).success
    }
}
