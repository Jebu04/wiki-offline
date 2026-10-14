package com.offlinewiki.simple.ui.ask

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinewiki.simple.AppContainer
import com.offlinewiki.simple.ai.AskEvent
import com.offlinewiki.simple.ai.AskStage
import com.offlinewiki.simple.ai.Source
import com.offlinewiki.simple.data.ContentKind
import com.offlinewiki.simple.data.transfer.Sources
import com.offlinewiki.simple.llm.LlmState
import com.offlinewiki.simple.ui.setup.Message
import com.offlinewiki.simple.ui.setup.TransferPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun AskScreen(container: AppContainer, initialQuestion: String, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val llmState by container.llm.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Ask AI", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (llmState is LlmState.NoModel) ModelSetup(container) else Ask(container, llmState, initialQuestion, onOpen)
        }
    }
}

@Composable
private fun ModelSetup(container: AppContainer) {
    Text(
        "The assistant runs entirely on this device. It needs a model file, which you get once. " +
            "Reading and normal search work without it.",
    )
    TransferPanel(container, ContentKind.MODEL, Sources.model, importLabel = "Import a model file (.litertlm or .task)")
    Text(
        "To use Google's Gemma 3 1B instead, accept its licence at huggingface.co/litert-community/Gemma3-1B-IT, " +
            "download gemma3-1b-it-int4.litertlm or gemma3-1b-it-int4.task there, and import it here.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Ask(container: AppContainer, llmState: LlmState, initialQuestion: String, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var question by rememberSaveable { mutableStateOf(initialQuestion) }
    var stage by remember { mutableStateOf<AskStage?>(null) }
    var queries by remember { mutableStateOf<List<String>>(emptyList()) }
    var answer by remember { mutableStateOf("") }
    var sources by remember { mutableStateOf<List<Source>>(emptyList()) }
    var notFound by remember { mutableStateOf<AskEvent.NotFound?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val running = job?.isActive == true

    fun ask() {
        val text = question.trim()
        if (text.isEmpty() || running) return
        keyboard?.hide()
        stage = null; queries = emptyList(); answer = ""; sources = emptyList(); notFound = null; error = null
        job = scope.launch {
            try {
                container.aiSearch.ask(text).collect { event ->
                    when (event) {
                        is AskEvent.Progress -> stage = event.stage
                        is AskEvent.Queries -> queries = event.queries
                        is AskEvent.Answer -> answer = event.text
                        is AskEvent.Answered -> { answer = event.text; sources = event.sources }
                        is AskEvent.NotFound -> { answer = ""; notFound = event }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Something went wrong."
            } finally {
                stage = null
            }
        }
    }

    LaunchedEffect(Unit) { if (initialQuestion.isNotBlank()) ask() }

    OutlinedTextField(
        value = question,
        onValueChange = { question = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Ask a question") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { ask() }),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = ::ask, enabled = !running && question.isNotBlank()) { Text("Ask") }
        if (running) OutlinedButton(onClick = { job?.cancel() }) { Text("Stop") }
    }

    if (running) {
        val status = when {
            llmState is LlmState.Loading -> "Loading the model. The first time can take a while."
            stage == AskStage.REWRITING -> "Working out what to search for…"
            stage == AskStage.SEARCHING -> "Searching the articles…"
            stage == AskStage.READING -> "Reading the articles…"
            else -> "Writing the answer…"
        }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        if (answer.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (queries.isNotEmpty()) {
        Text("Searched for: " + queries.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
    error?.let { Message(it) }
    if (answer.isNotEmpty()) Text(answer, style = MaterialTheme.typography.bodyLarge)

    if (sources.isNotEmpty()) {
        Text("From these articles", style = MaterialTheme.typography.titleSmall)
        Links(sources.map { it.path to it.title }, onOpen)
        Text(
            "Written by an AI model on this device from the articles above. Check the articles for anything important.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    notFound?.let { result ->
        Message(result.reason)
        if (result.results.isNotEmpty()) {
            Text("Search results", style = MaterialTheme.typography.titleSmall)
            Links(result.results.map { it.path to it.title }, onOpen)
        }
    }

    if (!running) {
        val file = (llmState as? LlmState.Installed)?.file ?: (llmState as? LlmState.Ready)?.file
            ?: (llmState as? LlmState.Failed)?.file
        Text("Model: ${file?.name.orEmpty()}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { scope.launch { container.llm.remove() } }) { Text("Remove this model") }
    }
}

@Composable
private fun Links(links: List<Pair<String, String>>, onOpen: (String) -> Unit) {
    Column {
        links.forEach { (path, title) ->
            Text(
                title,
                Modifier.fillMaxWidth().clickable { onOpen(path) }.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
