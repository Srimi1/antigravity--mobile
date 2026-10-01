package dev.srimi.antigravitymobile.runtime

import dev.srimi.antigravitymobile.ProviderModelRecord
import dev.srimi.antigravitymobile.ProviderUsageDao
import dev.srimi.antigravitymobile.ProviderUsageRecord
import dev.srimi.antigravitymobile.providers.*

/** Room implementation of Lane B's frozen contract. Null counts remain unknown. No credentials. */
class RoomProviderUsageStore(private val dao: ProviderUsageDao) : ProviderUsageStore {
    override suspend fun recordModel(verification: ModelVerification) {
        require(verification.providerId.isNotBlank() && verification.modelId.isNotBlank())
        dao.saveModel(ProviderModelRecord(verification.providerId, verification.modelId,
            verification.toolCallingVerified, verification.verifiedAt))
    }
    override suspend fun models(providerId: String) = dao.models(providerId).map {
        ModelVerification(it.providerId, it.modelId, it.toolCallingVerified, it.verifiedAt)
    }
    override suspend fun record(usage: UsageRecord) {
        require(usage.providerId.isNotBlank() && usage.modelId.isNotBlank() && usage.requests >= 0)
        require(usage.inputTokens == null || usage.inputTokens >= 0)
        require(usage.outputTokens == null || usage.outputTokens >= 0)
        dao.recordUsage(ProviderUsageRecord(providerId = usage.providerId, modelId = usage.modelId, taskId = usage.taskId,
            inputTokens = usage.inputTokens, outputTokens = usage.outputTokens, requests = usage.requests, recordedAt = usage.recordedAt))
    }
    override suspend fun totals(providerId: String, modelId: String?, since: Long): UsageTotals {
        val rows = dao.usage(providerId, since).filter { modelId == null || it.modelId == modelId }
        fun sum(pick: (ProviderUsageRecord) -> Long?): Long? = rows.mapNotNull(pick).takeIf { it.isNotEmpty() }
            ?.fold(0L) { total, value -> Math.addExact(total, value) }
        return UsageTotals(rows.fold(0L) { total, row -> Math.addExact(total, row.requests.toLong()) },
            sum { it.inputTokens }, sum { it.outputTokens }, since)
    }
}
