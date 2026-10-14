package com.offlinewiki.simple.zim

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kiwix.libkiwix.JNIKiwix
import org.kiwix.libzim.Archive
import org.kiwix.libzim.Query
import org.kiwix.libzim.Searcher
import org.kiwix.libzim.SuggestionSearcher
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class ZimInfo(
    val file: File,
    val title: String,
    val date: String,
    val articleCount: Int,
    val mediaCount: Int,
    val hasFulltextIndex: Boolean,
    val hasTitleIndex: Boolean,
)

/** One entry read from the archive. [redirectTo] is set when [path] is a redirect to another entry. */
class ZimContent(val path: String, val mimeType: String, val data: ByteArray, val redirectTo: String?)

/** A search result. [snippet] may contain <b> tags around matched words. */
data class SearchHit(val path: String, val title: String, val snippet: String?)

/** Owns the open ZIM archive. Articles are read and decompressed on demand by libzim. */
class ZimRepository(private val context: Context) {
    // Constructing JNIKiwix loads the native libraries; it must happen before any libzim call.
    private val native by lazy { JNIKiwix(context.applicationContext) }

    // libzim reads are thread-safe; the write lock only guards opening and closing.
    private val lock = ReentrantReadWriteLock()
    private var archive: Archive? = null

    // Searchers are created on first use and are not used from two threads at once.
    private val searchLock = Any()
    private var suggester: SuggestionSearcher? = null
    private var searcher: Searcher? = null

    suspend fun open(file: File): ZimInfo = withContext(Dispatchers.IO) {
        native
        lock.write {
            disposeAll()
            val opened = Archive(file.absolutePath)
            archive = opened
            ZimInfo(
                file = file,
                title = metadata(opened, "Title") ?: file.nameWithoutExtension,
                date = metadata(opened, "Date").orEmpty(),
                articleCount = opened.articleCount,
                mediaCount = opened.mediaCount,
                hasFulltextIndex = opened.hasFulltextIndex(),
                hasTitleIndex = opened.hasTitleIndex(),
            )
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        lock.write { disposeAll() }
    }

    private fun disposeAll() {
        suggester?.dispose()
        suggester = null
        searcher?.dispose()
        searcher = null
        archive?.dispose()
        archive = null
    }

    /** Title suggestions for what the user has typed so far. Fast enough to run on every keystroke. */
    fun suggest(query: String, limit: Int = 12): List<SearchHit> = lock.read {
        val archive = archive ?: return emptyList()
        synchronized(searchLock) {
            try {
                val s = suggester ?: SuggestionSearcher(archive).also { suggester = it }
                val search = s.suggest(query)
                val results = search.getResults(0, limit)
                val hits = ArrayList<SearchHit>()
                while (results.hasNext()) {
                    val item = results.next()
                    hits += SearchHit(item.path, item.title, null)
                }
                results.dispose()
                search.dispose()
                hits
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** Full-text search over article bodies using the Xapian index inside the ZIM file. */
    fun search(query: String, limit: Int = 20, withSnippets: Boolean = true): List<SearchHit> = lock.read {
        val archive = archive ?: return emptyList()
        if (!archive.hasFulltextIndex()) return emptyList()
        synchronized(searchLock) {
            try {
                val s = searcher ?: Searcher(archive).also { searcher = it }
                val q = Query(query)
                val search = s.search(q)
                val results = search.getResults(0, limit)
                val hits = ArrayList<SearchHit>()
                while (results.hasNext()) {
                    // The getters describe the current result; next() moves on to the following one.
                    hits += SearchHit(results.path, results.title, if (withSnippets) results.snippet else null)
                    results.next()
                }
                results.dispose()
                search.dispose()
                q.dispose()
                hits
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** Reads and decompresses one entry in memory. Returns null if there is no such entry. */
    fun read(path: String): ZimContent? = lock.read {
        val archive = archive ?: return null
        try {
            val entry = archive.getEntryByPath(path)
            val item = entry.getItem(true)
            ZimContent(
                path = path,
                mimeType = item.mimetype,
                data = item.data.data,
                redirectTo = if (entry.isRedirect) item.path else null,
            )
        } catch (e: Exception) {
            null
        }
    }

    fun mainPagePath(): String? = lock.read {
        runCatching { archive?.mainEntry?.getItem(true)?.path }.getOrNull()
    }

    fun randomPath(): String? = lock.read {
        runCatching { archive?.randomEntry?.getItem(true)?.path }.getOrNull()
    }

    private fun metadata(archive: Archive, key: String): String? =
        runCatching { archive.getMetadata(key) }.getOrNull()?.takeIf { it.isNotBlank() }
}
