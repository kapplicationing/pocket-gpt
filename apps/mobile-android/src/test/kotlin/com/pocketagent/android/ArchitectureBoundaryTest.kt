package com.pocketagent.android

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.streams.toList
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchitectureBoundaryTest {
    @Test
    fun `ui layer does not reference app runtime dependencies singleton`() {
        val uiDir = locatePath(
            "src/main/kotlin/com/pocketagent/android/ui",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui",
        )
        val offenders = Files.walk(uiDir).use { stream ->
            stream
                .filter { path -> path.toString().endsWith(".kt") }
                .filter { path -> String(Files.readAllBytes(path)).contains("AppRuntimeDependencies") }
                .map(Path::toString)
                .toList()
        }
        assertFalse(
            offenders.isNotEmpty(),
            "UI package must not reference AppRuntimeDependencies. Found: ${offenders.joinToString()}",
        )
    }

    @Test
    fun `app runtime dependencies singleton is only referenced by sanctioned gateway boundaries`() {
        val appDir = locatePath(
            "src/main/kotlin/com/pocketagent/android",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android",
        )
        val allowedSuffixes = setOf(
            "com/pocketagent/android/AppRuntimeDependencies.kt",
            "com/pocketagent/android/runtime/AppRuntimeAccess.kt",
            "com/pocketagent/android/runtime/ProvisioningGateway.kt",
            "com/pocketagent/android/runtime/RuntimeBootstrapper.kt",
            "com/pocketagent/android/runtime/modelmanager/ModelDownloadCancelReceiver.kt",
        )
        val offenders = Files.walk(appDir).use { stream ->
            stream
                .filter { path -> path.toString().endsWith(".kt") }
                .filter { path -> String(Files.readAllBytes(path)).contains("AppRuntimeDependencies") }
                .map(Path::toString)
                .filter { path -> allowedSuffixes.none { suffix -> path.replace('\\', '/').endsWith(suffix) } }
                .toList()
        }
        assertFalse(
            offenders.isNotEmpty(),
            "Only sanctioned gateways may reference AppRuntimeDependencies. Found: ${offenders.joinToString()}",
        )
    }

    @Test
    fun `unit tests avoid wall clock sleep usage`() {
        val testDir = locatePath(
            "src/test/kotlin",
            "apps/mobile-android/src/test/kotlin",
        )
        val sleepRegex = Regex("""\bThread\s*\.\s*sleep\s*\(""")
        val offenders = Files.walk(testDir).use { stream ->
            stream
                .filter { path -> path.toString().endsWith(".kt") }
                .filter { path -> sleepRegex.containsMatchIn(String(Files.readAllBytes(path))) }
                .map(Path::toString)
                .toList()
        }
        assertFalse(
            offenders.isNotEmpty(),
            "Unit tests must use coroutine test time instead of wall clock sleeps. Found: ${offenders.joinToString()}",
        )
    }

    @Test
    fun `runtime package does not reference ui package types`() {
        val runtimeDir = locatePath(
            "src/main/kotlin/com/pocketagent/android/runtime",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime",
        )
        val offenders = Files.walk(runtimeDir).use { stream ->
            stream
                .filter { path -> path.toString().endsWith(".kt") }
                .filter { path -> String(Files.readAllBytes(path)).contains("com.pocketagent.android.ui") }
                .map(Path::toString)
                .toList()
        }
        assertFalse(
            offenders.isNotEmpty(),
            "Runtime package must not depend on UI packages. Found: ${offenders.joinToString()}",
        )
    }

    @Test
    fun `ui state package contains only persistence contract and ui state types`() {
        val uiStateDir = locatePath(
            "src/main/kotlin/com/pocketagent/android/ui/state",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/state",
        )
        val implementationMarkers = listOf(
            "SQLiteOpenHelper",
            "PersistedChatStateCodec",
            "AndroidSessionPersistence",
            "SQLiteChatSessionRepository",
            "interface SessionPersistence",
            "sealed interface SessionStateLoadResult",
            "StoredChatState",
        )
        val offenders = Files.walk(uiStateDir).use { stream ->
            stream
                .filter { path -> path.toString().endsWith(".kt") }
                .filter { path ->
                    val source = String(Files.readAllBytes(path))
                    implementationMarkers.any(source::contains)
                }
                .map(Path::toString)
                .toList()
        }
        assertTrue(
            offenders.isEmpty(),
            "ui/state must not host persistence implementation details. Found: ${offenders.joinToString()}",
        )
    }

    @Test
    fun `ui packages do not import native bridge types directly`() {
        val uiDir = locatePath(
            "src/main/kotlin/com/pocketagent/android/ui",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui",
        )
        val offenders = Files.walk(uiDir).use { stream ->
            stream
                .filter { path -> path.toString().endsWith(".kt") }
                .filter { path -> String(Files.readAllBytes(path)).contains("import com.pocketagent.nativebridge.") }
                .map(Path::toString)
                .toList()
        }
        assertTrue(
            offenders.isEmpty(),
            "UI packages must not import nativebridge types directly. Found: ${offenders.joinToString()}",
        )
    }

    @Test
    fun `android sources do not use removed runtime gateway alias`() {
        val appDir = locatePath(
            "src/main/kotlin/com/pocketagent/android",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android",
        )
        val testDir = locatePath(
            "src/test/kotlin/com/pocketagent/android",
            "apps/mobile-android/src/test/kotlin/com/pocketagent/android",
        )
        val androidTestDir = locatePath(
            "src/androidTest/kotlin/com/pocketagent/android",
            "apps/mobile-android/src/androidTest/kotlin/com/pocketagent/android",
        )
        val forbiddenPatterns = listOf(
            "import com.pocketagent.android.runtime.RuntimeGateway",
            ": RuntimeGateway",
            "typealias RuntimeGateway",
        )
        val offenders = sequenceOf(appDir, testDir, androidTestDir)
            .flatMap { dir ->
                Files.walk(dir).use { stream ->
                    stream
                        .filter { path -> path.toString().endsWith(".kt") }
                        .filter { path -> !isArchitectureBoundaryTestFile(path) }
                        .filter { path ->
                            val source = String(Files.readAllBytes(path))
                            forbiddenPatterns.any(source::contains)
                        }
                        .map(Path::toString)
                        .toList()
                        .asSequence()
                }
            }
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "Android layer must use ChatRuntimeService directly. Found: ${offenders.joinToString()}",
        )
    }

    @Test
    fun `android sources do not use deprecated direct stream request builder path`() {
        val appDir = locatePath(
            "src/main/kotlin/com/pocketagent/android",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android",
        )
        val testDir = locatePath(
            "src/test/kotlin/com/pocketagent/android",
            "apps/mobile-android/src/test/kotlin/com/pocketagent/android",
        )
        val offenders = sequenceOf(appDir, testDir)
            .flatMap { dir ->
                Files.walk(dir).use { stream ->
                    stream
                        .filter { path -> path.toString().endsWith(".kt") }
                        .filter { path -> !isArchitectureBoundaryTestFile(path) }
                        .filter { path -> String(Files.readAllBytes(path)).contains("buildStreamChatRequest(") }
                        .map(Path::toString)
                        .toList()
                        .asSequence()
                }
            }
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "Android layer must prepare streams via ChatRuntimeService.prepareChatStream. Found: ${offenders.joinToString()}",
        )
    }

    @Test
    fun `android sources do not use deprecated stream user message request`() {
        val mainDir = locatePath(
            "src/main/kotlin/com/pocketagent/android",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android",
        )
        val testDir = locatePath(
            "src/test/kotlin/com/pocketagent/android",
            "apps/mobile-android/src/test/kotlin/com/pocketagent/android",
        )
        val androidTestDir = locatePath(
            "src/androidTest/kotlin/com/pocketagent/android",
            "apps/mobile-android/src/androidTest/kotlin/com/pocketagent/android",
        )
        val offenders = sequenceOf(mainDir, testDir, androidTestDir)
            .flatMap { dir ->
                Files.walk(dir).use { stream ->
                    stream
                        .filter { path -> path.toString().endsWith(".kt") }
                        .filter { path -> !isArchitectureBoundaryTestFile(path) }
                        .filter { path -> String(Files.readAllBytes(path)).contains("StreamUserMessageRequest") }
                        .map(Path::toString)
                        .toList()
                        .asSequence()
                }
            }
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "Deprecated StreamUserMessageRequest should not appear in Android sources. Found: ${offenders.joinToString()}",
        )
    }

    private fun locatePath(vararg candidates: String): Path {
        return candidates
            .asSequence()
            .map { Path.of(it) }
            .firstOrNull { it.exists() }
            ?: error("Unable to locate path from candidates: ${candidates.joinToString()}")
    }

    private fun isArchitectureBoundaryTestFile(path: Path): Boolean {
        return path.fileName.toString() == "ArchitectureBoundaryTest.kt"
    }
}
