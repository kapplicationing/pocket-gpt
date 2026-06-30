package com.pocketagent.android.runtime.modelmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.core.model.ModelSourceRef
import com.pocketagent.core.model.SourceTrustPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelDownloadTaskStateStoreInstrumentationTest {
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        ModelDownloadTaskStateStore.resetForTests()
        appContext.deleteDatabase("pocketagent_model_downloads.db")
        appContext.getSharedPreferences("pocketagent_model_downloads", 0).edit().clear().commit()
        ModelDownloadTaskStateStore.resetForTests()
    }

    @After
    fun tearDown() {
        ModelDownloadTaskStateStore.resetForTests()
        appContext.deleteDatabase("pocketagent_model_downloads.db")
        appContext.getSharedPreferences("pocketagent_model_downloads", 0).edit().clear().commit()
        ModelDownloadTaskStateStore.resetForTests()
    }

    @Test
    fun taskStorePersistsHuggingFaceDisplayNameAndSourceRef() {
        val sourceRef = ModelSourceRef(
            kind = ModelSourceKind.HUGGING_FACE,
            originId = "qwen3-0.6b-q4_k_m",
            publisher = "owner",
            repository = "owner/repo",
            trustPolicy = SourceTrustPolicy.INTEGRITY_ONLY,
            revision = "main",
            originUrl = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
        )
        val task = DownloadTaskState(
            taskId = "task-hf",
            modelId = "qwen3-0.6b-q4_k_m",
            version = "hf-model-aaaaaaaaaaaa",
            sourceKind = ModelSourceKind.HUGGING_FACE,
            displayName = "owner/repo / model.gguf",
            sourceRef = sourceRef,
            downloadUrl = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
            expectedSha256 = "a".repeat(64),
            provenanceIssuer = "huggingface:owner/repo",
            provenanceSignature = "",
            verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
            runtimeCompatibility = "android-arm64-v8a",
            status = DownloadTaskStatus.DOWNLOADING,
            progressBytes = 12L,
            totalBytes = 100L,
            updatedAtEpochMs = 2L,
        )

        ModelDownloadTaskStateStore.upsert(appContext, task)

        val decoded = ModelDownloadTaskStateStore.get(appContext, "task-hf")

        assertEquals("owner/repo / model.gguf", decoded?.displayName)
        assertEquals(ModelSourceKind.HUGGING_FACE, decoded?.sourceRef?.kind)
        assertEquals("owner/repo", decoded?.sourceRef?.repository)
        assertEquals("main", decoded?.sourceRef?.revision)
        assertEquals(SourceTrustPolicy.INTEGRITY_ONLY, decoded?.sourceRef?.trustPolicy)
        assertEquals("https://huggingface.co/owner/repo/resolve/main/model.gguf", decoded?.sourceRef?.originUrl)
    }

    @Test
    fun taskStoreReadsRowsWithoutNewHuggingFaceColumns() {
        appContext.openOrCreateDatabase("pocketagent_model_downloads.db", 0, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE download_tasks (
                    task_id TEXT PRIMARY KEY NOT NULL,
                    model_id TEXT NOT NULL,
                    version TEXT NOT NULL,
                    source_kind TEXT NOT NULL DEFAULT '${ModelSourceKind.BUILT_IN.name}',
                    download_url TEXT NOT NULL,
                    expected_sha256 TEXT NOT NULL,
                    provenance_issuer TEXT NOT NULL,
                    provenance_signature TEXT NOT NULL,
                    verification_policy TEXT NOT NULL,
                    runtime_compatibility TEXT NOT NULL,
                    prompt_profile_id TEXT,
                    processing_stage TEXT NOT NULL,
                    status TEXT NOT NULL,
                    progress_bytes INTEGER NOT NULL,
                    total_bytes INTEGER NOT NULL,
                    resume_etag TEXT,
                    resume_last_modified TEXT,
                    queue_order INTEGER NOT NULL DEFAULT 0,
                    network_preference TEXT NOT NULL DEFAULT '${DownloadNetworkPreference.ALLOW_METERED.name}',
                    download_speed_bps INTEGER,
                    eta_seconds INTEGER,
                    last_progress_epoch_ms INTEGER,
                    updated_at_epoch_ms INTEGER NOT NULL,
                    failure_reason TEXT,
                    message TEXT,
                    artifact_states_json TEXT,
                    active_artifact_id TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO download_tasks (
                    task_id,
                    model_id,
                    version,
                    source_kind,
                    download_url,
                    expected_sha256,
                    provenance_issuer,
                    provenance_signature,
                    verification_policy,
                    runtime_compatibility,
                    processing_stage,
                    status,
                    progress_bytes,
                    total_bytes,
                    queue_order,
                    network_preference,
                    updated_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    "task-legacy",
                    "qwen3.5-0.8b-q4",
                    "q4_0",
                    ModelSourceKind.BUILT_IN.name,
                    "https://example.test/model.gguf",
                    "b".repeat(64),
                    "",
                    "",
                    DownloadVerificationPolicy.INTEGRITY_ONLY.name,
                    "android-arm64-v8a",
                    DownloadProcessingStage.DOWNLOADING.name,
                    DownloadTaskStatus.QUEUED.name,
                    0L,
                    100L,
                    0L,
                    DownloadNetworkPreference.ALLOW_METERED.name,
                    1L,
                ),
            )
            db.version = 5
        }

        val decoded = ModelDownloadTaskStateStore.get(appContext, "task-legacy")

        assertNull(decoded?.displayName)
        assertNull(decoded?.sourceRef)
    }
}
