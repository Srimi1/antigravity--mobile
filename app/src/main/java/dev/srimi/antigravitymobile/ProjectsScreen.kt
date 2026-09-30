package dev.srimi.antigravitymobile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class ProjectDialog { CREATE, TEMPLATE, CLONE, NEW_FILE, NEW_FOLDER, COMMIT, RENAME }

@Composable fun ProjectsScreen(model: ProjectsViewModel, notify: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.message) { state.message?.let { notify(it); model.dismissMessage() } }
    var dialog by remember { mutableStateOf<ProjectDialog?>(null) }
    var includeGit by remember { mutableStateOf(true) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) model.importFolder(uri) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) model.export(uri, includeGit) }

    Column(Modifier.fillMaxSize()) {
        state.busy?.let { BusyRow(it, state.progress, onCancel = model::cancel) }
        val project = state.selected
        val editor = state.editor
        when {
            project == null -> ProjectList(state, model, onCreate = { dialog = ProjectDialog.CREATE },
                onTemplate = { dialog = ProjectDialog.TEMPLATE }, onClone = { dialog = ProjectDialog.CLONE },
                onImport = { importer.launch(null) })
            editor != null -> Editor(editor, model)
            else -> ProjectDetail(state, project, model, onDialog = { dialog = it },
                onExport = { git -> includeGit = git; exporter.launch("${project.name}.zip") })
        }
    }

    when (dialog) {
        ProjectDialog.CREATE -> TextPromptDialog("New project", "Name", "Create", onDismiss = { dialog = null }) { name, _ ->
            dialog = null; model.create(name, initGit = true)
        }
        ProjectDialog.TEMPLATE -> TextPromptDialog("New Compose app", "Name", "Create", initial = "Hello Phone", onDismiss = { dialog = null }) { name, _ ->
            dialog = null; model.createFromTemplate(name)
        }
        ProjectDialog.CLONE -> TextPromptDialog("Clone repository", "HTTPS URL", "Clone", secondLabel = "Project name (optional)",
            onDismiss = { dialog = null }) { url, name -> dialog = null; model.clone(url, name) }
        ProjectDialog.NEW_FILE -> TextPromptDialog("New file", "File name or relative path", "Create", onDismiss = { dialog = null }) { name, _ ->
            dialog = null; model.newFile(name)
        }
        ProjectDialog.NEW_FOLDER -> TextPromptDialog("New folder", "Folder name", "Create", onDismiss = { dialog = null }) { name, _ ->
            dialog = null; model.newFolder(name)
        }
        ProjectDialog.COMMIT -> TextPromptDialog("Commit all changes", "Commit message", "Commit", onDismiss = { dialog = null }) { message, _ ->
            dialog = null; model.commitAll(message)
        }
        ProjectDialog.RENAME -> TextPromptDialog("Rename project", "Name", "Rename", initial = state.selected?.name.orEmpty(),
            onDismiss = { dialog = null }) { name, _ -> dialog = null; model.rename(name) }
        null -> Unit
    }
}

@Composable private fun ProjectList(state: ProjectsState, model: ProjectsViewModel, onCreate: () -> Unit, onTemplate: () -> Unit,
                                    onClone: () -> Unit, onImport: () -> Unit) {
    var deleting by remember { mutableStateOf<ProjectRecord?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Projects", style = MaterialTheme.typography.headlineSmall)
            Text("Stored privately on this phone. Imports are copies; your original folders are never modified.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCreate, enabled = state.busy == null, modifier = Modifier.weight(1f)) { Text("New") }
                OutlinedButton(onClick = onClone, enabled = state.busy == null, modifier = Modifier.weight(1f)) { Text("Clone") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImport, enabled = state.busy == null, modifier = Modifier.weight(1f)) { Text("Import folder") }
                OutlinedButton(onClick = onTemplate, enabled = state.busy == null, modifier = Modifier.weight(1f)) { Text("Compose app") }
            }
        }
        if (state.projects.isEmpty()) item {
            EmptyState("No projects yet", "Create one, clone an HTTPS repository or import a folder to start.")
        }
        items(state.projects, key = { it.id }) { project ->
            Card(Modifier.fillMaxWidth().clickable { model.select(project) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Opened ${formatTime(project.openedAt)}", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { deleting = project }) { Icon(Icons.Default.Delete, contentDescription = "Delete ${project.name}") }
                }
            }
        }
    }
    deleting?.let { project ->
        ConfirmDialog("Delete ${project.name}?", "This removes the app's private copy, its conversations and change history. " +
            "Folders you imported from elsewhere are not touched. Export first if you want a backup.", "Delete",
            onDismiss = { deleting = null }) { model.delete(project) }
    }
}

@Composable private fun ProjectDetail(state: ProjectsState, project: ProjectRecord, model: ProjectsViewModel,
                                      onDialog: (ProjectDialog) -> Unit, onExport: (Boolean) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    BackHandler(enabled = state.directory.isNotEmpty()) { model.up() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = model::closeProject) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All projects") }
            Text(project.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Project actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onDialog(ProjectDialog.RENAME) })
                    DropdownMenuItem(text = { Text("Export ZIP with Git history") }, onClick = { menu = false; onExport(true) })
                    DropdownMenuItem(text = { Text("Export ZIP without .git") }, onClick = { menu = false; onExport(false) })
                }
            }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Files") })
            Tab(selected = tab == 1, onClick = { tab = 1; model.refreshGit() }, text = { Text("Git") })
        }
        if (tab == 0) FileBrowser(state, model, onDialog) else GitPanelView(state, model, onDialog)
    }
}

@Composable private fun FileBrowser(state: ProjectsState, model: ProjectsViewModel, onDialog: (ProjectDialog) -> Unit) {
    var deleting by remember { mutableStateOf<WorkspaceService.Entry?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("/" + state.directory, Modifier.weight(1f), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = model::refreshFiles) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.directory.isNotEmpty()) OutlinedButton(onClick = model::up) { Text("Up") }
                OutlinedButton(onClick = { onDialog(ProjectDialog.NEW_FILE) }, enabled = state.busy == null) { Text("New file") }
                OutlinedButton(onClick = { onDialog(ProjectDialog.NEW_FOLDER) }, enabled = state.busy == null) { Text("New folder") }
            }
        }
        if (state.entries.isEmpty()) item { EmptyState("Empty folder", "Create a file here or ask the agent to add one.") }
        items(state.entries, key = { it.path }) { entry ->
            ListItem(
                modifier = Modifier.clickable { if (entry.isDirectory) model.navigate(entry.path) else model.openFile(entry.path) },
                leadingContent = { Text(if (entry.isDirectory) "▸" else "·", style = MaterialTheme.typography.titleMedium) },
                headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { if (!entry.isDirectory) Text(size(entry.size)) },
                trailingContent = { IconButton(onClick = { deleting = entry }) { Icon(Icons.Default.Delete, contentDescription = "Delete ${entry.name}") } },
            )
        }
    }
    deleting?.let { entry ->
        ConfirmDialog("Delete ${entry.name}?", if (entry.isDirectory) "The folder and everything in it will be deleted." else
            "The file will be deleted. Use Git to recover committed content.", "Delete", onDismiss = { deleting = null }) { model.deleteEntry(entry) }
    }
}

private fun size(bytes: Long) = when { bytes < 1024 -> "$bytes B"; bytes < 1024 * 1024 -> "${bytes / 1024} KB"; else -> "${bytes / (1024 * 1024)} MB" }

@Composable private fun Editor(editor: EditorState, model: ProjectsViewModel) {
    var confirmClose by remember { mutableStateOf(false) }
    BackHandler { if (editor.dirty) confirmClose = true else model.closeEditor() }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (editor.dirty) confirmClose = true else model.closeEditor() }) { Icon(Icons.Default.Close, contentDescription = "Close file") }
            Text(editor.path + if (editor.dirty) " •" else "", Modifier.weight(1f), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Button(onClick = model::saveFile, enabled = editor.dirty) { Text("Save") }
        }
        if (editor.note != null) Text(editor.note, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else OutlinedTextField(value = editor.text, onValueChange = model::edit, readOnly = editor.readOnly,
            modifier = Modifier.fillMaxSize(), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
    }
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("Discard unsaved edits?") },
        confirmButton = { TextButton(onClick = { confirmClose = false; model.closeEditor() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep editing") } })
}

@Composable private fun GitPanelView(state: ProjectsState, model: ProjectsViewModel, onDialog: (ProjectDialog) -> Unit) {
    val git = state.git
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            git == null -> Text("Reading repository…")
            !git.isRepo -> EmptyState("Not a Git repository", "Initialize Git to track history and commit reviewed changes.") {
                Button(onClick = model::initGit, enabled = state.busy == null) { Text("Initialize Git") }
            }
            else -> {
                val status = git.status
                SectionCard("Working tree") {
                    Text(status?.summary ?: "Status unavailable")
                    git.remote?.let { Text("origin: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    status?.let { s ->
                        listOf("Staged" to (s.added + s.changed + s.removed), "Modified" to s.modified, "Untracked" to s.untracked,
                            "Deleted" to s.missing, "Conflicts" to s.conflicting).filter { it.second.isNotEmpty() }.forEach { (label, paths) ->
                            Text(label, style = MaterialTheme.typography.labelLarge)
                            paths.sorted().take(50).forEach { Text("  $it", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                            if (paths.size > 50) Text("  … ${paths.size - 50} more", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDialog(ProjectDialog.COMMIT) }, enabled = state.busy == null && status?.clean == false) { Text("Commit all") }
                        OutlinedButton(onClick = model::refreshGit) { Text("Refresh") }
                    }
                    Text("Agent edits are best committed from Changes after review, so only accepted files are included.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (git.remote != null) SectionCard("Remote") {
                    Text("Uses the HTTPS token saved in Accounts, if any.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = model::pull, enabled = state.busy == null) { Text("Pull") }
                        OutlinedButton(onClick = model::push, enabled = state.busy == null) { Text("Push") }
                    }
                }
                SectionCard("History") {
                    if (git.log.isEmpty()) Text("No commits yet")
                    git.log.forEach { commit ->
                        Column {
                            Text(commit.message, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${commit.id.take(8)} · ${commit.author} · ${formatTime(commit.time)}",
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}
