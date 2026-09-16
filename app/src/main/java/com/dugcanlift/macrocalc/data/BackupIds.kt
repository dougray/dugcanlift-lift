package com.dugcanlift.macrocalc.data

/**
 * How a restore decides whether it already has a record.
 *
 * Case-insensitive, because iOS writes UUIDs in upper case and the browser
 * generates them in lower case, so one record reaches this app spelled both
 * ways. Comparing exactly duplicated every food entry, workout and routine on a
 * round trip through an iPhone. See coach/BACKUP-FORMAT.md, "Restoring".
 */
internal fun backupIdKey(id: String): String = id.lowercase()
