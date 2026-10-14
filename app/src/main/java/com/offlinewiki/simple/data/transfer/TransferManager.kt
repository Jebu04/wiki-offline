package com.offlinewiki.simple.data.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.offlinewiki.simple.data.ContentKind
import com.offlinewiki.simple.data.ContentStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.Properties

/**
 * Runs one download or import at a time. Downloads write to a partial file
 * that survives interruptions and app restarts, and are resumed with an HTTP
 * Range request.
 */
class TransferManager(private val app: Context, private val store: ContentStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state

    private var job: Job? = null
    @Volatile private var connection: HttpURLConnection? = null

    init {
        // A partial download left by a previous run can be picked up again.
        for (kind in ContentKind.entries) {
            val pending = readPending(kind) ?: continue
            _state.value = TransferState.Paused(
                kind, store.partFile(kind).length(), pending.total, "The download was interrupted.",
            )
            break
        }
    }

    fun startDownload(source: DownloadSource) {
        store.discardPartial(source.kind)
        run(source.kind) { download(source.kind, Pending(source.id, null, null, null, -1)) }
    }

    fun resume() {
        val paused = _state.value as? TransferState.Paused ?: return
        val pending = readPending(paused.kind) ?: run {
            _state.value = TransferState.Idle
            return
        }
        run(paused.kind) { download(paused.kind, pending) }
    }

    fun startImport(kind: ContentKind, uri: Uri) {
        store.discardPartial(kind)
        run(kind) { import(kind, uri) }
    }

    /** Stops the transfer but keeps the partial file so it can be resumed. */
    fun pause() {
        val running = _state.value as? TransferState.Running ?: return
        val current = job ?: return
        scope.launch {
            connection?.disconnect()
            current.cancelAndJoin()
            _state.value = if (readPending(running.kind) != null) {
                TransferState.Paused(running.kind, store.partFile(running.kind).length(), running.totalBytes, "Paused.")
            } else {
                store.discardPartial(running.kind)
                TransferState.Idle
            }
        }
    }

    /** Stops any transfer and deletes the partial file. */
    fun discard() {
        val current = job
        scope.launch {
            connection?.disconnect()
            current?.cancelAndJoin()
            ContentKind.entries.forEach { store.discardPartial(it) }
            _state.value = TransferState.Idle
        }
    }

    fun acknowledge() {
        if (_state.value is TransferState.Done || _state.value is TransferState.Failed) {
            _state.value = TransferState.Idle
        }
    }

    private fun run(kind: ContentKind, block: suspend () -> File) {
        job?.cancel()
        _state.value = TransferState.Running(kind, Phase.CONNECTING, 0, -1)
        TransferService.start(app)
        job = scope.launch {
            try {
                _state.value = TransferState.Done(kind, block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransferException) {
                _state.value = if (e.resumable) {
                    val total = readPending(kind)?.total ?: -1
                    TransferState.Paused(kind, store.partFile(kind).length(), total, e.message ?: "Interrupted.")
                } else {
                    store.discardPartial(kind)
                    TransferState.Failed(kind, e.message ?: "Failed.")
                }
            } catch (e: Exception) {
                store.discardPartial(kind)
                _state.value = TransferState.Failed(kind, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ---- download ----

    private suspend fun download(kind: ContentKind, initial: Pending): File {
        var pending = initial
        val part = store.partFile(kind)

        if (pending.url == null) {
            val source = Sources.byId(pending.sourceId)
                ?: throw TransferException("Unknown download source.", resumable = false)
            val resolved = try {
                source.resolve()
            } catch (e: IOException) {
                throw TransferException("Could not reach the download server. Check your connection.", resumable = false)
            }
            pending = pending.copy(url = resolved.url, fileName = resolved.fileName, sha256Url = resolved.sha256Url)
            writePending(kind, pending)
        }
        val url = pending.url!!
        var total = pending.total
        var failures = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val existing = part.length()
            if (total > 0 && existing == total) break
            try {
                val conn = Http.open(url, existing)
                connection = conn
                try {
                    val append = when (val code = conn.responseCode) {
                        HttpURLConnection.HTTP_PARTIAL -> {
                            total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: total
                            true
                        }
                        HttpURLConnection.HTTP_OK -> {
                            total = conn.contentLengthLong
                            false
                        }
                        416 -> {
                            // The partial file does not match what the server has; start over.
                            part.delete()
                            continue
                        }
                        else -> throw TransferException("The server answered with HTTP $code.", resumable = code >= 500)
                    }
                    val have = if (append) existing else 0
                    if (total > 0 && total - have > store.usableBytes() - ContentStore.SPACE_MARGIN_BYTES) {
                        throw TransferException("Not enough free space on this device.", resumable = false)
                    }
                    if (total != pending.total) {
                        pending = pending.copy(total = total)
                        writePending(kind, pending)
                    }
                    conn.inputStream.use { input ->
                        FileOutputStream(part, append).use { out ->
                            copy(input, out) { written ->
                                failures = 0
                                _state.value = TransferState.Running(kind, Phase.DOWNLOADING, have + written, total)
                            }
                        }
                    }
                    if (total <= 0) break
                } finally {
                    connection = null
                    conn.disconnect()
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (++failures > MAX_RETRIES) {
                    throw TransferException("The connection was lost. You can resume when you are back online.", resumable = true)
                }
                delay(2_000L * failures)
            }
        }

        pending.sha256Url?.let { verifyChecksum(kind, part, it) }
        validate(kind, part)
        return store.commit(kind, pending.fileName ?: "content.${kind.extension}")
    }

    private suspend fun verifyChecksum(kind: ContentKind, part: File, sha256Url: String) {
        val expected = try {
            Http.readText(sha256Url, maxBytes = 4096).trim().substringBefore(' ').lowercase()
        } catch (e: IOException) {
            throw TransferException("Could not fetch the checksum to verify the download.", resumable = true)
        }
        if (!expected.matches(Regex("[0-9a-f]{64}"))) return
        val digest = MessageDigest.getInstance("SHA-256")
        val size = part.length()
        part.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            var done = 0L
            var lastReport = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                done += n
                val now = System.currentTimeMillis()
                if (now - lastReport > REPORT_MS) {
                    lastReport = now
                    _state.value = TransferState.Running(kind, Phase.VERIFYING, done, size)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            throw TransferException("The downloaded file is corrupted. Please download it again.", resumable = false)
        }
    }

    // ---- import ----

    private suspend fun import(kind: ContentKind, uri: Uri): File {
        val resolver = app.contentResolver
        var name = "imported.${kind.extension}"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        name = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        if (!kind.accepts(File(name))) name += ".${kind.extension}"

        if (size > store.usableBytes() - ContentStore.SPACE_MARGIN_BYTES) {
            throw TransferException("Not enough free space to copy this file into the app.", resumable = false)
        }
        val part = store.partFile(kind)
        val input = resolver.openInputStream(uri)
            ?: throw TransferException("Could not open the selected file.", resumable = false)
        input.use {
            FileOutputStream(part, false).use { out ->
                copy(it, out) { written ->
                    _state.value = TransferState.Running(kind, Phase.COPYING, written, size)
                }
            }
        }
        validate(kind, part)
        return store.commit(kind, name)
    }

    // ---- shared ----

    private suspend fun copy(input: java.io.InputStream, out: java.io.OutputStream, onProgress: (Long) -> Unit) {
        val buf = ByteArray(BUFFER)
        var written = 0L
        var lastReport = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            written += n
            val now = System.currentTimeMillis()
            if (now - lastReport > REPORT_MS) {
                lastReport = now
                onProgress(written)
            }
        }
        onProgress(written)
    }

    private fun validate(kind: ContentKind, file: File) {
        val head = ByteArray(8)
        val read = file.inputStream().use { it.read(head) }
        fun startsWith(bytes: ByteArray, offset: Int = 0) =
            read >= offset + bytes.size && bytes.indices.all { head[offset + it] == bytes[it] }
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        val ok = when (kind) {
            // ZIM files start with the little-endian magic number 0x044D495A.
            ContentKind.ZIM -> startsWith(byteArrayOf(0x5A, 0x49, 0x4D, 0x04))
            // .litertlm files start with "LITERTLM"; .task bundles are zip archives, sometimes after four zero bytes.
            ContentKind.MODEL -> startsWith("LITERTLM".toByteArray()) || startsWith(zip) || startsWith(zip, offset = 4)
        }
        if (!ok) {
            val message = if (kind == ContentKind.ZIM) "That file is not a ZIM file." else "That file is not a model file this app can use."
            throw TransferException(message, resumable = false)
        }
    }

    private data class Pending(
        val sourceId: String,
        val url: String?,
        val fileName: String?,
        val sha256Url: String?,
        val total: Long,
    )

    private fun readPending(kind: ContentKind): Pending? {
        val file = store.pendingFile(kind)
        if (!file.exists()) return null
        return try {
            val p = Properties().apply { file.inputStream().use { load(it) } }
            Pending(
                sourceId = p.getProperty("sourceId") ?: return null,
                url = p.getProperty("url") ?: return null,
                fileName = p.getProperty("fileName"),
                sha256Url = p.getProperty("sha256Url"),
                total = p.getProperty("total")?.toLongOrNull() ?: -1,
            )
        } catch (e: IOException) {
            null
        }
    }

    private fun writePending(kind: ContentKind, pending: Pending) {
        val p = Properties()
        p.setProperty("sourceId", pending.sourceId)
        pending.url?.let { p.setProperty("url", it) }
        pending.fileName?.let { p.setProperty("fileName", it) }
        pending.sha256Url?.let { p.setProperty("sha256Url", it) }
        p.setProperty("total", pending.total.toString())
        store.pendingFile(kind).outputStream().use { p.store(it, null) }
    }

    private companion object {
        const val BUFFER = 256 * 1024
        const val REPORT_MS = 250L
        const val MAX_RETRIES = 5
    }
}
