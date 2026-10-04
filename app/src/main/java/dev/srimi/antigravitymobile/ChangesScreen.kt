package dev.srimi.antigravitymobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun ChangesScreen(model: ChangesViewModel, notify: (String) -> Unit, onOpenProjects: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.message) { state.message?.let { notify(it); model.dismissMessage() } }
    if (state.project == null) {
        EmptyState("No project open", "Agent edits are grouped by task and reviewed here.") { Button(onClick = onOpenProjects) { Text("Go to Projects") } }
        return
    }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var committing by remember { mutableStateOf(false) }
    var reverting by remember { mutableStateOf<ChangeSetRecord?>(null) }
    val accepted = state.sets.count { it.status == "ACCEPTED" }
    val pending = state.sets.count { it.status == "REVIEW" }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Changes", style = MaterialTheme.typography.headlineSmall)
            Text("$pending to review · $accepted accepted, not committed", style = MaterialTheme.typography.bodySmall)
        }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (accepted > 0) item {
            SectionCard("Commit accepted changes") {
                if (state.isRepo) {
                    Text("Commits only files touched by accepted change sets. Other work in the project stays uncommitted.")
                    Button(onClick = { committing = true }, enabled = !state.busy) { Text("Commit $accepted set(s)") }
                } else Text("This project is not a Git repository. Initialize Git from the project's Git tab to commit.")
            }
        }
        if (state.sets.isEmpty()) item { EmptyState("Nothing to review", "When the agent edits files, each task's changes appear here with a diff.") }
        items(state.sets, key = { it.id }) { set ->
            val open = set.id in expanded || set.status == "REVIEW"
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().clickable {
                        expanded = if (set.id in expanded) expanded - set.id else expanded + set.id
                        model.load(set.id)
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(set.summary, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${formatTime(set.createdAt)} · ${set.detail}", style = MaterialTheme.typography.bodySmall)
                        }
                        StatusChip(set.status.lowercase(), set.status)
                    }
                    if (open) {
                        val files = state.files[set.id]
                        val failed = state.loadErrors[set.id]
                        if (files == null && failed != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Diff could not be loaded: $failed", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = { model.load(set.id, force = true) }) { Text("Retry") }
                        }
                        else if (files == null) Text("Loading diff…")
                        else files.forEach { file ->
                            Text("${file.kind}  ${file.path}  +${file.added} −${file.removed}", fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelLarge)
                            DiffView(file.diff)
                        }
                    }
                    if (set.status == "REVIEW" || set.status == "ACCEPTED") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (set.status == "REVIEW") Button(onClick = { model.accept(set.id) }, enabled = !state.busy && state.files[set.id] != null) { Text("Keep") }
                        OutlinedButton(onClick = { reverting = set }, enabled = !state.busy) { Text("Revert") }
                    }
                }
            }
        }
    }
    if (committing) TextPromptDialog("Commit accepted changes", "Commit message", "Commit",
        initial = state.sets.firstOrNull { it.status == "ACCEPTED" }?.summary.orEmpty(), onDismiss = { committing = false }) { message, _ ->
        committing = false; model.commitAccepted(message)
    }
    reverting?.let { set ->
        ConfirmDialog("Revert this task's changes?", "Files return to how they were before the task. If you edited any of them afterwards, " +
            "the revert is refused so your work is not overwritten.", "Revert", onDismiss = { reverting = null }) { model.revert(set.id) }
    }
}
