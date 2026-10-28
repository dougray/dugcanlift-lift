package com.dugcanlift.macrocalc.data

import android.content.SharedPreferences
import java.util.Locale

/**
 * The equipment someone has, which narrows exercise search to what they can actually do. Kept as
 * what they do *not* have, so nothing set is everything owned and an untouched phone hides nothing.
 *
 * An exercise whose equipment is not listed is never hidden: unknown is not "needs something you
 * lack", and hiding it would be a claim nobody made.
 *
 * A port of LIFT iPhone's `MyEquipment`, function for function. On this phone only, like
 * [SettingsStore.roadFoodRecent]: not in the backup, as the iPhone keeps it out of its own.
 */
object MyEquipment {
    const val STORAGE_KEY = "equipment_not_owned"

    data class Kind(
        /** The value as `exercises.json` spells it ([LibraryExercise.equipment]). */
        val id: String,
        val displayName: String
    )

    /**
     * Every equipment value in the bundled exercise data, in the order a gym is usually described.
     * `MyEquipmentTest` checks the data has no value missing from this list.
     */
    val kinds: List<Kind> = listOf(
        Kind("barbell", "Barbell"),
        Kind("e-z curl bar", "EZ bar"),
        Kind("dumbbell", "Dumbbells"),
        Kind("kettlebells", "Kettlebells"),
        Kind("cable", "Cable"),
        Kind("machine", "Machines"),
        Kind("bands", "Bands"),
        Kind("medicine ball", "Medicine ball"),
        Kind("exercise ball", "Exercise ball"),
        Kind("foam roll", "Foam roller"),
        Kind("body only", "Bodyweight"),
        Kind("other", "Other"),
    )

    fun normalised(equipment: String?): String = (equipment ?: "").trim().lowercase(Locale.ROOT)

    fun allows(equipment: String?, notOwned: Set<String>): Boolean {
        val value = normalised(equipment)
        return value.isEmpty() || value !in notOwned
    }

    /** What to show, and how many were left out, so search can say so. */
    data class Filtered<T>(val shown: List<T>, val leftOut: Int)

    fun <T> filter(items: List<T>, notOwned: Set<String>, equipment: (T) -> String?): Filtered<T> {
        if (notOwned.isEmpty()) return Filtered(items, 0)
        val shown = items.filter { allows(equipment(it), notOwned) }
        return Filtered(shown, items.size - shown.size)
    }

    fun load(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(STORAGE_KEY, null)?.toSet() ?: emptySet()

    /** Owning everything removes the key, rather than storing an empty list. */
    fun save(notOwned: Set<String>, prefs: SharedPreferences) {
        prefs.edit().apply {
            if (notOwned.isEmpty()) remove(STORAGE_KEY) else putStringSet(STORAGE_KEY, notOwned.toSet())
        }.apply()
    }

    /** Search's own line: "1 exercise needs equipment you don't have." */
    fun leftOutText(leftOut: Int): String =
        if (leftOut == 1) "1 exercise needs equipment you don't have."
        else "$leftOut exercises need equipment you don't have."

    /** The line above the results: narrowed, and by how much, or not narrowed at all. */
    fun statusText(filtering: Boolean, leftOut: Int): String = when {
        !filtering -> "All equipment"
        leftOut > 0 -> "Your equipment · $leftOut left out"
        else -> "Your equipment"
    }

    const val SETTINGS_FOOTER =
        "Exercise search shows what you can do with what's switched on, and says how many it left out. " +
            "An exercise with no equipment listed always shows."
}
