package com.offlinewiki.simple.data.transfer

import com.offlinewiki.simple.data.ContentKind
import java.io.File

enum class Phase { CONNECTING, DOWNLOADING, COPYING, VERIFYING }

sealed interface TransferState {
    data object Idle : TransferState

    /** [totalBytes] is -1 when the size is not known yet. */
    data class Running(
        val kind: ContentKind,
        val phase: Phase,
        val doneBytes: Long,
        val totalBytes: Long,
    ) : TransferState

    /** A download stopped part-way; the partial file is kept and can be resumed. */
    data class Paused(
        val kind: ContentKind,
        val doneBytes: Long,
        val totalBytes: Long,
        val reason: String,
    ) : TransferState

    data class Failed(val kind: ContentKind, val message: String) : TransferState

    data class Done(val kind: ContentKind, val file: File) : TransferState
}

class TransferException(message: String, val resumable: Boolean) : Exception(message)
