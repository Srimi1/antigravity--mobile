package dev.srimi.antigravitymobile.providers

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.srimi.antigravitymobile.ProviderId
import dev.srimi.antigravitymobile.SectionCard
import dev.srimi.antigravitymobile.StatusChip
import dev.srimi.antigravitymobile.container
import dev.srimi.antigravitymobile.friendly
import dev.srimi.antigravitymobile.network.DiagnosticDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

data class CompatRow(
    val entry: ProviderEntry,
    val hasKey: Boolean,
    val model: String?,
    val planConfirmed: Boolean,
    val usageToday: UsageTotals?,
)

data class CompatState(
    val rows: List<CompatRow> = emptyList(),
    val selected: String? = null,
    val freeOnly: Boolean = true,
    val agentUsesCompat: Boolean = false,
    /** The app routes ProviderId.OPENAI_COMPAT to this adapter (integration present). */
    val agentRoutingAvailable: Boolean = false,
    val expanded: String? = null,
    val models: Map<String, List<CompatModel>> = emptyMap(),
    val cloudflareAccount: String = "",
    val customUrl: String = "",
    val customName: String = "",
    val busy: String? = null,
    val message: String? = null,
    val diagnostic: DiagnosticReport? = null,
)

class CompatAccountsViewModel(application: Application) : AndroidViewModel(application) {
    private val providers = CompatProviders.shared(application)
    private val services = application.container
    private val mutable = MutableStateFlow(CompatState())
    val state: StateFlow<CompatState> = mutable.asStateFlow()
    private var job: Job? = null

    init { refresh() }

    fun refresh() { viewModelScope.launch {
        val dayStart = System.currentTimeMillis().let { it - it % 86_400_000 }
        val rows = withContext(Dispatchers.IO) { providers.entries().map { e ->
            val id = e.descriptor.id
            CompatRow(e, providers.hasKey(id), providers.model(id), providers.planConfirmed(id),
                ProviderStores.usage.totals(id, null, dayStart).takeIf { it.requests > 0 })
        } }
        val routed = runCatching { services.agentModel(ProviderId.OPENAI_COMPAT) is CompatProviders }.getOrDefault(false)
        mutable.update { it.copy(rows = rows, selected = providers.selected, freeOnly = providers.freeOnly,
            agentUsesCompat = services.agentProvider == ProviderId.OPENAI_COMPAT, agentRoutingAvailable = routed,
            cloudflareAccount = providers.cloudflareAccount, customUrl = providers.customUrl, customName = providers.customName,
            models = it.models + rows.associate { r -> r.entry.descriptor.id to (it.models[r.entry.descriptor.id] ?: providers.cachedModels(r.entry.descriptor.id)) }) }
    } }

    private fun run(label: String, block: suspend () -> String) {
        if (state.value.busy != null) return
        mutable.update { it.copy(busy = label, message = null) }
        job = viewModelScope.launch {
            val message = try { block() } catch (_: CancellationException) { "$label stopped" } catch (error: Exception) {
                (error as? ProviderFailure)?.diagnostic?.let { report -> mutable.update { it.copy(diagnostic = report) } }
                "$label failed: ${friendly(error)}"
            }
            mutable.update { it.copy(busy = null, message = message) }
            refresh()
        }
    }
    fun cancel() { providers.cancel(); job?.cancel() }
    fun dismissMessage() = mutable.update { it.copy(message = null) }
    fun expand(id: String?) = mutable.update { it.copy(expanded = id) }

    fun saveKey(id: String, key: String) = run("Check key") {
        val models = providers.saveKey(id, key)
        mutable.update { it.copy(models = it.models + (id to models)) }
        "Key accepted and saved in Keystore-encrypted storage. ${models.size} model(s) listed."
    }
    fun removeKey(id: String) = run("Remove key") { providers.removeKey(id); "Key removed from this phone" }
    fun loadModels(id: String) = run("Load models") {
        val models = providers.listModels(id); mutable.update { it.copy(models = it.models + (id to models)) }; "${models.size} model(s) listed"
    }
    fun chooseModel(id: String, model: String) { providers.setModel(id, model); providers.selected = id; refresh() }
    fun verify(id: String, model: String) = run("Tool-calling check") {
        if (providers.verifyToolCalling(id, model)) "$model called the test tool correctly; it can be used for coding."
        else "$model did not call the test tool correctly; it stays disabled for coding."
    }
    fun setPlan(id: String, value: Boolean) { providers.setPlanConfirmed(id, value); refresh() }
    fun setFreeOnly(value: Boolean) { providers.freeOnly = value; refresh() }
    fun setCloudflareAccount(value: String) { providers.cloudflareAccount = value; refresh() }
    fun setCustom(url: String, name: String) = run("Save endpoint") { providers.setCustom(url, name); "Custom endpoint saved. Add its key and choose a model." }
    fun useForAgent() { if (state.value.agentRoutingAvailable) { services.agentProvider = ProviderId.OPENAI_COMPAT; refresh() } }
}

@Composable fun CompatProvidersSection(notify: (String) -> Unit) {
    val model: CompatAccountsViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.message) { state.message?.let { notify(it); model.dismissMessage() } }
    val idle = state.busy == null
    SectionCard("Free & trial providers") {
        Text("You choose one provider and one model. Nothing switches providers or falls back to paid access on its own. " +
            "Allowances below come from each provider's official page; \"unknown\" means the provider does not publish it.",
            style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(state.freeOnly, model::setFreeOnly, enabled = idle)
            Spacer(Modifier.width(8.dp))
            Text(if (state.freeOnly) "Free mode: only zero-cost models or accounts that stop instead of billing" else "Free mode off: paid models and endpoints may bill you")
        }
        state.busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(it); TextButton(onClick = model::cancel) { Text("Stop") } } }
        state.diagnostic?.let { DiagnosticDetails(it) }
        if (state.selected != null) {
            val selected = state.rows.firstOrNull { it.entry.descriptor.id == state.selected }
            Text("Selected: ${selected?.entry?.descriptor?.displayName ?: state.selected} · ${selected?.model ?: "no model"}", style = MaterialTheme.typography.labelLarge)
            when {
                state.agentUsesCompat -> Text("Agent uses this provider.")
                state.agentRoutingAvailable -> Button(onClick = model::useForAgent, enabled = idle) { Text("Use for Agent") }
                else -> Text("Agent support for these providers arrives with the next runtime update; until then they can be set up and tested here.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        state.rows.forEach { row -> ProviderRow(row, state, model, idle) }
        CustomEndpoint(state, model, idle)
    }
}

@Composable private fun ProviderRow(row: CompatRow, state: CompatState, model: CompatAccountsViewModel, idle: Boolean) {
    val d = row.entry.descriptor
    val id = d.id
    val open = state.expanded == id
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.displayName, style = MaterialTheme.typography.titleSmall)
            Text(allowanceLine(d), style = MaterialTheme.typography.bodySmall)
        }
        StatusChip(d.allowanceClass.name.lowercase(), when (d.allowanceClass) { AllowanceClass.Free -> "PASSED"; AllowanceClass.Trial, AllowanceClass.AccountDependent -> "UNVERIFIED"; AllowanceClass.Paid -> "FAILED" })
        TextButton(onClick = { model.expand(if (open) null else id) }) { Text(if (open) "Close" else "Set up") }
    }
    if (!open) return
    val context = LocalContext.current
    var key by remember(id) { mutableStateOf("") }
    Text(detailLines(d), style = MaterialTheme.typography.bodySmall)
    Text("Data: ${row.entry.dataUse}", style = MaterialTheme.typography.bodySmall)
    Text("Usage on this phone today: " + (row.usageToday?.let { u -> "${u.requests} request(s)" +
        (u.inputTokens?.let { ", $it input / ${u.outputTokens ?: "unknown"} output tokens" } ?: ", tokens not reported") } ?: "none recorded") +
        ". Remaining quota: unknown (the provider does not report it to this app).", style = MaterialTheme.typography.bodySmall)
    row.entry.quirks.keyPage?.let { page -> OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page))) }) { Text("Get a key") } }
    if (row.entry.quirks.needsAccountId) {
        var account by remember(state.cloudflareAccount) { mutableStateOf(state.cloudflareAccount) }
        OutlinedTextField(account, { account = it }, label = { Text("Cloudflare account ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { model.setCloudflareAccount(account) }, enabled = idle) { Text("Save account ID") }
    }
    row.entry.needsPlanConfirmation?.let { text ->
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(row.planConfirmed, { model.setPlan(id, it) }); Text(text, style = MaterialTheme.typography.bodySmall) }
    }
    OutlinedTextField(key, { key = it }, label = { Text(if (row.hasKey) "Replace API key" else if (row.entry.quirks.keyOptional) "API key (optional)" else "API key") },
        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { model.saveKey(id, key); key = "" }, enabled = idle && key.isNotBlank()) { Text("Check and save") }
        if (row.hasKey || row.entry.quirks.keyOptional) OutlinedButton(onClick = { model.loadModels(id) }, enabled = idle) { Text("Models") }
        if (row.hasKey) OutlinedButton(onClick = { model.removeKey(id) }, enabled = idle) { Text("Remove key") }
    }
    val models = state.models[id].orEmpty()
    val providers = CompatProviders.shared(LocalContext.current.applicationContext)
    models.forEach { m ->
        val free = OpenAiCompatWire.isFree(row.entry.freeRule, m, m.id)
        val verified = providers.verifiedAt(id, m.id)
        val failed = providers.failedAt(id, m.id)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = row.model == m.id && state.selected == id, onClick = { model.chooseModel(id, m.id) }, enabled = idle)
            Column(Modifier.weight(1f)) {
                Text(m.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text(listOfNotNull(if (free) "free" else "may bill",
                    m.advertisesTools?.let { if (it) "lists tool support" else "no tool support listed" },
                    verified?.let { "tools verified ${DateFormat.getDateInstance().format(Date(it))}" },
                    failed?.let { "tool check failed" }).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            }
            if (verified == null) TextButton(onClick = { model.verify(id, m.id) }, enabled = idle && (free || !state.freeOnly)) { Text("Verify") }
        }
    }
}

@Composable private fun CustomEndpoint(state: CompatState, model: CompatAccountsViewModel, idle: Boolean) {
    var url by remember(state.customUrl) { mutableStateOf(state.customUrl) }
    var name by remember(state.customName) { mutableStateOf(state.customName) }
    HorizontalDivider()
    Text("Custom OpenAI-compatible endpoint", style = MaterialTheme.typography.titleSmall)
    Text("For example an OmniRoute server you run. Its key is stored for this URL only; changing the URL erases it. " +
        "It is treated as possibly paid, so free mode blocks it.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(url, { url = it }, label = { Text("Base URL (https://…/v1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = { model.setCustom(url, name) }, enabled = idle && url.isNotBlank()) { Text("Save endpoint") }
}

private fun allowanceLine(d: ProviderDescriptor): String = when {
    d.allowanceAmount != null && d.allowanceUnits != null -> "${d.allowanceAmount} ${d.allowanceUnits}"
    d.allowanceUnits != null -> "${d.allowanceUnits}: amount unknown"
    else -> "Allowance: unknown"
}

private fun detailLines(d: ProviderDescriptor): String = listOf(
    "Resets: ${d.reset ?: "unknown"}",
    "Expires: ${d.expiresAt?.let { DateFormat.getDateInstance().format(Date(it)) } ?: if (d.allowanceClass == AllowanceClass.Trial) "unknown (per account)" else "no documented expiry"}",
    "Billing: " + when (d.billing) { BillingRequirement.None -> "none required"; BillingRequirement.CardOnFile -> "card on file required"
        BillingRequirement.PrepaidCredit -> "prepaid credit"; BillingRequirement.PaidPlan -> "paid plan"; BillingRequirement.Unknown -> "unknown" },
    "Eligibility: ${d.eligibility}",
    "Source: ${d.sourceUrl}" + (d.verifiedAt?.let { " (checked ${DateFormat.getDateInstance().format(Date(it))})" } ?: " (not verified)"),
).joinToString("\n")
