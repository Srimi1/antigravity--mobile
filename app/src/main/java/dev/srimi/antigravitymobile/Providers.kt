package dev.srimi.antigravitymobile

enum class ProviderId(val label: String) { CHATGPT("ChatGPT"), CLAUDE("Claude"), GOOGLE("Google") }

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
            "or subscription limits. No approved route exists for this app, so no Claude login is offered and no API key fallback is used.")
    val google = AccountState(ProviderId.GOOGLE, AccountStatus.BLOCKED,
        "Google documents Antigravity subscription access only in its own desktop apps, and its SDK uses API keys or " +
            "Google Cloud credentials. No supported way for a native third-party Android app to use a Google subscription was found.")
}
