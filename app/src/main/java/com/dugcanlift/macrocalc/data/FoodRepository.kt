package com.dugcanlift.macrocalc.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * Stores the food log as a single JSON array in the app's private files
 * directory. Nothing leaves the device.
 *
 * Deliberately simple: no database, no annotation processing. Callers only
 * ever touch this class, so swapping in Room later is a contained change.
 */
class FoodRepository private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    private val _entries = MutableStateFlow<List<FoodEntry>>(emptyList())
    val entries: StateFlow<List<FoodEntry>> = _entries.asStateFlow()

    /** Call once on startup. Safe to call again. */
    suspend fun load() = withContext(Dispatchers.IO) {
        _entries.value = read()
    }

    suspend fun add(entry: FoodEntry) = withContext(Dispatchers.IO) {
        val updated = read() + entry
        write(updated)
        _entries.value = updated
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val updated = read().filterNot { it.id == id }
        write(updated)
        _entries.value = updated
    }

    suspend fun update(entry: FoodEntry) = withContext(Dispatchers.IO) {
        val updated = read().map { if (it.id == entry.id) entry else it }
        write(updated)
        _entries.value = updated
    }

    /** Wipes the log. Used by a "clear my data" action. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        write(emptyList())
        _entries.value = emptyList()
    }

    /* ---------- file access ---------- */

    @Synchronized
    private fun read(): List<FoodEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { foodEntryFromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            // A corrupt or truncated file shouldn't crash the app on launch.
            // Better to start empty than to die at startup.
            emptyList()
        }
    }

    @Synchronized
    private fun write(entries: List<FoodEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        // Write to a temp file then rename, so an interrupted write can't
        // leave a half-written log behind.
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(array.toString())
        temp.renameTo(file)
    }

    companion object {
        private const val FILE_NAME = "food_log.json"

        @Volatile
        private var instance: FoodRepository? = null

        fun get(context: Context): FoodRepository =
            instance ?: synchronized(this) {
                instance ?: FoodRepository(context).also { instance = it }
            }
    }
}

/** Entries for one day, newest first. */
fun List<FoodEntry>.forDate(date: String): List<FoodEntry> =
    filter { it.date == date }.sortedByDescending { it.loggedAt }
