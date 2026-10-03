package com.nlphotos.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val PREFS_NAME = "recent_searches"
private const val MAX_RECENT_SEARCHES = 10

class RecentSearchStore private constructor(
    private val prefs: SharedPreferences,
) {

    companion object {
        fun create(context: Context): RecentSearchStore {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return RecentSearchStore(prefs)
        }
    }

    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches = _recentSearches.asStateFlow()

    init {
        load()
    }

    private fun load() {
        val list = mutableListOf<String>()
        for (i in 0 until MAX_RECENT_SEARCHES) {
            prefs.getString("search_$i", null)?.let { list.add(it) }
        }
        _recentSearches.value = list
    }

    suspend fun addSearch(query: String) = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext

        val list = _recentSearches.value.toMutableList()
        list.remove(query)
        list.add(0, query)
        if (list.size > MAX_RECENT_SEARCHES) {
            list.removeAt(list.lastIndex)
        }

        val editor = prefs.edit()
        editor.clear()
        list.forEachIndexed { idx, q ->
            editor.putString("search_$idx", q)
        }
        editor.apply()

        _recentSearches.value = list
    }

    suspend fun removeSearch(query: String) = withContext(Dispatchers.IO) {
        val list = _recentSearches.value.filterNot { it == query }
        val editor = prefs.edit()
        editor.clear()
        list.forEachIndexed { idx, q ->
            editor.putString("search_$idx", q)
        }
        editor.apply()

        _recentSearches.value = list
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
        _recentSearches.value = emptyList()
    }
}