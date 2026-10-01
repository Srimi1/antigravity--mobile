package dev.srimi.antigravitymobile.providers

/**
 * Free mode admits only zero-cost routes or accounts with a documented hard stop (requests fail instead of
 * billing). Paid routes require the user to turn free mode off themselves. Nothing here ever switches provider.
 */
object FreeModePolicy {
    /** Returns null when the request may be sent, otherwise the failure to raise instead of sending it. */
    fun check(entry: ProviderEntry, modelId: String, catalog: CompatModel?, freeOnly: Boolean, planConfirmed: Boolean,
              now: Long = System.currentTimeMillis()): ProviderFailure? {
        val d = entry.descriptor
        d.expiresAt?.let { if (now >= it) return ProviderFailure.TrialExpired("${d.displayName}'s trial or promotion has ended") }
        if (!freeOnly) return null
        return when {
            d.allowanceClass == AllowanceClass.Paid -> ProviderFailure.Rejected(
                "Free mode is on and ${d.displayName} may bill you. Turn off free mode in Accounts to use it.")
            d.allowanceClass == AllowanceClass.AccountDependent && !planConfirmed -> ProviderFailure.Rejected(
                "Free mode is on. Confirm in Accounts that your ${d.displayName} account cannot be billed (${entry.needsPlanConfirmation ?: "free plan"}).")
            OpenAiCompatWire.isFree(entry.freeRule, catalog, modelId) -> null
            entry.freeRule is FreeRule.ZeroPriced && catalog != null -> ProviderFailure.PricingChanged(
                "$modelId on ${d.displayName} now has a price; free mode stopped before sending.")
            entry.freeRule is FreeRule.ZeroPriced -> ProviderFailure.PricingChanged(
                "$modelId is no longer listed as a free model on ${d.displayName}; free mode stopped before sending.")
            else -> ProviderFailure.Rejected("$modelId is not one of ${d.displayName}'s free models. Choose a free model or turn off free mode.")
        }
    }
}
