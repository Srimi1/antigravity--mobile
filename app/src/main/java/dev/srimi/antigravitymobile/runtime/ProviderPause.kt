package dev.srimi.antigravitymobile.runtime

import dev.srimi.antigravitymobile.providers.ProviderFailure

/** Consume Lane B's classification; never persist raw exception messages, URLs, headers or credentials. */
fun providerPause(error: Throwable): RuntimePause {
    val failure = generateSequence(error) { it.cause }.filterIsInstance<ProviderFailure>().firstOrNull()
        ?: return RuntimePause("the provider reply did not complete", "Check network and selected provider in Accounts, then retry this provider.")
    val recovery = failure.diagnostic?.recovery ?: when (failure) {
        is ProviderFailure.AuthInvalid -> "Update this provider's sign-in or key in Accounts, then retry this provider."
        is ProviderFailure.QuotaExhausted -> "Check this account's allowance or wait for its reset, then retry this provider."
        is ProviderFailure.TrialExpired -> "Check the promotion's eligibility. Select another provider manually if it has ended."
        is ProviderFailure.PricingChanged -> "Check current pricing in Accounts. Paid access requires explicit selection."
        is ProviderFailure.Rejected -> "Review the request and this provider's restrictions before retrying."
        else -> "Check the network diagnosis in Accounts, then retry this provider."
    }
    return RuntimePause(failure.reason, recovery)
}
