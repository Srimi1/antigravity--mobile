package dev.srimi.antigravitymobile.providers

import java.time.LocalDate
import java.time.ZoneOffset

/** Which models of a provider cost nothing (in free mode only these may be used). */
sealed interface FreeRule {
    /** Every model on this account is covered by the documented free/trial allowance. */
    data object AllModels : FreeRule
    /** Only models whose catalog price is zero (and, when [suffix] is set, whose id ends with it). */
    data class ZeroPriced(val suffix: String? = null) : FreeRule
    /** Only these documented model ids. */
    data class Listed(val ids: Set<String>) : FreeRule
    /** Ids matching [pattern] (documented promotions whose list changes). */
    data class Matching(val pattern: Regex) : FreeRule
}

/** OpenAI Chat Completions differences that matter to the shared adapter. */
data class CompatQuirks(
    val parallelToolCallsParam: Boolean = true,
    val streamUsage: Boolean = true,
    val needsAccountId: Boolean = false,
    val keyOptional: Boolean = false,
    val keyPage: String? = null,
)

/**
 * A registry entry: the documented descriptor plus how to use it. [dataUse] is shown to the user before they
 * add a key, because several free tiers may use prompts for training or log them.
 */
data class ProviderEntry(
    val descriptor: ProviderDescriptor,
    val freeRule: FreeRule,
    val quirks: CompatQuirks,
    val dataUse: String,
    /** True when the allowance applies only on a specific account plan the app cannot check (user confirms). */
    val needsPlanConfirmation: String? = null,
)

/**
 * Providers checked against their official pages on [CHECKED]. Any field the official source does not state is
 * null (shown as unknown). OmniRoute's catalog was used only to find candidates. Retired: GitHub Models
 * (fully retired 30 July 2026, https://docs.github.com/en/github-models) is deliberately absent.
 */
object ProviderRegistry {
    val CHECKED: Long = LocalDate.of(2026, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    const val CUSTOM = "custom"

    val entries: List<ProviderEntry> = listOf(
        ProviderEntry(ProviderDescriptor("groq", "Groq", "https://api.groq.com/openai/v1", AuthType.ApiKey, AllowanceClass.Free,
            "requests per day per model (most text models; 30/min, 8K tokens/min, 200K tokens/day)", 1_000, "daily (time not stated)", null,
            BillingRequirement.Unknown, "Any Groq account on the Free plan", "https://console.groq.com/docs/rate-limits", CHECKED),
            FreeRule.AllModels, CompatQuirks(keyPage = "https://console.groq.com/keys"),
            "Groq's terms apply; no training use is stated on the rate-limit page."),
        ProviderEntry(ProviderDescriptor("mistral", "Mistral (Experiment plan)", "https://api.mistral.ai/v1", AuthType.ApiKey, AllowanceClass.Free,
            null, null, null, null, BillingRequirement.Unknown, "Free mode: included monthly usage within the limits on your console's Limits page",
            "https://docs.mistral.ai/admin/billing-usage/usage-limits", CHECKED),
            FreeRule.AllModels, CompatQuirks(keyPage = "https://console.mistral.ai/api-keys"),
            "Data use on the free plan is governed by Mistral's terms (not stated on the limits page; check before sending private code). Exact limits are shown only in your Mistral console."),
        ProviderEntry(ProviderDescriptor("openrouter", "OpenRouter (free models)", "https://openrouter.ai/api/v1", AuthType.ApiKey, AllowanceClass.Free,
            "requests per day on :free models (1,000 after $10 of purchased credit; 20/min)", 50, "daily", null,
            BillingRequirement.None, "Any account; a negative balance blocks free models too",
            "https://openrouter.ai/docs/api/reference/limits", CHECKED),
            FreeRule.ZeroPriced(":free"), CompatQuirks(keyPage = "https://openrouter.ai/settings/keys"),
            "Free models are served by third-party providers that may log prompts; check each model's page."),
        ProviderEntry(ProviderDescriptor("cloudflare", "Cloudflare Workers AI", "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1",
            AuthType.ApiKey, AllowanceClass.AccountDependent, "Neurons per day", 10_000, "daily, 00:00 UTC", null,
            BillingRequirement.None, "Workers Free plan stops at the allowance; Workers Paid bills overage",
            "https://developers.cloudflare.com/workers-ai/platform/pricing/", CHECKED),
            FreeRule.AllModels, CompatQuirks(needsAccountId = true, keyPage = "https://dash.cloudflare.com/profile/api-tokens"),
            "Cloudflare's terms apply. Some models (Kimi, GLM, DeepSeek) require a paid billing method.",
            needsPlanConfirmation = "My Cloudflare account is on the Workers Free plan (requests fail instead of billing)"),
        ProviderEntry(ProviderDescriptor("huggingface", "Hugging Face Inference Providers", "https://router.huggingface.co/v1", AuthType.ApiKey,
            AllowanceClass.AccountDependent, "USD of credits per month (free users)", null, "monthly", null,
            BillingRequirement.None, "Free users: \$0.10/month (subject to change); usage beyond it needs purchased credits",
            "https://huggingface.co/docs/inference-providers/pricing", CHECKED),
            FreeRule.AllModels, CompatQuirks(keyPage = "https://huggingface.co/settings/tokens"),
            "Requests are routed to third-party inference providers under their terms.",
            needsPlanConfirmation = "I have not bought Hugging Face credits (requests fail when the monthly credit runs out)"),
        ProviderEntry(ProviderDescriptor("zai", "Z.AI (GLM Flash)", "https://api.z.ai/api/paas/v4", AuthType.ApiKey, AllowanceClass.Free,
            null, null, null, null, BillingRequirement.Unknown, "Any Z.AI account; only the Flash models are free",
            "https://docs.z.ai/guides/overview/pricing", CHECKED),
            FreeRule.Listed(setOf("glm-4.7-flash", "glm-4.5-flash")), CompatQuirks(parallelToolCallsParam = false, streamUsage = false,
                keyPage = "https://z.ai/manage-apikey/apikey-list"),
            "Z.AI's terms apply. Other GLM models are billed; free mode allows only the Flash models."),
        ProviderEntry(ProviderDescriptor("kilo", "Kilo Gateway (free models)", "https://api.kilo.ai/api/gateway", AuthType.ApiKey, AllowanceClass.Free,
            "requests per hour per IP without a key", 200, "hourly", null, BillingRequirement.None,
            "Anonymous or signed-in; free models change over time", "https://kilo.ai/docs/gateway/models-and-providers", CHECKED),
            FreeRule.ZeroPriced(), CompatQuirks(keyOptional = true, keyPage = "https://app.kilo.ai"),
            "Free models may be routed to providers that log prompts and outputs and use them to improve their services. Do not send private code."),
        ProviderEntry(ProviderDescriptor("nvidia", "NVIDIA NIM (API catalog trial)", "https://integrate.api.nvidia.com/v1", AuthType.ApiKey,
            AllowanceClass.Trial, null, null, null, null, BillingRequirement.None,
            "NVIDIA Developer Program; prototyping, research, development and testing only (not production)", "https://docs.api.nvidia.com/nim/docs/product", CHECKED),
            FreeRule.AllModels, CompatQuirks(keyPage = "https://build.nvidia.com/settings/api-keys"),
            "NVIDIA API Trial Terms of Service apply (trial use only)."),
        ProviderEntry(ProviderDescriptor("cohere", "Cohere (evaluation key)", "https://api.cohere.ai/compatibility/v1", AuthType.ApiKey,
            AllowanceClass.Trial, "Chat API calls per month (20/min)", 1_000, "monthly", null, BillingRequirement.None,
            "Evaluation keys, non-production use only", "https://docs.cohere.com/docs/rate-limits", CHECKED),
            FreeRule.AllModels, CompatQuirks(parallelToolCallsParam = false, keyPage = "https://dashboard.cohere.com/api-keys"),
            "Cohere's terms for evaluation keys apply (non-production)."),
        ProviderEntry(ProviderDescriptor("opencode-zen", "OpenCode Zen (free promotions)", "https://opencode.ai/zen/v1", AuthType.ApiKey,
            AllowanceClass.Trial, null, null, null, null, BillingRequirement.Unknown,
            "Promotional free models for a limited time; list changes", "https://opencode.ai/docs/zen", CHECKED),
            FreeRule.Matching(Regex("(?i)(-free$|^big-pickle$)")), CompatQuirks(keyPage = "https://opencode.ai/auth"),
            "Several free promotional models allow the model provider to use your data for training; Nemotron free tiers retain logged sessions."),
        ProviderEntry(ProviderDescriptor("cerebras", "Cerebras (free trial)", "https://api.cerebras.ai/v1", AuthType.ApiKey,
            AllowanceClass.Trial, "USD of trial credit, expiring 30 days after grant (5/min, 1M tokens/day)", 5, null, null,
            BillingRequirement.CardOnFile, "New accounts; verified payment method required; access stops when the trial ends",
            "https://inference-docs.cerebras.ai/support/rate-limits", CHECKED),
            FreeRule.AllModels, CompatQuirks(keyPage = "https://cloud.cerebras.ai"),
            "Cerebras' terms apply. A card is required; your own account's trial status is unverified until a request succeeds."),
    )

    fun entry(id: String): ProviderEntry? = entries.firstOrNull { it.descriptor.id == id }

    /** A user-configured OpenAI-compatible endpoint (for example OmniRoute). Always treated as paid/unknown. */
    fun custom(baseUrl: String, name: String): ProviderEntry = ProviderEntry(
        ProviderDescriptor(CUSTOM, name.ifBlank { "Custom endpoint" }, baseUrl.trimEnd('/'), AuthType.ApiKey, AllowanceClass.Paid,
            null, null, null, null, BillingRequirement.Unknown, "Configured by you", baseUrl, null),
        FreeRule.Listed(emptySet()), CompatQuirks(keyOptional = true),
        "Your endpoint decides where requests go. No automatic routing: only the model you select is used.")

    /** Accepts https URLs, or http only for this phone (127.0.0.1/localhost, e.g. a local OmniRoute). */
    fun validCustomUrl(url: String): Boolean {
        val trimmed = url.trim()
        val host = trimmed.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        return when {
            trimmed.startsWith("https://") -> host.matches(Regex("[a-z0-9.-]{1,253}")) && '.' in host || host == "localhost"
            trimmed.startsWith("http://") -> host == "127.0.0.1" || host == "localhost"
            else -> false
        } && !trimmed.contains('@') && !trimmed.contains('?') && !trimmed.contains('#')
    }
}
