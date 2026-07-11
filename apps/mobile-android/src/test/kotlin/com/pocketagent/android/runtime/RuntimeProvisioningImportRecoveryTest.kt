package com.pocketagent.android.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeProvisioningImportRecoveryTest {
    @Test
    fun `failed durable commit invokes rollback`() {
        var rollbackCalls = 0

        val result = commitOrRollback(
            commit = { false },
            rollback = {
                rollbackCalls += 1
                true
            },
        )

        assertFalse(result.committed)
        assertTrue(result.rollbackSucceeded)
        assertEquals(1, rollbackCalls)
    }

    @Test
    fun `ambiguous durable failure reports rollback failure`() {
        val result = commitOrRollback(
            commit = { throw IllegalStateException("commit failed after write") },
            rollback = { false },
        )

        assertFalse(result.committed)
        assertFalse(result.rollbackSucceeded)
    }

    @Test
    fun `cleanup removes only owned unreferenced import artifacts`() {
        val directory = Files.createTempDirectory("pocketgpt-import-recovery").toFile()
        val referenced = directory.resolve(
            "pocketgpt-import-0123456789ab-${"a".repeat(64)}.gguf",
        ).apply { writeText("referenced") }
        val orphan = directory.resolve(
            "pocketgpt-import-0123456789ab-${"b".repeat(64)}.gguf",
        ).apply { writeText("orphan") }
        val activePublication = directory.resolve(
            "pocketgpt-import-0123456789ab-${"c".repeat(64)}.gguf",
        ).apply { writeText("publishing") }
        val orphanSidecar = directory.resolve("${orphan.name}$PROVISIONING_METADATA_SUFFIX")
            .apply { writeText("metadata") }
        val ownedTemp = directory.resolve(
            "${PROVISIONING_IMPORT_TEMP_PREFIX}fixture$PROVISIONING_IMPORT_TEMP_SUFFIX",
        ).apply { writeText("partial") }
        val activeTemp = directory.resolve(
            "${PROVISIONING_IMPORT_TEMP_PREFIX}active$PROVISIONING_IMPORT_TEMP_SUFFIX",
        ).apply { writeText("copying") }
        val unrelatedTemp = directory.resolve("download.tmp").apply { writeText("keep") }

        try {
            val result = cleanupOwnedImportArtifacts(
                managedDirectory = directory,
                referencedPaths = setOf(referenced.canonicalPath),
                activeImportPaths = setOf(activeTemp.canonicalPath, activePublication.canonicalPath),
                normalizePath = { path -> java.io.File(path).canonicalPath },
                metadataFileFor = { path -> java.io.File("$path$PROVISIONING_METADATA_SUFFIX") },
            )

            assertTrue(result.failedPaths.isEmpty())
            assertTrue(referenced.exists())
            assertFalse(orphan.exists())
            assertFalse(orphanSidecar.exists())
            assertFalse(ownedTemp.exists())
            assertTrue(activeTemp.exists())
            assertTrue(activePublication.exists())
            assertTrue(unrelatedTemp.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
