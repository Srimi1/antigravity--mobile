package dev.srimi.antigravitymobile

enum class ProviderId(val label: String) {
    CHATGPT("ChatGPT"), GEMINI("Gemini (Google AI Studio key)"), CLAUDE_KEY("Claude (Anthropic API key)"),
    CLAUDE("Claude Pro/Max subscription"), GOOGLE("Google AI subscription")
}

/**
 * VERIFIED: a subscription response completed on this install. CONNECTED: signed in, not yet proven.
 * EXPIRED: needs renewal or reconnection. DISCONNECTED: no credentials. BLOCKED: no supported route exists.
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
        "Google does not allow third-party apps to use a Google AI Pro/Ultra subscription login, and has suspended accounts " +
            "that did. Use Gemini with your own Google AI Studio API key above instead.")
}
