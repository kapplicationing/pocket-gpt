package com.pocketagent.android.ui

import com.pocketagent.android.runtime.ProvisionedModelState
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.runtime.RuntimeLoadedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelLibraryUxDecisionsTest {
    @Test
    fun `default badge is reserved for active product default model`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true)),
        )

        val badge = resolveDownloadedModelBadge(
            model = model,
            version = model.installedVersions.first(),
            defaultGetReadyModelId = "qwen3.5-0.8b-q4",
            activeModel = null,
            loadedModel = null,
        )

        assertEquals(DownloadedModelBadge.DEFAULT, badge)
    }

    @Test
    fun `active non default version shows active badge instead of default`() {
        val model = provisionedModel(
            modelId = "qwen3-1.7b-q4_k_m",
            activeVersion = "q4_k_m",
            installedVersions = listOf(versionDescriptor(modelId = "qwen3-1.7b-q4_k_m", version = "q4_k_m", isActive = true)),
        )

        val badge = resolveDownloadedModelBadge(
            model = model,
            version = model.installedVersions.first(),
            defaultGetReadyModelId = "qwen3.5-0.8b-q4",
            activeModel = null,
            loadedModel = null,
        )

        assertEquals(DownloadedModelBadge.ACTIVE, badge)
    }

    @Test
    fun `loaded badge wins over default and active badges`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true)),
        )

        val badge = resolveDownloadedModelBadge(
            model = model,
            version = model.installedVersions.first(),
            defaultGetReadyModelId = "qwen3.5-0.8b-q4",
            activeModel = RuntimeLoadedModel(modelId = "qwen3.5-0.8b-q4", modelVersion = "q4_0"),
            loadedModel = RuntimeLoadedModel(modelId = "qwen3.5-0.8b-q4", modelVersion = "q4_0"),
        )

        assertEquals(DownloadedModelBadge.LOADED, badge)
    }

    @Test
    fun `removing only installed active version clears active selection`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true)),
        )

        val plan = resolveRemoveVersionPlan(
            model = model,
            version = model.installedVersions.first(),
            loadedModel = null,
        )

        assertTrue(plan.requiresClearingActiveSelection)
        assertFalse(plan.isBlockedByActiveSelection)
        assertFalse(plan.requiresOffload)
    }

    @Test
    fun `removing loaded only installed active version offloads first and clears active selection`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true)),
        )

        val plan = resolveRemoveVersionPlan(
            model = model,
            version = model.installedVersions.first(),
            loadedModel = RuntimeLoadedModel(modelId = "qwen3.5-0.8b-q4", modelVersion = "q4_0"),
        )

        assertTrue(plan.requiresOffload)
        assertTrue(plan.requiresClearingActiveSelection)
        assertFalse(plan.isBlockedByActiveSelection)
    }

    @Test
    fun `removing active version with alternatives stays blocked`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true),
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_k_m", isActive = false),
            ),
        )

        val plan = resolveRemoveVersionPlan(
            model = model,
            version = model.installedVersions.first(),
            loadedModel = null,
        )

        assertTrue(plan.isBlockedByActiveSelection)
        assertFalse(plan.requiresClearingActiveSelection)
    }

    @Test
    fun `removing non-active version with alternatives is not blocked`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true),
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_k_m", isActive = false),
            ),
        )
        val toRemove = model.installedVersions.single { it.version == "q4_k_m" }

        val plan = resolveRemoveVersionPlan(
            model = model,
            version = toRemove,
            loadedModel = null,
        )

        assertFalse(plan.isBlockedByActiveSelection)
        assertFalse(plan.requiresClearingActiveSelection)
        assertFalse(plan.requiresOffload)
    }

    @Test
    fun `removing loaded but not active version requires offload only`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true),
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_k_m", isActive = false),
            ),
        )
        val toRemove = model.installedVersions.single { it.version == "q4_k_m" }

        val plan = resolveRemoveVersionPlan(
            model = model,
            version = toRemove,
            loadedModel = RuntimeLoadedModel(modelId = "qwen3.5-0.8b-q4", modelVersion = "q4_k_m"),
        )

        assertTrue(plan.requiresOffload)
        assertFalse(plan.requiresClearingActiveSelection)
        assertFalse(plan.isBlockedByActiveSelection)
    }

    @Test
    fun `removing version when model has no active version at all`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = null,
            installedVersions = listOf(
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = false),
            ),
        )

        val plan = resolveRemoveVersionPlan(
            model = model,
            version = model.installedVersions.first(),
            loadedModel = null,
        )

        assertFalse(plan.isBlockedByActiveSelection)
        assertFalse(plan.requiresClearingActiveSelection)
        assertFalse(plan.requiresOffload)
    }

    @Test
    fun `badge after remove shows ready for remaining non-active version`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = null,
            installedVersions = listOf(
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_k_m", isActive = false),
            ),
        )
        val version = model.installedVersions.first()

        val badge = resolveDownloadedModelBadge(
            model = model,
            version = version,
            defaultGetReadyModelId = "qwen3.5-0.8b-q4",
            activeModel = null,
            loadedModel = null,
        )

        assertEquals(DownloadedModelBadge.READY, badge)
    }

    @Test
    fun `badge during load shows switching when loaded differs from active`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true),
            ),
        )
        val version = model.installedVersions.first()
        val activeModel = RuntimeLoadedModel(modelId = "qwen3.5-0.8b-q4", modelVersion = "q4_0")
        val loadedModel = RuntimeLoadedModel(modelId = "qwen3-1.7b-q4_k_m", modelVersion = "q4_k_m")

        val badge = resolveDownloadedModelBadge(
            model = model,
            version = version,
            defaultGetReadyModelId = "qwen3.5-0.8b-q4",
            activeModel = activeModel,
            loadedModel = loadedModel,
        )

        assertEquals(DownloadedModelBadge.SWITCHING, badge)
    }

    @Test
    fun `badge when loaded model version differs from active version`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true),
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_k_m", isActive = false),
            ),
        )
        val loadedModel = RuntimeLoadedModel(modelId = "qwen3.5-0.8b-q4", modelVersion = "q4_k_m")
        val q40 = model.installedVersions.single { it.version == "q4_0" }
        val q4km = model.installedVersions.single { it.version == "q4_k_m" }

        val loadedBadge = resolveDownloadedModelBadge(
            model = model,
            version = q4km,
            defaultGetReadyModelId = "qwen3-1.7b-q4_k_m",
            activeModel = null,
            loadedModel = loadedModel,
        )
        val activeBadge = resolveDownloadedModelBadge(
            model = model,
            version = q40,
            defaultGetReadyModelId = "qwen3-1.7b-q4_k_m",
            activeModel = null,
            loadedModel = loadedModel,
        )

        assertEquals(DownloadedModelBadge.LOADED, loadedBadge)
        assertEquals(DownloadedModelBadge.ACTIVE, activeBadge)
    }
}

private fun provisionedModel(
    modelId: String,
    activeVersion: String?,
    installedVersions: List<ModelVersionDescriptor>,
): ProvisionedModelState {
    return ProvisionedModelState(
        modelId = modelId,
        displayName = modelId,
        fileName = "$modelId.gguf",
        absolutePath = installedVersions.firstOrNull()?.absolutePath,
        sha256 = installedVersions.firstOrNull()?.sha256,
        importedAtEpochMs = 1L,
        activeVersion = activeVersion,
        installedVersions = installedVersions,
    )
}

private fun versionDescriptor(
    modelId: String,
    version: String,
    isActive: Boolean,
): ModelVersionDescriptor {
    return ModelVersionDescriptor(
        modelId = modelId,
        version = version,
        displayName = "$modelId $version",
        absolutePath = "/tmp/$modelId-$version.gguf",
        sha256 = "a".repeat(64),
        provenanceIssuer = "issuer",
        provenanceSignature = "sig",
        runtimeCompatibility = "android-arm64-v8a",
        fileSizeBytes = 1L,
        importedAtEpochMs = 1L,
        isActive = isActive,
    )
}
