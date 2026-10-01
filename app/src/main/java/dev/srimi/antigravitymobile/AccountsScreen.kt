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
import dev.srimi.antigravitymobile.network.DiagnosticDetails

@Composable fun AccountsScreen(model: AccountsViewModel, notify: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.message) { state.message?.let { notify(it); model.dismissMessage() } }
    LaunchedEffect(Unit) { model.refresh() }
    var disconnecting by remember { mutableStateOf(false) }
    val idle = state.busy == null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Accounts", style = MaterialTheme.typography.headlineSmall)
        Text("Credentials stay in Keystore-encrypted storage on this phone. Nothing switches providers on its own: the Agent uses only the account you choose.",
            style = MaterialTheme.typography.bodySmall)
        state.busy?.let { BusyRow(it, onCancel = model::cancel) }

        state.accounts.forEach { account ->
            SectionCard(account.provider.label) {
                StatusChip(account.status.name.lowercase(), account.status.name)
                Text(account.detail)
                if (account.provider in setOf(ProviderId.CHATGPT, ProviderId.GEMINI, ProviderId.CLAUDE_KEY) && account.status != AccountStatus.DISCONNECTED)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.agentProvider == account.provider, onClick = { model.useForAgent(account.provider) }, enabled = idle)
                        Text(if (state.agentProvider == account.provider) "Agent uses this account" else "Use for Agent")
                    }
                if (account.provider == ProviderId.GEMINI) GeminiSettings(state, model, idle, account)
                if (account.provider == ProviderId.CLAUDE_KEY) ClaudeSettings(state, model, idle, account)
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = state.preferredModel == null, onClick = { model.chooseModel(null) }); Text("Automatic")
                            }
                            state.models.forEach { item ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = state.preferredModel == item.slug, onClick = { model.chooseModel(item.slug) })
                                    Column {
                                        Text(item.displayName)
                                        Text(item.slug + if (item.listed) "" else " · not recommended for this sign-in; may be refused",
                                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Text("These are all the models OpenAI returns for apps using Sign in with ChatGPT. Models missing here " +
                                "(even if you see them in the ChatGPT app) are not offered to third-party apps, and Antigravity cannot add them.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (state.output.isNotBlank()) Text("Response: ${state.output}", fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                    Text("Sign-in uses OpenAI's documented Sign in with ChatGPT flow in your browser. Eligibility for this app is " +
                        "confirmed only when a test request completes.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        dev.srimi.antigravitymobile.providers.CompatProvidersSection(notify)
        NetworkCheck(state, model, idle)
        GitSettings(state, model, idle)
    }
    if (disconnecting) ConfirmDialog("Disconnect ChatGPT?", "Local tokens are erased and the app asks OpenAI to revoke the session.",
        "Disconnect", onDismiss = { disconnecting = false }) { model.disconnect() }
}

@Composable private fun NetworkCheck(state: AccountsState, model: AccountsViewModel, idle: Boolean) {
    var host by remember { mutableStateOf("") }
    SectionCard("Network check") {
        Text("Checks whether this phone can reach a provider on the current network (DNS, connection and secure TLS). " +
            "No account details are sent. Run it on Wi-Fi and on mobile data to compare.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("OpenAI" to "api.openai.com", "Google" to "generativelanguage.googleapis.com", "Anthropic" to "api.anthropic.com").forEach { (label, target) ->
                OutlinedButton(onClick = { model.checkNetwork(target) }, enabled = idle) { Text(label) }
            }
        }
        OutlinedTextField(host, { host = it }, label = { Text("Other hostname or URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { model.checkNetwork(host) }, enabled = idle && host.isNotBlank()) { Text("Check") }
        state.diagnostic?.let { report ->
            HorizontalDivider()
            StatusChip(if (report.failedStage == null) "reachable" else "problem", if (report.failedStage == null) "PASSED" else "FAILED")
            DiagnosticDetails(report)
            TextButton(onClick = model::dismissDiagnostic) { Text("Clear") }
        }
    }
}

@Composable private fun GeminiSettings(state: AccountsState, model: AccountsViewModel, idle: Boolean, account: AccountState) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf(false) }
    if (account.status == AccountStatus.DISCONNECTED) {
        Text("1. Open Google AI Studio and create an API key (free tier available). 2. Paste it here. " +
            "This uses the Gemini API, not your Google AI Pro/Ultra subscription; Google does not allow third-party apps to use that login.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }) {
            Text("Get a key in AI Studio")
        }
    }
    OutlinedTextField(key, { key = it }, label = { Text(if (account.status == AccountStatus.DISCONNECTED) "Gemini API key" else "Replace API key") },
        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { model.saveGeminiKey(key); key = "" }, enabled = idle && key.isNotBlank()) { Text("Check and save") }
        if (account.status != AccountStatus.DISCONNECTED) OutlinedButton(onClick = model::verifyGemini, enabled = idle) { Text("Test request") }
    }
    if (account.status != AccountStatus.DISCONNECTED) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = model::loadGeminiModels, enabled = idle) { Text("Models") }
            OutlinedButton(onClick = { removing = true }, enabled = idle) { Text("Remove key") }
        }
        Text("Model: ${state.geminiModel ?: "automatic (newest stable Flash)"}", style = MaterialTheme.typography.bodySmall)
        if (state.geminiModels.isNotEmpty()) Column {
            listOf<String?>(null).plus(state.geminiModels).forEach { slug ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.geminiModel == slug, onClick = { model.chooseGeminiModel(slug) })
                    Text(slug ?: "Automatic", fontFamily = if (slug == null) null else FontFamily.Monospace)
                }
            }
        }
        if (state.geminiOutput.isNotBlank()) Text("Response: ${state.geminiOutput}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
    Text("Free-tier keys: Google may use prompts and project files you send to improve its products, and limits are low. " +
        "Usage is billed only if you enable billing on the key's Google Cloud project.", style = MaterialTheme.typography.bodySmall)
    if (removing) ConfirmDialog("Remove Gemini key?", "The key is erased from this phone. It stays valid at Google until you delete it in AI Studio.",
        "Remove", onDismiss = { removing = false }) { removing = false; model.removeGeminiKey() }
}

@Composable private fun ClaudeSettings(state: AccountsState, model: AccountsViewModel, idle: Boolean, account: AccountState) {
    var key by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf(false) }
    Text("Paid per use: every Agent message is billed to your Anthropic Console credits, separately from any Claude Pro/Max " +
        "subscription. A coding task can cost from a few cents to a dollar or more. Set a spend limit in the Console.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    if (account.status == AccountStatus.DISCONNECTED) OpenLink("Get a key at console.anthropic.com", "https://console.anthropic.com/settings/keys")
    OutlinedTextField(key, { key = it }, label = { Text(if (account.status == AccountStatus.DISCONNECTED) "Anthropic API key (sk-ant-…)" else "Replace API key") },
        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { model.saveClaudeKey(key); key = "" }, enabled = idle && key.isNotBlank()) { Text("Check and save") }
        if (account.status != AccountStatus.DISCONNECTED) OutlinedButton(onClick = model::verifyClaude, enabled = idle) { Text("Test request") }
    }
    if (account.status != AccountStatus.DISCONNECTED) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = model::loadClaudeModels, enabled = idle) { Text("Models") }
            OutlinedButton(onClick = { removing = true }, enabled = idle) { Text("Remove key") }
        }
        Text("Model: ${state.claudeModel ?: "${ClaudeAdapter.DEFAULT_MODEL} (default)"}", style = MaterialTheme.typography.bodySmall)
        if (state.claudeModels.isNotEmpty()) Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.claudeModel == null, onClick = { model.chooseClaudeModel(null) }); Text("Default (${ClaudeAdapter.DEFAULT_MODEL})")
            }
            state.claudeModels.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.claudeModel == item.id, onClick = { model.chooseClaudeModel(item.id) })
                    Column { Text(item.displayName); Text(item.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (state.claudeOutput.isNotBlank()) Text("Response: ${state.claudeOutput}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
    if (removing) ConfirmDialog("Remove Claude key?", "The key is erased from this phone. It stays valid at Anthropic until you delete it in the Console.",
        "Remove", onDismiss = { removing = false }) { removing = false; model.removeClaudeKey() }
}

@Composable private fun GitSettings(state: AccountsState, model: AccountsViewModel, idle: Boolean) {
    var name by remember(state.authorName) { mutableStateOf(state.authorName) }
    var email by remember(state.authorEmail) { mutableStateOf(state.authorEmail) }
    var user by remember(state.gitUser) { mutableStateOf(state.gitUser) }
    var token by remember { mutableStateOf("") }
    var githubToken by remember { mutableStateOf("") }
    SectionCard("GitHub") {
        if (state.hasGitToken) {
            StatusChip("connected", "CONNECTED")
            Text("Signed in as ${state.gitUser.ifEmpty { "token" }}. Open repositories with Projects → From GitHub; push, branches and pull requests are in each project's Git tab.")
        } else {
            Text("1. Create a token on GitHub (the page opens with the needed \"repo\" and \"workflow\" permissions). 2. Copy it and paste it here.",
                style = MaterialTheme.typography.bodySmall)
            OpenLink("Create token on GitHub", GitHubWire.TOKEN_PAGE)
        }
        OutlinedTextField(githubToken, { githubToken = it }, label = { Text(if (state.hasGitToken) "Replace token" else "GitHub token") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.signInGitHub(githubToken); githubToken = "" }, enabled = idle && githubToken.isNotBlank()) { Text("Sign in") }
            if (state.hasGitToken) OutlinedButton(onClick = model::clearGitToken, enabled = idle) { Text("Sign out") }
        }
        Text("The token stays in Keystore-encrypted storage and is sent only to GitHub. Revoke it any time in GitHub settings.",
            style = MaterialTheme.typography.bodySmall)
    }
    SectionCard("Git") {
        Text("Commit author", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { model.saveAuthor(name, email) }, enabled = idle) { Text("Save author") }
        HorizontalDivider()
        Text("Other HTTPS Git hosts (advanced): username and token", style = MaterialTheme.typography.labelLarge)
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
