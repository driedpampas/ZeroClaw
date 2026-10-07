/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.device

import java.security.MessageDigest

/**
 * Converts a [UiNode] tree into a compact LLM prompt representation.
 *
 * Kotlin port of DroidClaw's `AccessibilityNodeSerializer` logic:
 * only semantically useful fields are kept, depth is capped to bound
 * token usage, and empty/layout-only nodes are skipped. The output
 * is a flat indexed list (`[0] Button "OK" …`) so the model can
 * reference coordinates via `centerX/centerY`.
 *
 * The serializer never logs; its output goes to the LLM prompt only.
 */
object UiTreeSerializer {
    /** Recommended maximum recursion depth (matches DroidClaw). */
    const val DEFAULT_MAX_DEPTH = 6

    /** Hard cap on emitted nodes to bound prompt tokens. */
    const val MAX_NODES = 200

    /** Per-field truncation to avoid one node dominating the prompt. */
    const val MAX_TEXT_LENGTH = 80

    /**
     * Serializes [roots] into the compact indexed format.
     *
     * @param roots Top-level nodes (usually a single window root).
     * @param packageName Foreground package, included as a header line.
     * @param maxDepth Maximum recursion depth.
     * @return Compact multi-line representation for the LLM.
     */
    fun serialize(
        roots: List<UiNode>,
        packageName: String = "unknown",
        maxDepth: Int = DEFAULT_MAX_DEPTH,
    ): String {
        val lines = ArrayList<String>(MAX_NODES + 1)
        lines.add("package=$packageName")
        var index = 0
        for (root in roots) {
            index = appendNode(root, lines, index, 0, maxDepth)
            if (lines.size >= MAX_NODES + 1) break
        }
        return lines.joinToString("\n")
    }

    /**
     * Stable hash of a serialized tree for same-screen (stuck) detection.
     *
     * Normalizes whitespace and hashes with SHA-256 so identical screens
     * produce identical hashes across loop iterations.
     *
     * @param serialized Output of [serialize].
     * @return Lowercase hex digest.
     */
    fun stableHash(serialized: String): String {
        val normalized = serialized.trim().replace(Regex("\\s+"), " ")
        val digest = MessageDigest.getInstance("SHA-256")
        return digest
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun appendNode(
        node: UiNode,
        lines: MutableList<String>,
        index: Int,
        depth: Int,
        maxDepth: Int,
    ): Int {
        if (lines.size >= MAX_NODES + 1) return index
        var next = index
        if (isUseful(node)) {
            lines.add(formatNode(next, node, depth))
            next++
        }
        if (depth < maxDepth) {
            for (child in node.children) {
                next = appendNode(child, lines, next, depth + 1, maxDepth)
                if (lines.size >= MAX_NODES + 1) break
            }
        }
        return next
    }

    private fun isUseful(node: UiNode): Boolean =
        node.text != null ||
            node.contentDescription != null ||
            node.resourceId != null ||
            node.clickable ||
            node.scrollable ||
            node.editable

    private fun formatNode(
        index: Int,
        node: UiNode,
        depth: Int,
    ): String =
        buildString {
            append("[$index] ")
            append(node.className ?: "Node")
            node.text?.takeIf { it.isNotBlank() }?.let {
                append(" \"${it.take(MAX_TEXT_LENGTH)}\"")
            }
            node.contentDescription?.takeIf { it.isNotBlank() }?.let {
                append(" desc=\"${it.take(MAX_TEXT_LENGTH)}\"")
            }
            node.resourceId?.takeIf { it.isNotBlank() }?.let {
                append(" id=$it")
            }
            node.hint?.takeIf { it.isNotBlank() }?.let {
                append(" hint=\"${it.take(MAX_TEXT_LENGTH)}\"")
            }
            val flags = mutableListOf<String>()
            if (node.clickable) flags.add("clickable")
            if (node.scrollable) flags.add("scrollable")
            if (node.editable) flags.add("editable")
            if (!node.enabled) flags.add("disabled")
            if (flags.isNotEmpty()) append(" ${flags.joinToString(",")}")
            append(" bounds=[${node.centerX},${node.centerY}]")
            if (depth > 0) append(" d=$depth")
        }
}
