package com.dugcanlift.macrocalc.data

import org.json.JSONObject

/*
 * Optional numbers from a stored record or a backup file.
 *
 * `JSONObject` has no nullable getters, and its fallbacks hide two traps.
 * `has(key)` is true for `"amountGrams": null` -- a shape a hand-built or
 * third-party backup can carry, even though LIFT web omits the key -- and
 * `optDouble(key)` then returns NaN. And `optDouble` coerces a string,
 * so `"NaN"` or `"Infinity"` in a file becomes a non-finite Double. Either one is
 * stored quietly and then throws "Forbidden numeric value" the next time the
 * record is written, which is the write straight after a restore.
 *
 * BACKUP-FORMAT's rule is that unknown stays null, never zero. So absent, `null`
 * and any non-finite number all read as absent: null for an optional field, the
 * caller's default for a required one.
 */

/** A finite number, or null when the key is absent, `null`, or not finite. */
internal fun JSONObject.finiteDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return optDouble(key).takeIf { it.isFinite() }
}

/** A finite number, or [fallback] when the key is absent, `null`, or not finite. */
internal fun JSONObject.finiteDouble(key: String, fallback: Double): Double =
    finiteDoubleOrNull(key) ?: fallback

/** A whole number, or null when the key is absent, `null`, or not a finite number. */
internal fun JSONObject.finiteIntOrNull(key: String): Int? =
    if (finiteDoubleOrNull(key) == null) null else optInt(key)

internal fun JSONObject.finiteInt(key: String, fallback: Int): Int =
    finiteIntOrNull(key) ?: fallback

/** As [finiteIntOrNull], for epoch milliseconds. */
internal fun JSONObject.finiteLongOrNull(key: String): Long? =
    if (finiteDoubleOrNull(key) == null) null else optLong(key)

internal fun JSONObject.finiteLong(key: String, fallback: Long): Long =
    finiteLongOrNull(key) ?: fallback
