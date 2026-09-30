package dev.srimi.antigravitymobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable fun WebsitePanel(state: ProjectsState, model: ProjectsViewModel, export: () -> Unit) {
    val available = state.busy == null && state.editor?.dirty != true
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Website", style = MaterialTheme.typography.headlineSmall)
        Text("Preview saved HTML, CSS, JavaScript and local data on this phone.")
        OutlinedTextField(state.websiteRoot, model::websiteRoot, label = { Text("Website folder") },
            supportingText = { Text("Leave empty for the project root, or enter a folder such as dist.") },
            enabled = available, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.websiteEntry, model::websiteEntry, label = { Text("HTML entry inside that folder") },
            enabled = available, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = model::prepareWebsite, enabled = available && state.websiteEntry.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Review preview")
        }
        OutlinedButton(onClick = export, enabled = available && state.websiteEntry.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Export website ZIP")
        }
        Text("Install or update the bundled tools on Build before previewing. Each preview uses an approved copy. Reload keeps that copy; saved changes need a new review.",
            style = MaterialTheme.typography.bodySmall)
        Text("The ZIP puts your chosen website folder at its root and excludes hidden files and node_modules. Choose the public output folder when exporting a built frontend.",
            style = MaterialTheme.typography.bodySmall)
        Text("Static sites only at this stage. Package builds, backend servers, external APIs and deployment are still unavailable.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun WebsiteApproval(copy: WebsiteCopy, approve: () -> Unit, decline: () -> Unit) {
    AlertDialog(onDismissRequest = decline, title = { Text("Run this website preview?") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("The copied website's JavaScript will run in the separate tools app. HTTP network requests, file access and device permissions are blocked.")
            Text("Folder: ${copy.root.ifEmpty { "project root" }}\nEntry: ${copy.entry}\n${copy.files.size} files · ${copy.bytes / 1024} KB")
            Text("SHA-256: ${copy.hash}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            copy.files.take(20).forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            if (copy.files.size > 20) Text("… ${copy.files.size - 20} more files")
            Text("This approval is used once. Reload stays on this copy. Unsaved editor text is never included.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = approve) { Text("Approve and open") } },
        dismissButton = { TextButton(onClick = decline) { Text("Decline") } })
}
