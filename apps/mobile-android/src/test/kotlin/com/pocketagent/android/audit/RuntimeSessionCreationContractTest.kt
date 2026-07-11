package com.pocketagent.android.audit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class RuntimeSessionCreationContractTest {
    @Test
    fun `runtime session callers use the typed transition boundary`() {
        val createSessionCall = Regex("""\b([A-Za-z][A-Za-z0-9]*)\.createSession\s*\(""")
        val allowedReceivers = mapOf(
            "AndroidMvpContainer.kt" to setOf("orchestrator"),
            "HotSwappableRuntimeFacade.kt" to setOf("it"),
            "RuntimeGateway.kt" to setOf("facade"),
            "ChatApp.kt" to setOf("viewModel"),
            "ChatViewModelConversationWorkflow.kt" to setOf("conversationService"),
            "AndroidChatConversationService.kt" to setOf("sessionService"),
            "AndroidChatSessionService.kt" to setOf("service"),
            "ChatStartupFlow.kt" to setOf("sessionCreationRetrier", "sessionService"),
        )
        val offenders = mainSourceRoot().walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .flatMap { file ->
                createSessionCall.findAll(file.readText()).mapNotNull { match ->
                    val receiver = match.groupValues[1]
                    val allowed = receiver in allowedReceivers[file.name].orEmpty()
                    if (allowed) {
                        null
                    } else {
                        "${file.relativeTo(mainSourceRoot()).path}: ${match.value}"
                    }
                }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "Runtime session callers must use createRuntimeSession() so transitions cannot escape " +
                "as exceptions: $offenders",
        )

        val runtimeContract = sourceFile("runtime/RuntimeGateway.kt").readText()
        assertTrue(
            "Production callers must use [createRuntimeSession]" in runtimeContract &&
                "fun createRuntimeSession(): RuntimeSessionCreationResult" in runtimeContract,
            "ChatRuntimeService must keep one documented typed session-creation boundary.",
        )
        val requiredTypedCallers = mapOf(
            "ui/ChatViewModelConversationWorkflow.kt" to "runtimeFacade.createRuntimeSession()",
            "ui/controllers/RuntimeSessionCreationRetrier.kt" to "runtimeGateway.createRuntimeSession()",
            "voice/OffscreenRuntimeClient.kt" to "runtimeGateway.createRuntimeSession()",
        )
        val missing = requiredTypedCallers.mapNotNull { (relativePath, marker) ->
            relativePath.takeIf { marker !in sourceFile(relativePath).readText() }
        }
        assertTrue(missing.isEmpty(), "Typed runtime session handling is missing from: $missing")
    }

    private fun sourceFile(relativePath: String): File = File(mainSourceRoot(), relativePath)

    private fun mainSourceRoot(): File = listOf(
        File("src/main/kotlin/com/pocketagent/android"),
        File("apps/mobile-android/src/main/kotlin/com/pocketagent/android"),
    ).firstOrNull(File::isDirectory)
        ?: error("Could not resolve Android main source root from ${File(".").absolutePath}")
}
