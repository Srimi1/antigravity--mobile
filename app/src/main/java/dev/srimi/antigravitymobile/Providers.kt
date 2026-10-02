package dev.srimi.antigravitymobile

enum class ProviderId(val label: String) {
    CHATGPT("ChatGPT"), GEMINI("Gemini (Google AI Studio key)"), CLAUDE_KEY("Claude (Anthropic API key)"),
    CLAUDE("Claude Pro/Max subscription"), GOOGLE("Google AI subscription"),
    /** One of the free/trial providers or the custom endpoint in [dev.srimi.antigravitymobile.providers.CompatProviders]. */
    OPENAI_COMPAT("Free & trial providers")
}

/**
 * VERIFIED: a subscription response completed on this install. CONNECTED: signed in, not yet proven.
 * EXPIRED: needs renewal or reconnection. DISCONNECTED: no credentials. BLOCKED: this adapter has no supported route.
 */
enum class AccountStatus { VERIFIED, CONNECTED, EXPIRED, DISCONNECTED, BLOCKED }
data class AccountState(val provider: ProviderId, val status: AccountStatus, val detail: String) {
    val usable: Boolean get() = status == AccountStatus.VERIFIED || status == AccountStatus.CONNECTED || status == AccountStatus.EXPIRED
}

object ProviderPolicy {
    val claude = AccountState(ProviderId.CLAUDE, AccountStatus.BLOCKED,
        "Anthropic's documentation requires prior approval before a third-party product offers claude.ai sign-in " +
            "or subscription limits. No approved route exists for this app. Use Claude with your own Anthropic API key above instead (billed per use).")
    val google = AccountState(ProviderId.GOOGLE, AccountStatus.BLOCKED,
        "Direct Google subscription sign-in is unavailable in this native adapter. For Gemini with your Google account, " +
            "install the official Antigravity CLI in Build → Linux setup and run agy in its terminal. " +
            "Agent chat requires this phone's CLI sandbox verification. AI Studio API-key access is available separately above.")
}
