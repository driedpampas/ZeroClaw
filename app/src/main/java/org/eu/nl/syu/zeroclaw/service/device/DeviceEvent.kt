/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

/**
 * Structured agent-loop events for logging and UI.
 *
 * Events carry only counts, step numbers, and reason codes — never
 * screen contents, typed text, node labels, or resource IDs — so
 * they are safe to write to logcat, the Room log store, and analytics.
 */
sealed interface DeviceEvent {
    /** Stable machine-readable event token. */
    val name: String

    /**
     * Task started with a step budget.
     *
     * @property maxSteps Step budget before the stuck detector fires.
     */
    data class TaskStarted(
        val maxSteps: Int,
    ) : DeviceEvent {
        override val name: String = "TASK_STARTED"
    }

    /**
     * One Act step dispatched.
     *
     * @property actionName A [DeviceAction.eventName] (type only).
     * @property step Zero-based loop iteration.
     */
    data class ActionDispatched(
        val actionName: String,
        val step: Int,
    ) : DeviceEvent {
        override val name: String = actionName
    }

    /** Loop paused; [reason] is `user_touch` or `explicit`. */
    data class TaskPaused(
        val reason: String,
    ) : DeviceEvent {
        override val name: String = "TASK_PAUSED"
    }

    /** Loop resumed after a pause. */
    data object TaskResumed : DeviceEvent {
        override val name: String = "TASK_RESUMED"
    }

    /** Task completed normally after [steps] steps. */
    data class TaskCompleted(
        val steps: Int,
    ) : DeviceEvent {
        override val name: String = "TASK_COMPLETED"
    }

    /** Stuck detector fired after [steps] steps. */
    data class TaskStuck(
        val steps: Int,
    ) : DeviceEvent {
        override val name: String = "TASK_STUCK"
    }

    /** Task stopped by the user after [steps] steps. */
    data class TaskStopped(
        val steps: Int,
    ) : DeviceEvent {
        override val name: String = "TASK_STOPPED"
    }

    /** Unrecoverable error; [detail] is sanitized, never screen content. */
    data class TaskError(
        val detail: String,
    ) : DeviceEvent {
        override val name: String = "TASK_ERROR"
    }
}
