package dev.srimi.antigravitymobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
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

@Composable fun AgentScreen(model: AgentViewModel, onOpenProjects: () -> Unit, onOpenAccounts: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val project = state.project
    if (project == null) {
        EmptyState("No project open", "The agent works inside one project at a time. Open or create a project first.") {
            Button(onClick = onOpenProjects) { Text("Go to Projects") }
        }
        return
    }
    var input by rememberSaveable { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ConversationRecord?>(null) }
    val list = rememberLazyListState()
    val account = state.account
    val usable = account?.usable == true
    val lastIndex = state.messages.size + if (state.streaming.isNotEmpty()) 1 else 0
    LaunchedEffect(lastIndex, state.streaming.length / 200) { if (lastIndex > 0) list.animateScrollToItem(lastIndex - 1) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val title = state.conversations.firstOrNull { it.id == state.conversationId }?.title ?: "New conversation"
                Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { picker = true }, enabled = !state.running) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Conversations") }
                DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                    DropdownMenuItem(text = { Text("New conversation") }, leadingIcon = { Icon(Icons.Default.Add, null) },
                        onClick = { picker = false; model.newConversation() })
                    state.conversations.forEach { conversation ->
                        DropdownMenuItem(text = { Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = { IconButton(onClick = { picker = false; deleting = conversation }) { Icon(Icons.Default.Delete, "Delete conversation") } },
                            onClick = { picker = false; model.openConversation(conversation.id) })
                    }
                }
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip("ChatGPT ${account?.status?.name?.lowercase() ?: "…"}", account?.status?.name ?: "UNVERIFIED")
            StatusChip("Claude blocked", "BLOCKED")
            StatusChip("Google blocked", "BLOCKED")
        }
        if (!usable) Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(account?.detail ?: "Checking account…")
                Text("Only ChatGPT has a documented subscription route. Claude and Google are blocked; no API-key fallback exists.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onOpenAccounts) { Text("Open Accounts") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.messages.isEmpty() && state.streaming.isEmpty()) item {
                EmptyState("Ask for a change", "For example: \"Add a settings screen\" or \"Explain how MainActivity works\". " +
                    "The agent can read and search files; every write needs your approval and lands in Changes for review. It cannot build or run code.")
            }
            items(state.messages, key = { it.id }) { MessageBubble(it) }
            if (state.streaming.isNotEmpty()) item(key = "streaming") {
                MessageBubble(MessageRecord("streaming", "", "assistant", state.streaming, 0))
            }
        }
        if (state.running) BusyRow(if (state.approval != null) "Waiting for your approval" else "Agent working" +
            if (state.autoApprove) " · edits auto-approved for this task" else "", onCancel = model::stop)
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message the agent") },
                enabled = !state.running, maxLines = 6)
            Spacer(Modifier.width(8.dp))
            if (state.running) FilledTonalButton(onClick = model::stop) { Text("Stop") }
            else IconButton(onClick = { model.send(input); input = "" }, enabled = usable && input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }

    state.approval?.let { approval ->
        AlertDialog(onDismissRequest = {}, title = { Text("Approve ${approval.tool.replace('_', ' ')}?") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(approval.summary, style = MaterialTheme.typography.titleSmall)
                    Text("The change is recorded and can be reverted from Changes unless the file is edited again afterwards.",
                        style = MaterialTheme.typography.bodySmall)
                    if (approval.detail.isNotEmpty()) DiffView(approval.detail, maxLines = 300)
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { model.answerApproval(true) }) { Text("Approve") }
                    TextButton(onClick = { model.answerApproval(true, allForTask = true) }) { Text("Approve all edits in this task") }
                }
            },
            dismissButton = { TextButton(onClick = { model.answerApproval(false) }) { Text("Decline") } })
    }
    deleting?.let { conversation ->
        ConfirmDialog("Delete conversation?", "\"${conversation.title}\" and its action log will be removed. File changes stay in Changes.",
            "Delete", onDismiss = { deleting = null }) { model.deleteConversation(conversation.id) }
    }
}

@Composable private fun MessageBubble(message: MessageRecord) {
    when (message.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 320.dp)) {
                Text(message.content, Modifier.padding(12.dp))
            }
        }
        "assistant" -> Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            SelectableText(message.content)
        }
        "tool" -> Text("⟶ ${message.content}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        "error" -> Text(message.content, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        else -> Text(message.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable private fun SelectableText(text: String) {
    androidx.compose.foundation.text.selection.SelectionContainer { Text(text, Modifier.padding(12.dp)) }
}
