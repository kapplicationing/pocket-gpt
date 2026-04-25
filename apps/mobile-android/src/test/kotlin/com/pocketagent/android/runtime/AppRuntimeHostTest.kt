package com.pocketagent.android.runtime

import android.content.Context
import android.content.ContextWrapper
import com.pocketagent.core.model.ModelSpecProvider
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class AppRuntimeHostTest {
    @Test
    fun `resolve foreground runtime services uses application-owned host when available`() {
        val context = TestHostOwnerContext(
            appRuntimeHost = TestAppRuntimeHost(
                foregroundRuntimeServices = HostOwnedForegroundRuntimeServices,
            ),
        )

        val resolved = resolveAppForegroundRuntimeServices(context)

        assertSame(HostOwnedForegroundRuntimeServices, resolved)
    }

    @Test
    fun `resolve foreground runtime services exposes host-owned model spec provider`() {
        val context = TestHostOwnerContext(
            appRuntimeHost = TestAppRuntimeHost(
                foregroundRuntimeServices = HostOwnedForegroundRuntimeServices,
            ),
        )

        val resolved = resolveAppForegroundRuntimeServices(context)

        assertSame(HostOwnedModelSpecProvider, resolved.modelSpecProvider)
    }

    @Test
    fun `resolve foreground runtime services requires an application-owned host`() {
        val error = assertFailsWith<IllegalStateException> {
            resolveAppForegroundRuntimeServices(TestPlainContext())
        }

        kotlin.test.assertTrue(error.message.orEmpty().contains("AppRuntimeHostOwner"))
    }
}

private object HostOwnedForegroundRuntimeServices : AppForegroundRuntimeServices {
    override val runtimeTuning: AndroidRuntimeTuningStore
        get() = throw AssertionError("Not used in host resolution tests")
    override val runtimeGateway: ChatRuntimeService
        get() = throw AssertionError("Not used in host resolution tests")
    override val provisioningGateway: ProvisioningGateway
        get() = throw AssertionError("Not used in host resolution tests")
    override val eligibilitySignalsProvider: ModelEligibilitySignalsProvider
        get() = throw AssertionError("Not used in host resolution tests")
    override val presetBackingStore: PresetBackingStore
        get() = throw AssertionError("Not used in host resolution tests")
    override val modelSpecProvider: ModelSpecProvider
        get() = HostOwnedModelSpecProvider
}

private object HostOwnedModelSpecProvider : ModelSpecProvider {
    override fun allSpecs() = emptyList<com.pocketagent.core.model.NormalizedModelSpec>()
}

private class TestAppRuntimeHost(
    override val foregroundRuntimeServices: AppForegroundRuntimeServices,
) : AppRuntimeHost

private class TestHostOwnerContext(
    override val appRuntimeHost: AppRuntimeHost,
) : ContextWrapper(null), AppRuntimeHostOwner {
    override fun getApplicationContext(): Context = this
}

private class TestPlainContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
}
