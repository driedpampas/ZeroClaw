/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

/**
 * Platform-agnostic UI node for the compact screen representation.
 *
 * Mirrors the fields DroidClaw's `AccessibilityNodeSerializer` keeps
 * (class, resource ID, text, content description, interactability,
 * bounds) but as a plain data class so serialization, truncation,
 * and hashing are unit-testable without Android.
 *
 * @property className Short class name (e.g. `Button`).
 * @property resourceId Android view resource ID, if any.
 * @property text Visible text, if any.
 * @property contentDescription Accessibility label, if any.
 * @property hint Hint text for input fields, if any.
 * @property clickable Whether the node is clickable.
 * @property scrollable Whether the node is scrollable.
 * @property editable Whether the node is an editable field.
 * @property enabled Whether the node is enabled.
 * @property left Left bound in screen pixels.
 * @property top Top bound in screen pixels.
 * @property right Right bound in screen pixels.
 * @property bottom Bottom bound in screen pixels.
 * @property children Child nodes.
 */
data class UiNode(
    val className: String? = null,
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val enabled: Boolean = false,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val children: List<UiNode> = emptyList(),
) {
    /** Horizontal center derived from bounds. */
    val centerX: Int get() = (left + right) / 2

    /** Vertical center derived from bounds. */
    val centerY: Int get() = (top + bottom) / 2
}
