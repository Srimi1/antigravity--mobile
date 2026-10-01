package dev.srimi.antigravitymobile

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dev.srimi.antigravitymobile.runtime.NativeAgentTaskRunner
import kotlinx.coroutines.flow.flow

/** Test APK only. Production APK contains no fixture providers. Real services still own the task. */
class RuntimeInstrumentationRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, RuntimeTestApplication::class.java.name, context)
}

object RuntimeFixture {
    const val PROVIDER = "RUNTIME_DEVICE_FIXTURE"
    @Volatile var model: AgentModel? = null
}

class RuntimeTestApplication : AntigravityApp() {
    override val container by lazy { AppContainer(this) { services, app -> NativeAgentTaskRunner(services, app,
        modelFor = { provider ->
            if (provider == RuntimeFixture.PROVIDER) RuntimeFixture.model ?: object : AgentModel {
                override val providerId = "missing-device-fixture"
                override fun cancel() {}
                override fun streamAgentTurn(request: AgentRequest) = flow<ProviderEvent> { error("Device fixture missing") }
            } else services.agentModel(ProviderId.valueOf(provider))
        }) } }
}
