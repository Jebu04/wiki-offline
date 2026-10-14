package com.offlinewiki.simple.ui.saved

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinewiki.simple.data.SavedPage
import com.offlinewiki.simple.data.SavedPages

@Composable
fun SavedScreen(saved: SavedPages, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val bookmarks by saved.bookmarks.collectAsStateWithLifecycle()
    val history by saved.history.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Bookmarks and history", style = MaterialTheme.typography.titleLarge)
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Bookmarks") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History") })
        }
        val pages = if (tab == 0) bookmarks else history
        if (pages.isEmpty()) {
            Text(
                if (tab == 0) "No bookmarks yet. Use “Bookmark this page” in the menu on an article to save it." else "No pages read yet.",
                Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(pages, key = { it.path }) { page -> PageRow(page, showTime = tab == 1, onClick = { onOpen(page.path) }) }
                if (tab == 1) {
                    item { TextButton(onClick = saved::clearHistory, modifier = Modifier.padding(8.dp)) { Text("Clear history") } }
                }
            }
        }
    }
}

@Composable
private fun PageRow(page: SavedPage, showTime: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(page.title, style = MaterialTheme.typography.bodyLarge)
        if (showTime) {
            Text(
                DateUtils.getRelativeTimeSpanString(page.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    HorizontalDivider()
}
