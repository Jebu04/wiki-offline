package com.offlinewiki.simple.ui.info

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.offlinewiki.simple.zim.ZimInfo

/** Shows what the installed ZIM file contains and lets the user replace it. */
@Composable
fun ContentInfoScreen(info: ZimInfo, onBack: () -> Unit, onReplaceContent: () -> Unit) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(info.title, style = MaterialTheme.typography.headlineSmall)
        if (info.date.isNotEmpty()) Text("Content date: ${info.date}")
        Text("Articles: ${"%,d".format(info.articleCount)}")
        Text("Images and media: ${"%,d".format(info.mediaCount)}")
        Text("Full-text search index: ${if (info.hasFulltextIndex) "yes" else "no"}")
        Text("Title index: ${if (info.hasTitleIndex) "yes" else "no"}")
        Text(
            "${info.file.name} · ${Formatter.formatShortFileSize(context, info.file.length())}",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { confirm = true }, modifier = Modifier.padding(top = 16.dp)) {
            Text("Replace content file")
        }
        TextButton(onClick = onBack) { Text("Back to reading") }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete this content file?") },
            text = { Text("You will need to download or import a file again before you can read.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; onReplaceContent() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
