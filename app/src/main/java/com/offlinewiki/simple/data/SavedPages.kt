package com.offlinewiki.simple.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

data class SavedPage(val path: String, val title: String, val time: Long)

/** Reading history and bookmarks, kept in two small JSON files in the app's private storage. */
class SavedPages(dir: File) {
    private val historyFile = File(dir, "history.json")
    private val bookmarksFile = File(dir, "bookmarks.json")
    private val io = Executors.newSingleThreadExecutor()

    private val _history = MutableStateFlow(load(historyFile))
    val history: StateFlow<List<SavedPage>> = _history

    private val _bookmarks = MutableStateFlow(load(bookmarksFile))
    val bookmarks: StateFlow<List<SavedPage>> = _bookmarks

    /** Puts the page at the top of the history, removing any older visit to it. */
    fun recordVisit(path: String, title: String) {
        if (_history.value.firstOrNull()?.path == path) return
        val page = SavedPage(path, title, System.currentTimeMillis())
        _history.value = (listOf(page) + _history.value.filter { it.path != path }).take(MAX_HISTORY)
        save(historyFile, _history.value)
    }

    fun clearHistory() {
        _history.value = emptyList()
        save(historyFile, emptyList())
    }

    fun isBookmarked(path: String): Boolean = _bookmarks.value.any { it.path == path }

    fun toggleBookmark(path: String, title: String) {
        _bookmarks.value = if (isBookmarked(path)) {
            _bookmarks.value.filter { it.path != path }
        } else {
            listOf(SavedPage(path, title, System.currentTimeMillis())) + _bookmarks.value
        }
        save(bookmarksFile, _bookmarks.value)
    }

    private fun load(file: File): List<SavedPage> = try {
        val array = JSONArray(file.readText())
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            SavedPage(o.getString("path"), o.getString("title"), o.optLong("time"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(file: File, pages: List<SavedPage>) {
        io.execute {
            val array = JSONArray()
            pages.forEach { array.put(JSONObject().put("path", it.path).put("title", it.title).put("time", it.time)) }
            runCatching { file.writeText(array.toString()) }
        }
    }

    private companion object {
        const val MAX_HISTORY = 300
    }
}
