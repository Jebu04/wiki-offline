package com.offlinewiki.simple.data

import android.content.Context
import java.io.File

enum class ContentKind(val dirName: String, val extensions: List<String>, val label: String) {
    ZIM("zim", listOf("zim"), "Wikipedia content"),
    // .litertlm is the current LiteRT-LM format; .task is the older MediaPipe bundle some models ship in.
    MODEL("models", listOf("litertlm", "task"), "AI model");

    /** The extension given to a file whose own name does not have a usable one. */
    val extension: String get() = extensions.first()

    fun accepts(file: File): Boolean = extensions.any { file.extension.equals(it, ignoreCase = true) }
}

/**
 * Large content files live in app-specific external storage, so no storage
 * permission is needed and they are removed when the app is uninstalled.
 */
class ContentStore(context: Context) {
    private val root: File = context.getExternalFilesDir(null) ?: context.filesDir

    fun dir(kind: ContentKind): File = File(root, kind.dirName).apply { mkdirs() }

    fun installed(kind: ContentKind): File? =
        dir(kind).listFiles { f -> f.isFile && kind.accepts(f) }
            ?.maxByOrNull { it.lastModified() }

    fun partFile(kind: ContentKind): File = File(dir(kind), "incoming.part")

    fun pendingFile(kind: ContentKind): File = File(dir(kind), "incoming.properties")

    fun usableBytes(): Long = root.usableSpace

    fun discardPartial(kind: ContentKind) {
        partFile(kind).delete()
        pendingFile(kind).delete()
    }

    /** Moves a finished partial file into place and removes any older file of the same kind. */
    fun commit(kind: ContentKind, fileName: String): File {
        val dir = dir(kind)
        val target = File(dir, fileName)
        dir.listFiles { f -> kind.accepts(f) }?.forEach { it.delete() }
        if (!partFile(kind).renameTo(target)) throw java.io.IOException("Could not move file into place")
        pendingFile(kind).delete()
        return target
    }

    fun remove(kind: ContentKind) {
        dir(kind).listFiles { f -> kind.accepts(f) }?.forEach { it.delete() }
    }

    companion object {
        /** Kept free on top of the file itself so the device does not run completely full. */
        const val SPACE_MARGIN_BYTES = 200L * 1024 * 1024
    }
}
