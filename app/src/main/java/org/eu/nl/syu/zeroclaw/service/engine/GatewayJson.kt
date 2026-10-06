/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.service.engine

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Defensive accessors for gateway JSON.
 *
 * The engine's gateway responses are the contract, but upstream has historically
 * renamed individual fields between releases. These helpers accept multiple
 * candidate key spellings so a rename degrades to a missing value rather than a
 * crash.
 */
internal fun JSONObject.string(vararg keys: String): String? {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val value = opt(key)
        if (value != null && value != JSONObject.NULL) return value.toString()
    }
    return null
}

internal fun JSONObject.stringOrEmpty(vararg keys: String): String = string(*keys).orEmpty()

internal fun JSONObject.long(vararg keys: String): Long? {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        when (val value = opt(key)) {
            is Number -> return value.toLong()
            is String -> value.toLongOrNull()?.let { return it }
        }
    }
    return null
}

internal fun JSONObject.int(vararg keys: String): Int? = long(*keys)?.toInt()

internal fun JSONObject.bool(vararg keys: String): Boolean? {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        when (val value = opt(key)) {
            is Boolean -> return value
            is String -> return value.toBooleanStrictOrNull()
            is Number -> return value.toInt() != 0
        }
    }
    return null
}

internal fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)

internal fun JSONObject.arr(key: String): JSONArray? = optJSONArray(key)

/** Materialises a [JSONArray] of objects. */
internal fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { idx -> optJSONObject(idx) }

/**
 * Wraps a bare array response (`[ ... ]`) as `{ "<key>": [ ... ] }` when the
 * gateway returns either shape.
 */
internal fun JSONObject.listAt(key: String): List<JSONObject> {
    val array = optJSONArray(key)
    if (array != null) return array.objects()
    return emptyList()
}

/** Parses an RFC 3339 timestamp into epoch milliseconds, or null. */
internal fun rfc3339ToEpochMs(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        Instant.parse(value).toEpochMilli()
    } catch (@Suppress("SwallowedException") e: Exception) {
        null
    }
}
