package com.offlinewiki.simple.llm

import com.offlinewiki.simple.data.ContentKind
import com.offlinewiki.simple.data.ContentStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

sealed interface LlmState {
    data object NoModel : LlmState
    /** A model file is installed but not in memory. */
    data class Installed(val file: File) : LlmState
    data class Loading(val file: File) : LlmState
    data class Ready(val file: File) : LlmState
    data class Failed(val file: File, val message: String) : LlmState
}

/**
 * Owns the model's lifecycle. The model is loaded only when something first
 * asks for text, so reading and plain search never pay for its memory.
 */
class LlmManager(private val store: ContentStore, private val createEngine: (File) -> LlmEngine) {
    private val _state = MutableStateFlow<LlmState>(LlmState.NoModel)
    val state: StateFlow<LlmState> = _state

    // One generation at a time; also guards loading and unloading.
    private val mutex = Mutex()
    private var engine: LlmEngine? = null

    init {
        refresh()
    }

    /** Re-reads which model file is installed. Call after a download, import or removal. */
    fun refresh() {
        val file = store.installed(ContentKind.MODEL)
        val current = _state.value
        _state.value = when {
            file == null -> LlmState.NoModel
            current is LlmState.Ready && current.file == file -> current
            else -> LlmState.Installed(file)
        }
    }

    fun generate(prompt: String, options: GenerationOptions = GenerationOptions()): Flow<String> = flow {
        mutex.withLock { emitAll(loaded().generate(prompt, options)) }
    }

    private suspend fun loaded(): LlmEngine {
        engine?.let { return it }
        val file = store.installed(ContentKind.MODEL) ?: throw LlmException("No AI model is installed.")
        _state.value = LlmState.Loading(file)
        val created = createEngine(file)
        try {
            created.load()
        } catch (e: CancellationException) {
            runCatching { created.close() }
            _state.value = LlmState.Installed(file)
            throw e
        } catch (e: Throwable) {
            runCatching { created.close() }
            val message = "The model could not be loaded: ${e.message ?: e.javaClass.simpleName}"
            _state.value = LlmState.Failed(file, message)
            throw LlmException(message, e)
        }
        engine = created
        _state.value = LlmState.Ready(file)
        return created
    }

    /** Frees the model's memory if nothing is being generated right now. */
    fun unloadIfIdle() {
        if (!mutex.tryLock()) return
        try {
            closeEngine()
        } finally {
            mutex.unlock()
        }
    }

    suspend fun remove() {
        mutex.withLock {
            closeEngine()
            store.remove(ContentKind.MODEL)
            _state.value = LlmState.NoModel
        }
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        refresh()
        (_state.value as? LlmState.Ready)?.let { _state.value = LlmState.Installed(it.file) }
    }
}
