package com.offlinewiki.simple.ui.reader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.offlinewiki.simple.R
import com.offlinewiki.simple.data.SavedPages
import com.offlinewiki.simple.zim.ZimRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    zim: ZimRepository,
    saved: SavedPages,
    fallbackTitle: String,
    dark: Boolean,
    onToggleDark: () -> Unit,
    openRequests: Flow<String>,
    onSearch: () -> Unit,
    onAsk: () -> Unit,
    onShowSaved: () -> Unit,
    onShowContentInfo: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf<String?>(null) }
    // Saved so the same article comes back after a theme change, rotation or the app being killed in the background.
    var currentPath by rememberSaveable { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var externalLink by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val bookmarks by saved.bookmarks.collectAsStateWithLifecycle()
    val bookmarked = currentPath != null && bookmarks.any { it.path == currentPath }

    val webView = remember {
        WebView(context).apply {
            // Scripts come only from the ZIM file (collapsible sections and so on); network access is cut off.
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // Lets the WebView darken article pages when the app is in its dark theme.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
            }
            webViewClient = ZimWebViewClient(
                zim = zim,
                onExternalLink = { externalLink = it },
                onPage = { url, pageTitle, back, finished ->
                    val path = url?.let { ZimWebViewClient.pathOf(Uri.parse(it)) }
                    val shown = pageTitle?.takeUnless { it.isBlank() || it.startsWith(ZimWebViewClient.HOST) }
                    title = shown
                    canGoBack = back
                    if (path != null) currentPath = path
                    if (finished && path != null && shown != null) saved.recordVisit(path, shown)
                },
            )
        }
    }
    DisposableEffect(webView) { onDispose { webView.destroy() } }

    fun open(lookup: () -> String?) {
        scope.launch {
            val path = withContext(Dispatchers.IO) { lookup() } ?: return@launch
            webView.loadUrl(ZimWebViewClient.urlFor(path))
        }
    }

    // Other screens (search, AI answers, bookmarks) ask the reader to open an article by its ZIM path.
    LaunchedEffect(openRequests) {
        openRequests.collect { path -> webView.loadUrl(ZimWebViewClient.urlFor(path)) }
    }

    BackHandler(enabled = canGoBack) { webView.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title ?: fallbackTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = { webView.goBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onToggleDark) {
                        Icon(
                            painterResource(if (dark) R.drawable.ic_light_mode else R.drawable.ic_dark_mode),
                            contentDescription = if (dark) "Switch to light mode" else "Switch to dark mode",
                        )
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        val items = listOf(
                            "Ask AI" to onAsk,
                            (if (bookmarked) "Remove bookmark" else "Bookmark this page") to {
                                currentPath?.let { saved.toggleBookmark(it, title ?: it) }
                                Unit
                            },
                            "Main page" to { open(zim::mainPagePath) },
                            "Random article" to { open(zim::randomPath) },
                            "Bookmarks and history" to onShowSaved,
                            "Content file" to onShowContentInfo,
                        )
                        items.forEach { (label, action) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; action() })
                        }
                    }
                },
            )
        },
    ) { padding ->
        AndroidView(
            factory = {
                val restore = currentPath
                if (restore != null) webView.loadUrl(ZimWebViewClient.urlFor(restore)) else open(zim::mainPagePath)
                webView
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }

    externalLink?.let { url ->
        AlertDialog(
            onDismissRequest = { externalLink = null },
            title = { Text("This link needs the internet") },
            text = { Text("It goes outside the offline copy:\n$url") },
            confirmButton = {
                TextButton(onClick = {
                    externalLink = null
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: ActivityNotFoundException) {
                    }
                }) { Text("Open in browser") }
            },
            dismissButton = { TextButton(onClick = { externalLink = null }) { Text("Stay here") } },
        )
    }
}
