package dev.srimi.antigravitymobile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Lists the signed-in user's repositories (or search results) and clones the chosen one. */
@Composable fun GitHubRepoPicker(state: ProjectsState, model: ProjectsViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { model.loadGitHubRepos("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Open from GitHub") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, label = { Text("Search (empty = your repositories)") }, singleLine = true,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { model.loadGitHubRepos(query) }, enabled = !state.gitHubLoading) { Text("Find") }
            }
            if (state.gitHubLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.gitHubLoading && state.gitHubRepos.isEmpty()) Text("No repositories yet. Sign in to GitHub in Accounts to see yours, or search public repositories by name.",
                style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(state.gitHubRepos, key = { it.fullName }) { repo ->
                    ListItem(modifier = Modifier.clickable { onDismiss(); model.cloneGitHub(repo) },
                        headlineContent = { Text(repo.fullName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text((if (repo.private) "Private · " else "Public · ") + repo.defaultBranch +
                            (if (repo.description.isNotBlank()) " · ${repo.description}" else ""), maxLines = 2, overflow = TextOverflow.Ellipsis) })
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable fun BranchDialog(git: GitPanel, model: ProjectsViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Branches") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Current: ${git.status?.branch ?: "?"}", fontFamily = FontFamily.Monospace)
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(git.branches) { branch ->
                    ListItem(modifier = Modifier.clickable(enabled = branch != git.status?.branch) { onDismiss(); model.switchBranch(branch, false) },
                        headlineContent = { Text(branch, fontFamily = FontFamily.Monospace) },
                        supportingContent = { if (branch == git.status?.branch) Text("current") else if (branch.startsWith("origin/")) Text("on GitHub only — tap to check out") })
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("New branch name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { onDismiss(); model.switchBranch(name, true) }, enabled = name.isNotBlank()) { Text("Create and switch") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable fun PublishDialog(projectName: String, model: ProjectsViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(projectName.replace(Regex("[^A-Za-z0-9_.-]+"), "-").trim('-')) }
    var private by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Publish to GitHub") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Repository name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(private, { private = it }); Text("Private repository") }
            Text("Creates the repository on your GitHub account and pushes the current branch.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onDismiss(); model.publishToGitHub(name, private) }, enabled = name.isNotBlank()) { Text("Publish") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable fun PullRequestDialog(branch: String, model: ProjectsViewModel, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Create pull request") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pushes $branch, then opens a pull request into the repository's default branch.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(body, { body = it }, label = { Text("Description (optional)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { onDismiss(); model.createPullRequest(title, body) }, enabled = title.isNotBlank()) { Text("Push and open PR") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable fun OpenLink(label: String, url: String) {
    val context = LocalContext.current
    OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text(label) }
}
