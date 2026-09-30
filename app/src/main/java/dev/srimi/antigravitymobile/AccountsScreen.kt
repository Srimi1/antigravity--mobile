package dev.srimi.antigravitymobile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun AccountsScreen(model: AccountsViewModel, notify: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.message) { state.message?.let { notify(it); model.dismissMessage() } }
    LaunchedEffect(Unit) { model.refresh() }
    var disconnecting by remember { mutableStateOf(false) }
    val idle = state.busy == null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Accounts", style = MaterialTheme.typography.headlineSmall)
        Text("Subscriptions only. There is no API-key billing fallback, and credentials never leave Keystore-encrypted storage.",
            style = MaterialTheme.typography.bodySmall)
        state.busy?.let { BusyRow(it, onCancel = model::cancel) }

        state.accounts.forEach { account ->
            SectionCard(account.provider.label) {
                StatusChip(account.status.name.lowercase(), account.status.name)
                Text(account.detail)
                if (account.provider == ProviderId.CHATGPT) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { model.connectChatGpt { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                            enabled = idle) { Text(if (account.status == AccountStatus.DISCONNECTED) "Continue with ChatGPT" else "Reconnect") }
                        if (account.status != AccountStatus.DISCONNECTED)
                            OutlinedButton(onClick = model::verifyChatGpt, enabled = idle) { Text("Test request") }
                    }
                    if (account.status != AccountStatus.DISCONNECTED) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = model::renew, enabled = idle) { Text("Renew") }
                            OutlinedButton(onClick = model::loadModels, enabled = idle) { Text("Models") }
                            OutlinedButton(onClick = { disconnecting = true }, enabled = idle) { Text("Disconnect") }
                        }
                        Text("Model: ${state.preferredModel ?: "first listed for your account"}", style = MaterialTheme.typography.bodySmall)
                        if (state.models.isNotEmpty()) Column {
                            listOf<String?>(null).plus(state.models).forEach { slug ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = state.preferredModel == slug, onClick = { model.chooseModel(slug) })
                                    Text(slug ?: "Automatic", fontFamily = if (slug == null) null else FontFamily.Monospace)
                                }
                            }
                        }
                    }
                    if (state.output.isNotBlank()) Text("Response: ${state.output}", fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                    Text("Sign-in uses OpenAI's documented Sign in with ChatGPT flow in your browser. Eligibility for this app is " +
                        "confirmed only when a test request completes.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        GitSettings(state, model, idle)
    }
    if (disconnecting) ConfirmDialog("Disconnect ChatGPT?", "Local tokens are erased and the app asks OpenAI to revoke the session.",
        "Disconnect", onDismiss = { disconnecting = false }) { model.disconnect() }
}

@Composable private fun GitSettings(state: AccountsState, model: AccountsViewModel, idle: Boolean) {
    var name by remember(state.authorName) { mutableStateOf(state.authorName) }
    var email by remember(state.authorEmail) { mutableStateOf(state.authorEmail) }
    var user by remember(state.gitUser) { mutableStateOf(state.gitUser) }
    var token by remember { mutableStateOf("") }
    SectionCard("Git") {
        Text("Commit author", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { model.saveAuthor(name, email) }, enabled = idle) { Text("Save author") }
        HorizontalDivider()
        Text("HTTPS access token for private repositories and push", style = MaterialTheme.typography.labelLarge)
        Text(if (state.hasGitToken) "A token is saved for user \"${state.gitUser.ifEmpty { "token" }}\"." else "No token saved. Public clones work without one.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("Personal access token") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { model.saveGitToken(user, token); token = "" }, enabled = idle && token.isNotBlank()) { Text("Save token") }
            if (state.hasGitToken) OutlinedButton(onClick = model::clearGitToken, enabled = idle) { Text("Remove token") }
        }
    }
}
