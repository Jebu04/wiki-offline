package com.offlinewiki.simple.ui.setup

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinewiki.simple.AppContainer
import com.offlinewiki.simple.BuildConfig
import com.offlinewiki.simple.data.ContentKind
import com.offlinewiki.simple.data.ContentStore
import com.offlinewiki.simple.data.transfer.DownloadSource
import com.offlinewiki.simple.data.transfer.Phase
import com.offlinewiki.simple.data.transfer.Sources
import com.offlinewiki.simple.data.transfer.TransferState

private enum class Network { NONE, METERED, UNMETERED }

private fun networkStatus(context: Context): Network {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    return when {
        cm.activeNetwork == null -> Network.NONE
        cm.isActiveNetworkMetered -> Network.METERED
        else -> Network.UNMETERED
    }
}

@Composable
fun SetupScreen(container: AppContainer, brokenMessage: String?) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Set up offline Wikipedia", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The articles are kept in one file on this device. Get it once, and after that " +
                "the app works with no internet connection.",
        )
        if (brokenMessage != null) {
            Message("The content file on this device could not be opened ($brokenMessage). Download or import it again.")
        }
        TransferPanel(container, ContentKind.ZIM, Sources.zim, importLabel = "Import a ZIM file from this device")
    }
}

/** Download-or-import controls and progress for one kind of content file. */
@Composable
fun TransferPanel(container: AppContainer, kind: ContentKind, allSources: List<DownloadSource>, importLabel: String) {
    val context = LocalContext.current
    val transfer by container.transfers.state.collectAsStateWithLifecycle()
    val sources = remember(allSources) { allSources.filter { BuildConfig.DEBUG || !it.debugOnly } }
    var selectedId by rememberSaveable { mutableStateOf(sources.first().id) }
    val selected = sources.first { it.id == selectedId }
    var meteredPrompt by remember { mutableStateOf<DownloadSource?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) container.transfers.startImport(kind, uri)
    }

    fun begin(source: DownloadSource) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        container.transfers.startDownload(source)
    }

    fun requestDownload(source: DownloadSource) {
        when (networkStatus(context)) {
            Network.NONE -> notice = "You are not connected to the internet."
            Network.METERED -> meteredPrompt = source
            Network.UNMETERED -> begin(source)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (val state = transfer) {
            is TransferState.Running -> Progress(
                state,
                onPause = container.transfers::pause,
                onCancel = container.transfers::discard,
            )
            is TransferState.Paused -> {
                Message(state.reason)
                ProgressLine(state.doneBytes, state.totalBytes)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = container.transfers::resume) { Text("Resume") }
                    OutlinedButton(onClick = container.transfers::discard) { Text("Start over") }
                }
            }
            else -> {
                if (state is TransferState.Failed) Message(state.message)
                notice?.let { Message(it) }

                val free = container.store.usableBytes()
                val enoughSpace = free - ContentStore.SPACE_MARGIN_BYTES > selected.approxBytes

                sources.forEach { source ->
                    SourceRow(source, selected = source.id == selectedId, onSelect = { selectedId = source.id })
                }
                Text(
                    "Free space on this device: ${Formatter.formatShortFileSize(context, free)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!enoughSpace) {
                    Message("There is not enough free space for this download. Free up some space and come back.")
                }
                Button(
                    onClick = { notice = null; requestDownload(selected) },
                    enabled = enoughSpace,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Download") }
                OutlinedButton(
                    onClick = { notice = null; picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(importLabel) }
                Text(
                    "Importing copies the file into the app, so it needs as much free space as the file itself.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    meteredPrompt?.let { source ->
        AlertDialog(
            onDismissRequest = { meteredPrompt = null },
            title = { Text("You are not on Wi-Fi") },
            text = {
                Text(
                    "This download is about ${Formatter.formatShortFileSize(context, source.approxBytes)} " +
                        "and may use your mobile data allowance.",
                )
            },
            confirmButton = {
                TextButton(onClick = { meteredPrompt = null; begin(source) }) { Text("Download anyway") }
            },
            dismissButton = { TextButton(onClick = { meteredPrompt = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SourceRow(source: DownloadSource, selected: Boolean, onSelect: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(source.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${source.detail} · about ${Formatter.formatShortFileSize(context, source.approxBytes)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Progress(state: TransferState.Running, onPause: () -> Unit, onCancel: () -> Unit) {
    val label = when (state.phase) {
        Phase.CONNECTING -> "Connecting…"
        Phase.DOWNLOADING -> "Downloading"
        Phase.COPYING -> "Copying the file into the app"
        Phase.VERIFYING -> "Checking the download"
    }
    Text(label, style = MaterialTheme.typography.titleMedium)
    ProgressLine(state.doneBytes, state.totalBytes, indeterminate = state.phase == Phase.CONNECTING)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.phase == Phase.DOWNLOADING) OutlinedButton(onClick = onPause) { Text("Pause") }
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
    Text("You can leave the app; this carries on in the background.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ProgressLine(done: Long, total: Long, indeterminate: Boolean = false) {
    val context = LocalContext.current
    if (total > 0 && !indeterminate) {
        LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        Text(
            "${Formatter.formatShortFileSize(context, done)} of ${Formatter.formatShortFileSize(context, total)} " +
                "(${done * 100 / total}%)",
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (done > 0) Text(Formatter.formatShortFileSize(context, done), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Message(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(
            text,
            Modifier.fillMaxWidth().padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
