package com.burnto.disk.data

import android.content.Context
import android.content.SharedPreferences
import com.burnto.disk.data.model.RecentIso
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the last few ISOs used, backed by SharedPreferences as specified.
 * Stores a small JSON array; capped at [MAX_RECENT] entries, most-recent first.
 */
@Singleton
class RecentIsoStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "burn2disk_recent"
        private const val KEY_RECENT = "recent_isos"
        const val MAX_RECENT = 5
    }

    fun getRecent(): List<RecentIso> {
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentIso(
                    name = o.getString("name"),
                    path = o.getString("path"),
                    sizeBytes = o.getLong("size"),
                    lastUsedEpochMs = o.getLong("lastUsed")
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Adds or refreshes [iso] at the head of the list, de-duplicating by path. */
    fun addRecent(iso: RecentIso) {
        val current = getRecent().filter { it.path != iso.path }
        val updated = (listOf(iso) + current).take(MAX_RECENT)
        val arr = JSONArray()
        updated.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("name", r.name)
                    put("path", r.path)
                    put("size", r.sizeBytes)
                    put("lastUsed", r.lastUsedEpochMs)
                }
            )
        }
        prefs.edit().putString(KEY_RECENT, arr.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_RECENT).apply()
    }
}
