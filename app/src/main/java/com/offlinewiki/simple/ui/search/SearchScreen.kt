package com.offlinewiki.simple.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.offlinewiki.simple.zim.SearchHit
import com.offlinewiki.simple.zim.ZimRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Plain search: title suggestions while typing, full-text results on submit.
 * It only needs the ZIM file, so it works whether or not an AI model is installed.
 */
@Composable
fun SearchScreen(
    zim: ZimRepository,
    hasFulltextIndex: Boolean,
    onOpen: (String) -> Unit,
    onAsk: (String) -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // The query whose full-text results are showing; null while the user is still typing.
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var results by remember { mutableStateOf<List<SearchHit>?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { focus.requestFocus() }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            suggestions = emptyList()
        } else {
            delay(120)
            suggestions = withContext(Dispatchers.IO) { zim.suggest(q) }
        }
    }
    LaunchedEffect(submitted) {
        val q = submitted
        results = null
        if (q != null) results = withContext(Dispatchers.IO) { zim.search(q) }
    }

    fun submit() {
        val q = query.trim()
        if (q.isNotEmpty() && hasFulltextIndex) {
            keyboard?.hide()
            submitted = q
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        TextField(
            value = query,
            onValueChange = { query = it; submitted = null },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = { Text("Search Wikipedia") },
            singleLine = true,
            leadingIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; submitted = null; focus.requestFocus() }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        HorizontalDivider()

        val current = submitted
        if (current == null) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (query.isNotBlank() && hasFulltextIndex) {
                    item(key = "fulltext") {
                        Row(
                            Modifier.fillMaxWidth().clickable { submit() }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = null)
                            Text(
                                "Search inside articles for “${query.trim()}”",
                                Modifier.padding(start = 16.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider()
                    }
                    item(key = "ask") {
                        Row(
                            Modifier.fillMaxWidth().clickable { onAsk(query.trim()) }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Star, contentDescription = null)
                            Text(
                                "Ask AI: “${query.trim()}”",
                                Modifier.padding(start = 16.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider()
                    }
                }
                items(suggestions, key = { it.path }) { hit ->
                    Text(
                        hit.title,
                        Modifier.fillMaxWidth().clickable { onOpen(hit.path) }.padding(horizontal = 16.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        } else {
            val list = results
            when {
                list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                list.isEmpty() -> Text("No articles found for “$current”.", Modifier.padding(16.dp))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.path }) { hit -> ResultRow(hit, onClick = { onOpen(hit.path) }) }
                }
            }
        }
    }
}

@Composable
fun ResultRow(hit: SearchHit, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(hit.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (!hit.snippet.isNullOrBlank()) {
            Text(
                snippetText(hit.snippet),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider()
}

/** Turns a libzim snippet, which marks matches with <b> tags, into styled text. */
private fun snippetText(snippet: String): AnnotatedString = buildAnnotatedString {
    val parts = snippet.split(Regex("(?i)</?b>"))
    parts.forEachIndexed { i, part ->
        // Entities are decoded by hand: an HTML parser would drop the spaces next to the bold words.
        val text = part.replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
            .replace('\n', ' ')
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text) } else append(text)
    }
}
