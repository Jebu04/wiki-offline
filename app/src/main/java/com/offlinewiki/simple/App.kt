package com.offlinewiki.simple

import android.app.Application
import com.offlinewiki.simple.ai.AiSearch
import com.offlinewiki.simple.data.ContentKind
import com.offlinewiki.simple.data.ContentStore
import com.offlinewiki.simple.data.SavedPages
import com.offlinewiki.simple.data.ThemeSetting
import com.offlinewiki.simple.data.transfer.TransferManager
import com.offlinewiki.simple.data.transfer.TransferState
import com.offlinewiki.simple.llm.LiteRtLmEngine
import com.offlinewiki.simple.llm.LlmManager
import com.offlinewiki.simple.zim.ZimInfo
import com.offlinewiki.simple.zim.ZimRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The model is by far the largest thing in memory and is cheap to give up: it reloads on next use.
        if (level >= TRIM_MEMORY_RUNNING_LOW) container.llm.unloadIfIdle()
    }
}

sealed interface LibraryState {
    data object Loading : LibraryState
    data object NeedsSetup : LibraryState
    data class Ready(val info: ZimInfo) : LibraryState
    data class Broken(val file: File, val message: String) : LibraryState
}

class AppContainer(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val store = ContentStore(app)
    val transfers = TransferManager(app, store)
    val zim = ZimRepository(app)
    val llm = LlmManager(store) { file -> LiteRtLmEngine(file, app.cacheDir) }
    val aiSearch = AiSearch(zim, llm)
    val saved = SavedPages(app.filesDir)
    val theme = ThemeSetting(app)

    private val _library = MutableStateFlow<LibraryState>(LibraryState.Loading)
    val library: StateFlow<LibraryState> = _library

    init {
        refreshLibrary()
        scope.launch {
            transfers.state.collect { state ->
                if (state is TransferState.Done) {
                    when (state.kind) {
                        ContentKind.ZIM -> refreshLibrary()
                        ContentKind.MODEL -> llm.refresh()
                    }
                    transfers.acknowledge()
                }
            }
        }
    }

    fun refreshLibrary() {
        scope.launch {
            val file = store.installed(ContentKind.ZIM)
            _library.value = if (file == null) {
                LibraryState.NeedsSetup
            } else {
                try {
                    LibraryState.Ready(zim.open(file))
                } catch (e: Exception) {
                    LibraryState.Broken(file, e.message ?: "The file could not be opened.")
                }
            }
        }
    }

    fun removeContent() {
        scope.launch {
            zim.close()
            store.remove(ContentKind.ZIM)
            _library.value = LibraryState.NeedsSetup
        }
    }
}
