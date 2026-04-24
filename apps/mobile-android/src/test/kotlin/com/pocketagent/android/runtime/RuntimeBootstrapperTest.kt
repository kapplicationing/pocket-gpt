package com.pocketagent.android.runtime

import android.content.Context
import android.content.ContextWrapper
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.MvpRuntimeFacade
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RuntimeBootstrapperTest {
    @Test
    fun `runtime bootstrapper delegates to configured access seam`() {
        val facade = RecordingBootstrapRuntimeFacade()
        val access = RecordingAppRuntimeBootstrapAccess(facade = facade)
        val context = TestBootstrapContext()

        RuntimeBootstrapper.swapAccessForTests(access).use {
            RuntimeBootstrapper.installProductionRuntime(context)

            val resolvedFacade = RuntimeBootstrapper.runtimeFacade(context)

            assertEquals(1, access.installCalls)
            assertSame(context, access.lastInstallContext)
            assertSame(facade, resolvedFacade)
        }
    }
}

private class RecordingAppRuntimeBootstrapAccess(
    private val facade: MvpRuntimeFacade,
) : AppRuntimeBootstrapAccess {
    var installCalls: Int = 0
    var lastInstallContext: Context? = null

    override fun installProductionRuntime(context: Context) {
        installCalls += 1
        lastInstallContext = context
    }

    override fun runtimeFacade(context: Context): MvpRuntimeFacade = facade

    override fun runtimeTuning(context: Context): AndroidRuntimeTuningStore {
        throw AssertionError("runtimeTuning is not part of this delegation test")
    }
}

private class TestBootstrapContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
}

private class RecordingBootstrapRuntimeFacade : MvpRuntimeFacade {
    override fun createSession(): SessionId = SessionId("bootstrap")

    override fun streamChat(request: com.pocketagent.runtime.StreamChatRequestV2): Flow<ChatStreamEvent> = emptyFlow()

    override fun cancelGeneration(sessionId: SessionId): Boolean = false

    override fun runTool(toolName: String, jsonArgs: String): String = ""

    override fun analyzeImage(imagePath: String, prompt: String): String = ""

    override fun exportDiagnostics(): String = ""

    override fun setRoutingMode(mode: RoutingMode) = Unit

    override fun getRoutingMode(): RoutingMode = RoutingMode.AUTO

    override fun runStartupChecks(): List<String> = emptyList()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) = Unit

    override fun deleteSession(sessionId: SessionId): Boolean = false
}
