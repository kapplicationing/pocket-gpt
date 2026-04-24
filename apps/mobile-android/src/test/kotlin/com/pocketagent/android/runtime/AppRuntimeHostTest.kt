package com.pocketagent.android.runtime

import android.content.Context
import android.content.ContextWrapper
import kotlin.test.Test
import kotlin.test.assertSame

class AppRuntimeHostTest {
    @Test
    fun `resolve app runtime access uses application-owned host when available`() {
        val context = TestHostOwnerContext(
            appRuntimeHost = TestAppRuntimeHost(
                runtimeAccess = HostOwnedAppRuntimeAccess,
                foregroundRuntimeServices = HostOwnedForegroundRuntimeServices,
            ),
        )

        val resolved = resolveAppRuntimeAccess(context)

        assertSame(HostOwnedAppRuntimeAccess, resolved)
    }

    @Test
    fun `resolve foreground runtime services uses application-owned host when available`() {
        val context = TestHostOwnerContext(
            appRuntimeHost = TestAppRuntimeHost(
                runtimeAccess = HostOwnedAppRuntimeAccess,
                foregroundRuntimeServices = HostOwnedForegroundRuntimeServices,
            ),
        )

        val resolved = resolveAppForegroundRuntimeServices(context)

        assertSame(HostOwnedForegroundRuntimeServices, resolved)
    }

    @Test
    fun `resolve app runtime access falls back to singleton compatibility host`() {
        val resolved = resolveAppRuntimeAccess(TestPlainContext())

        assertSame(CompatibilityAppRuntimeAccess, resolved)
    }

    @Test
    fun `resolve foreground runtime services falls back to cached compatibility host`() {
        val context = TestPlainContext()

        val first = resolveAppForegroundRuntimeServices(context)
        val second = resolveAppForegroundRuntimeServices(context)

        assertSame(first, second)
    }
}

private object HostOwnedAppRuntimeAccess : AppRuntimeAccess by CompatibilityAppRuntimeAccess

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
}

private class TestAppRuntimeHost(
    override val runtimeAccess: AppRuntimeAccess,
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
