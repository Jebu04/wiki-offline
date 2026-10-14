package com.offlinewiki.simple

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinewiki.simple.ui.ask.AskScreen
import com.offlinewiki.simple.ui.info.ContentInfoScreen
import com.offlinewiki.simple.ui.reader.ReaderScreen
import com.offlinewiki.simple.ui.saved.SavedScreen
import com.offlinewiki.simple.ui.search.SearchScreen
import com.offlinewiki.simple.ui.setup.SetupScreen
import com.offlinewiki.simple.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableSharedFlow

private enum class Overlay { NONE, SEARCH, ASK, SAVED, INFO }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val container = (application as App).container
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val dark = container.theme.dark ?: systemDark
        // Set on the activity as well as in Compose: the WebView decides whether to darken articles from this theme.
        setTheme(if (dark) R.style.Theme_SimpleWiki_Dark else R.style.Theme_SimpleWiki_Light)
        super.onCreate(savedInstanceState)
        val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            AppTheme(dark = dark) {
                Surface(Modifier.fillMaxSize()) {
                    val library by container.library.collectAsStateWithLifecycle()
                    when (val state = library) {
                        LibraryState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        LibraryState.NeedsSetup -> SetupScreen(container, brokenMessage = null)
                        is LibraryState.Broken -> SetupScreen(container, brokenMessage = state.message)
                        is LibraryState.Ready -> {
                            var overlay by rememberSaveable { mutableStateOf(Overlay.NONE) }
                            var askQuestion by rememberSaveable { mutableStateOf("") }
                            val openRequests = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }
                            // The reader stays composed underneath so the WebView keeps its page and history.
                            ReaderScreen(
                                zim = container.zim,
                                saved = container.saved,
                                fallbackTitle = state.info.title,
                                dark = dark,
                                onToggleDark = {
                                    container.theme.dark = !dark
                                    // Open pages and screens are saved and restored across the restart.
                                    recreate()
                                },
                                openRequests = openRequests,
                                onSearch = { overlay = Overlay.SEARCH },
                                onAsk = { askQuestion = ""; overlay = Overlay.ASK },
                                onShowSaved = { overlay = Overlay.SAVED },
                                onShowContentInfo = { overlay = Overlay.INFO },
                            )
                            when (overlay) {
                                Overlay.NONE -> {}
                                Overlay.SEARCH -> Surface(Modifier.fillMaxSize()) {
                                    SearchScreen(
                                        zim = container.zim,
                                        hasFulltextIndex = state.info.hasFulltextIndex,
                                        onOpen = { path -> openRequests.tryEmit(path); overlay = Overlay.NONE },
                                        onAsk = { question -> askQuestion = question; overlay = Overlay.ASK },
                                        onBack = { overlay = Overlay.NONE },
                                    )
                                }
                                Overlay.ASK -> Surface(Modifier.fillMaxSize()) {
                                    AskScreen(
                                        container,
                                        initialQuestion = askQuestion,
                                        onOpen = { path -> openRequests.tryEmit(path); overlay = Overlay.NONE },
                                        onBack = { overlay = Overlay.NONE },
                                    )
                                }
                                Overlay.SAVED -> Surface(Modifier.fillMaxSize()) {
                                    SavedScreen(
                                        container.saved,
                                        onOpen = { path -> openRequests.tryEmit(path); overlay = Overlay.NONE },
                                        onBack = { overlay = Overlay.NONE },
                                    )
                                }
                                Overlay.INFO -> Surface(Modifier.fillMaxSize()) {
                                    ContentInfoScreen(
                                        state.info,
                                        onBack = { overlay = Overlay.NONE },
                                        onReplaceContent = container::removeContent,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
