package com.pocketagent.android.runtime.modelmanager

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.pocketagent.android.runtime.ModelSourceRefJsonCodec
import com.pocketagent.core.model.ModelArtifactRole
import com.pocketagent.core.model.ModelSourceKind
import org.json.JSONArray
import org.json.JSONObject

internal object ModelDownloadTaskStateStore {
    private const val DB_NAME = "pocketagent_model_downloads.db"
    private const val DB_VERSION = 6
    private const val TABLE = "download_tasks"

    private const val LEGACY_PREFS_NAME = "pocketagent_model_downloads"
    private const val LEGACY_TASKS_KEY = "tasks_json_v1"

    private val lock = Any()

    @Volatile
    private var helper: StoreDbHelper? = null

    fun resetForTests() {
        synchronized(lock) {
            helper?.close()
            helper = null
        }
    }

    fun list(context: Context): List<DownloadTaskState> {
        synchronized(lock) {
            val appContext = context.applicationContext
            migrateLegacyPrefsIfNeeded(appContext)
            val db = db(appContext).readableDatabase
            return queryStates(db)
        }
    }

    fun get(context: Context, taskId: String): DownloadTaskState? {
        synchronized(lock) {
            val appContext = context.applicationContext
            migrateLegacyPrefsIfNeeded(appContext)
            val db = db(appContext).readableDatabase
            db.query(
                TABLE,
                null,
                "task_id = ?",
                arrayOf(taskId),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                return if (cursor.moveToFirst()) decodeCursor(cursor) else null
            }
        }
    }

    fun upsert(context: Context, task: DownloadTaskState) {
        synchronized(lock) {
            val appContext = context.applicationContext
            migrateLegacyPrefsIfNeeded(appContext)
            val db = db(appContext).writableDatabase
            upsertRow(db, task)
        }
    }

    fun remove(context: Context, taskId: String) {
        synchronized(lock) {
            val appContext = context.applicationContext
            migrateLegacyPrefsIfNeeded(appContext)
            val db = db(appContext).writableDatabase
            db.delete(TABLE, "task_id = ?", arrayOf(taskId))
        }
    }

    fun reconcile(
        context: Context,
        mutate: (MutableMap<String, DownloadTaskState>) -> Unit,
    ): List<DownloadTaskState> {
        synchronized(lock) {
            val appContext = context.applicationContext
            migrateLegacyPrefsIfNeeded(appContext)
            val db = db(appContext).writableDatabase
            db.beginTransaction()
            try {
                val existing = queryStates(db)
                val states = existing.associateBy { it.taskId }.toMutableMap()
                mutate(states)
                val removedTaskIds = existing.map { it.taskId }.toSet() - states.keys
                removedTaskIds.forEach { taskId ->
                    db.delete(TABLE, "task_id = ?", arrayOf(taskId))
                }
                states.values.forEach { task ->
                    upsertRow(db, task)
                }
                db.setTransactionSuccessful()
                return states.values.sortedByDescending { it.updatedAtEpochMs }
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun upsertRow(db: SQLiteDatabase, task: DownloadTaskState) {
        val values = ContentValues().apply {
            put("task_id", task.taskId)
            put("model_id", task.modelId)
            put("version", task.version)
            put("source_kind", task.sourceKind.name)
            put("display_name", task.displayName)
            put("source_ref_json", task.sourceRef?.let(ModelSourceRefJsonCodec::encode)?.toString())
            put("download_url", task.downloadUrl)
            put("expected_sha256", task.expectedSha256)
            put("provenance_issuer", task.provenanceIssuer)
            put("provenance_signature", task.provenanceSignature)
            put("verification_policy", task.verificationPolicy.name)
            put("runtime_compatibility", task.runtimeCompatibility)
            put("prompt_profile_id", task.promptProfileId)
            put("processing_stage", task.processingStage.name)
            put("status", task.status.name)
            put("progress_bytes", task.progressBytes)
            put("total_bytes", task.totalBytes)
            put("resume_etag", task.resumeEtag)
            put("resume_last_modified", task.resumeLastModified)
            put("queue_order", task.queueOrder)
            put("network_preference", task.networkPreference.name)
            put("download_speed_bps", task.downloadSpeedBps)
            put("eta_seconds", task.etaSeconds)
            put("last_progress_epoch_ms", task.lastProgressEpochMs)
            put("updated_at_epoch_ms", task.updatedAtEpochMs)
            put("failure_reason", task.failureReason?.name)
            put("message", task.message)
            put("artifact_states_json", encodeArtifactStates(task.artifactStates).toString())
            put("active_artifact_id", task.activeArtifactId)
        }
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun decodeLegacy(json: JSONObject): DownloadTaskState? {
        return decodeValues(
            taskId = json.optString("taskId", "").trim(),
            modelId = json.optString("modelId", "").trim(),
            version = json.optString("version", "").trim(),
            sourceKindRaw = json.optString("sourceKind", "").trim(),
            displayName = json.optString("displayName", "").trim().ifEmpty { null },
            sourceRefJson = json.optJSONObject("sourceRef")?.toString(),
            downloadUrl = json.optString("downloadUrl", "").trim(),
            expectedSha256 = json.optString("expectedSha256", "").trim(),
            provenanceIssuer = json.optString("provenanceIssuer", "").trim(),
            provenanceSignature = json.optString("provenanceSignature", "").trim(),
            verificationPolicyRaw = json.optString(
                "verificationPolicy",
                DownloadVerificationPolicy.INTEGRITY_ONLY.name,
            ),
            runtimeCompatibility = json.optString("runtimeCompatibility", "").trim(),
            promptProfileId = json.optString("promptProfileId", "").trim().ifEmpty { null },
            processingStageRaw = json.optString(
                "processingStage",
                DownloadProcessingStage.DOWNLOADING.name,
            ),
            statusRaw = json.optString("status", DownloadTaskStatus.QUEUED.name),
            progressBytes = json.optLong("progressBytes", 0L).coerceAtLeast(0L),
            totalBytes = json.optLong("totalBytes", 0L).coerceAtLeast(0L),
            resumeEtag = json.optString("resumeEtag", "").trim().ifEmpty { null },
            resumeLastModified = json.optString("resumeLastModified", "").trim().ifEmpty { null },
            queueOrder = json.optLong("queueOrder", 0L).coerceAtLeast(0L),
            networkPreferenceRaw = json.optString(
                "networkPreference",
                DownloadNetworkPreference.ALLOW_METERED.name,
            ),
            downloadSpeedBps = json.optLong("downloadSpeedBps", -1L).takeIf { value -> value > 0L },
            etaSeconds = json.optLong("etaSeconds", -1L).takeIf { value -> value >= 0L },
            lastProgressEpochMs = json.optLong("lastProgressEpochMs", -1L).takeIf { value -> value > 0L },
            updatedAtEpochMs = json.optLong("updatedAtEpochMs", System.currentTimeMillis()),
            failureReasonRaw = json.optString("failureReason", ""),
            message = json.optString("message", "").trim().ifEmpty { null },
            artifactStatesJson = json.optJSONArray("artifactStates")?.toString(),
            activeArtifactId = json.optString("activeArtifactId", "").trim().ifEmpty { null },
        )
    }

    private fun decodeCursor(cursor: Cursor): DownloadTaskState? {
        return decodeValues(
            taskId = cursor.stringOrEmpty("task_id"),
            modelId = cursor.stringOrEmpty("model_id"),
            version = cursor.stringOrEmpty("version"),
            sourceKindRaw = cursor.stringOrEmpty("source_kind"),
            displayName = cursor.stringOrEmpty("display_name").ifBlank { null },
            sourceRefJson = cursor.stringOrEmpty("source_ref_json").ifBlank { null },
            downloadUrl = cursor.stringOrEmpty("download_url"),
            expectedSha256 = cursor.stringOrEmpty("expected_sha256"),
            provenanceIssuer = cursor.stringOrEmpty("provenance_issuer"),
            provenanceSignature = cursor.stringOrEmpty("provenance_signature"),
            verificationPolicyRaw = cursor.stringOrEmpty("verification_policy"),
            runtimeCompatibility = cursor.stringOrEmpty("runtime_compatibility"),
            promptProfileId = cursor.stringOrEmpty("prompt_profile_id").ifBlank { null },
            processingStageRaw = cursor.stringOrEmpty("processing_stage"),
            statusRaw = cursor.stringOrEmpty("status"),
            progressBytes = cursor.longOrZero("progress_bytes"),
            totalBytes = cursor.longOrZero("total_bytes"),
            resumeEtag = cursor.stringOrEmpty("resume_etag").ifBlank { null },
            resumeLastModified = cursor.stringOrEmpty("resume_last_modified").ifBlank { null },
            queueOrder = cursor.longOrZero("queue_order"),
            networkPreferenceRaw = cursor.stringOrEmpty("network_preference"),
            downloadSpeedBps = cursor.longOrNull("download_speed_bps"),
            etaSeconds = cursor.longOrNull("eta_seconds"),
            lastProgressEpochMs = cursor.longOrNull("last_progress_epoch_ms"),
            updatedAtEpochMs = cursor.longOrZero("updated_at_epoch_ms"),
            failureReasonRaw = cursor.stringOrEmpty("failure_reason"),
            message = cursor.stringOrEmpty("message").ifBlank { null },
            artifactStatesJson = cursor.stringOrEmpty("artifact_states_json").ifBlank { null },
            activeArtifactId = cursor.stringOrEmpty("active_artifact_id").ifBlank { null },
        )
    }

    private fun decodeValues(
        taskId: String,
        modelId: String,
        version: String,
        sourceKindRaw: String,
        displayName: String?,
        sourceRefJson: String?,
        downloadUrl: String,
        expectedSha256: String,
        provenanceIssuer: String,
        provenanceSignature: String,
        verificationPolicyRaw: String,
        runtimeCompatibility: String,
        promptProfileId: String?,
        processingStageRaw: String,
        statusRaw: String,
        progressBytes: Long,
        totalBytes: Long,
        resumeEtag: String?,
        resumeLastModified: String?,
        queueOrder: Long,
        networkPreferenceRaw: String,
        downloadSpeedBps: Long?,
        etaSeconds: Long?,
        lastProgressEpochMs: Long?,
        updatedAtEpochMs: Long,
        failureReasonRaw: String,
        message: String?,
        artifactStatesJson: String?,
        activeArtifactId: String?,
    ): DownloadTaskState? {
        val status = runCatching { DownloadTaskStatus.valueOf(statusRaw) }.getOrNull()
        val failure = runCatching {
            if (failureReasonRaw.isBlank()) {
                null
            } else {
                DownloadFailureReason.valueOf(failureReasonRaw)
            }
        }.getOrNull()
        val verificationPolicy = runCatching { DownloadVerificationPolicy.valueOf(verificationPolicyRaw) }.getOrNull()
        val sourceKind = runCatching {
            if (sourceKindRaw.isBlank()) {
                ModelSourceKind.BUILT_IN
            } else {
                ModelSourceKind.valueOf(sourceKindRaw)
            }
        }.getOrNull()
        val sourceRef = ModelSourceRefJsonCodec.decode(sourceRefJson)
        val processingStage = runCatching { DownloadProcessingStage.valueOf(processingStageRaw) }.getOrNull()
        val networkPreference = runCatching {
            if (networkPreferenceRaw.isBlank()) {
                DownloadNetworkPreference.ALLOW_METERED
            } else {
                DownloadNetworkPreference.valueOf(networkPreferenceRaw)
            }
        }.getOrNull()
        if (taskId.isEmpty() || modelId.isEmpty() || version.isEmpty()) {
            return null
        }
        val corruptionNotes = buildList {
            if (status == null) {
                add("invalid_status=$statusRaw")
            }
            if (verificationPolicy == null) {
                add("invalid_verification_policy=$verificationPolicyRaw")
            }
            if (sourceKind == null) {
                add("invalid_source_kind=$sourceKindRaw")
            }
            if (processingStage == null) {
                add("invalid_processing_stage=$processingStageRaw")
            }
            if (networkPreference == null) {
                add("invalid_network_preference=$networkPreferenceRaw")
            }
            if (failureReasonRaw.isNotBlank() && failure == null) {
                add("invalid_failure_reason=$failureReasonRaw")
            }
        }
        val isCorrupt = corruptionNotes.isNotEmpty()
        val artifactStates = decodeArtifactStates(artifactStatesJson).ifEmpty {
            listOf(
                DownloadArtifactTaskState(
                    artifactId = "$modelId::$version::primary",
                    role = ModelArtifactRole.PRIMARY_GGUF,
                    fileName = downloadUrl.substringAfterLast('/').ifBlank { "$modelId-$version.gguf" },
                    downloadUrl = downloadUrl,
                    expectedSha256 = expectedSha256,
                    provenanceIssuer = provenanceIssuer,
                    provenanceSignature = provenanceSignature,
                    verificationPolicy = verificationPolicy ?: DownloadVerificationPolicy.UNKNOWN,
                    runtimeCompatibility = runtimeCompatibility,
                    fileSizeBytes = totalBytes.coerceAtLeast(0L),
                    progressBytes = progressBytes.coerceAtLeast(0L),
                    totalBytes = totalBytes.coerceAtLeast(progressBytes.coerceAtLeast(0L)),
                    resumeEtag = resumeEtag?.takeIf { it.isNotBlank() },
                    resumeLastModified = resumeLastModified?.takeIf { it.isNotBlank() },
                    status = when {
                        isCorrupt -> DownloadArtifactTaskStatus.FAILED
                        status == DownloadTaskStatus.FAILED -> DownloadArtifactTaskStatus.FAILED
                        status == DownloadTaskStatus.VERIFYING -> DownloadArtifactTaskStatus.VERIFIED
                        status == DownloadTaskStatus.DOWNLOADING -> DownloadArtifactTaskStatus.DOWNLOADING
                        status == DownloadTaskStatus.COMPLETED || status == DownloadTaskStatus.INSTALLED_INACTIVE ->
                            DownloadArtifactTaskStatus.INSTALLED
                        else -> DownloadArtifactTaskStatus.PENDING
                    },
                    failureReason = if (isCorrupt) DownloadFailureReason.UNKNOWN else failure,
                ),
            )
        }
        val resolvedMessage = if (isCorrupt) {
            "Corrupt task metadata detected; task marked failed (${corruptionNotes.joinToString(",")})."
        } else {
            message
        }
        return DownloadTaskState(
            taskId = taskId,
            modelId = modelId,
            version = version,
            sourceKind = sourceKind ?: ModelSourceKind.BUILT_IN,
            displayName = displayName,
            sourceRef = sourceRef,
            downloadUrl = downloadUrl,
            expectedSha256 = expectedSha256,
            provenanceIssuer = provenanceIssuer,
            provenanceSignature = provenanceSignature,
            verificationPolicy = verificationPolicy ?: DownloadVerificationPolicy.UNKNOWN,
            runtimeCompatibility = runtimeCompatibility,
            promptProfileId = promptProfileId,
            processingStage = processingStage ?: DownloadProcessingStage.CORRUPT,
            status = if (isCorrupt) DownloadTaskStatus.FAILED else status ?: DownloadTaskStatus.FAILED,
            progressBytes = progressBytes.coerceAtLeast(0L),
            totalBytes = totalBytes.coerceAtLeast(0L),
            resumeEtag = resumeEtag?.takeIf { it.isNotBlank() },
            resumeLastModified = resumeLastModified?.takeIf { it.isNotBlank() },
            queueOrder = queueOrder.coerceAtLeast(0L),
            networkPreference = networkPreference ?: DownloadNetworkPreference.ALLOW_METERED,
            downloadSpeedBps = downloadSpeedBps?.takeIf { value -> value > 0L },
            etaSeconds = etaSeconds?.takeIf { value -> value >= 0L },
            lastProgressEpochMs = lastProgressEpochMs?.takeIf { value -> value > 0L },
            updatedAtEpochMs = updatedAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
            failureReason = if (isCorrupt) DownloadFailureReason.UNKNOWN else failure,
            message = resolvedMessage,
            artifactStates = artifactStates,
            activeArtifactId = activeArtifactId ?: artifactStates.firstOrNull()?.artifactId,
        )
    }

    private fun migrateLegacyPrefsIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(LEGACY_TASKS_KEY, null).orEmpty().trim()
        if (raw.isEmpty()) {
            return
        }
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: JSONArray()
        val db = db(context).writableDatabase
        db.beginTransaction()
        try {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val decoded = decodeLegacy(item) ?: continue
                upsertRow(db, decoded)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        prefs.edit().remove(LEGACY_TASKS_KEY).apply()
    }

    private fun queryStates(db: SQLiteDatabase): List<DownloadTaskState> {
        db.query(TABLE, null, null, null, null, null, "updated_at_epoch_ms DESC").use { cursor ->
            val out = mutableListOf<DownloadTaskState>()
            while (cursor.moveToNext()) {
                decodeCursor(cursor)?.let(out::add)
            }
            return out
        }
    }

    private fun db(context: Context): StoreDbHelper {
        return helper ?: synchronized(lock) {
            helper ?: StoreDbHelper(context.applicationContext).also { helper = it }
        }
    }

    private class StoreDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE (
                    task_id TEXT PRIMARY KEY NOT NULL,
                    model_id TEXT NOT NULL,
                    version TEXT NOT NULL,
                    source_kind TEXT NOT NULL DEFAULT '${ModelSourceKind.BUILT_IN.name}',
                    display_name TEXT,
                    source_ref_json TEXT,
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
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_download_tasks_updated_at ON $TABLE(updated_at_epoch_ms DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN download_speed_bps INTEGER")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN eta_seconds INTEGER")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN last_progress_epoch_ms INTEGER")
            }
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN resume_etag TEXT")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN resume_last_modified TEXT")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN queue_order INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "ALTER TABLE $TABLE ADD COLUMN network_preference TEXT NOT NULL DEFAULT '${DownloadNetworkPreference.ALLOW_METERED.name}'",
                )
            }
            if (oldVersion < 4) {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN source_kind TEXT NOT NULL DEFAULT '${ModelSourceKind.BUILT_IN.name}'")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN artifact_states_json TEXT")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN active_artifact_id TEXT")
            }
            if (oldVersion < 5) {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN prompt_profile_id TEXT")
            }
            if (oldVersion < 6) {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN display_name TEXT")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN source_ref_json TEXT")
            }
        }
    }

    private fun encodeArtifactStates(states: List<DownloadArtifactTaskState>): JSONArray {
        return JSONArray().apply {
            states.forEach { state ->
                put(
                    JSONObject()
                        .put("artifactId", state.artifactId)
                        .put("role", state.role.name)
                        .put("fileName", state.fileName)
                        .put("downloadUrl", state.downloadUrl)
                        .put("expectedSha256", state.expectedSha256)
                        .put("provenanceIssuer", state.provenanceIssuer)
                        .put("provenanceSignature", state.provenanceSignature)
                        .put("verificationPolicy", state.verificationPolicy.name)
                        .put("runtimeCompatibility", state.runtimeCompatibility)
                        .put("fileSizeBytes", state.fileSizeBytes)
                        .put("required", state.required)
                        .put("progressBytes", state.progressBytes)
                        .put("totalBytes", state.totalBytes)
                        .put("resumeEtag", state.resumeEtag)
                        .put("resumeLastModified", state.resumeLastModified)
                        .put("verifiedSha256", state.verifiedSha256)
                        .put("stagedFileName", state.stagedFileName)
                        .put("installedAbsolutePath", state.installedAbsolutePath)
                        .put("status", state.status.name)
                        .put("failureReason", state.failureReason?.name)
                )
            }
        }
    }

    private fun decodeArtifactStates(raw: String?): List<DownloadArtifactTaskState> {
        val payload = raw?.trim().orEmpty()
        if (payload.isEmpty()) {
            return emptyList()
        }
        val array = runCatching { JSONArray(payload) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val role = runCatching { ModelArtifactRole.valueOf(item.optString("role", "").trim()) }.getOrNull() ?: continue
                val status = runCatching { DownloadArtifactTaskStatus.valueOf(item.optString("status", "").trim()) }
                    .getOrDefault(DownloadArtifactTaskStatus.PENDING)
                val verificationPolicy = runCatching {
                    DownloadVerificationPolicy.valueOf(item.optString("verificationPolicy", DownloadVerificationPolicy.INTEGRITY_ONLY.name).trim())
                }.getOrDefault(DownloadVerificationPolicy.INTEGRITY_ONLY)
                val failureReason = runCatching {
                    item.optString("failureReason", "").trim()
                        .takeIf { it.isNotBlank() }
                        ?.let(DownloadFailureReason::valueOf)
                }.getOrNull()
                val artifactId = item.optString("artifactId", "").trim()
                val fileName = item.optString("fileName", "").trim()
                if (artifactId.isEmpty() || fileName.isEmpty()) {
                    continue
                }
                add(
                    DownloadArtifactTaskState(
                        artifactId = artifactId,
                        role = role,
                        fileName = fileName,
                        downloadUrl = item.optString("downloadUrl", "").trim(),
                        expectedSha256 = item.optString("expectedSha256", "").trim(),
                        provenanceIssuer = item.optString("provenanceIssuer", "").trim(),
                        provenanceSignature = item.optString("provenanceSignature", "").trim(),
                        verificationPolicy = verificationPolicy,
                        runtimeCompatibility = item.optString("runtimeCompatibility", "").trim(),
                        fileSizeBytes = item.optLong("fileSizeBytes", 0L).coerceAtLeast(0L),
                        required = item.optBoolean("required", true),
                        progressBytes = item.optLong("progressBytes", 0L).coerceAtLeast(0L),
                        totalBytes = item.optLong("totalBytes", 0L).coerceAtLeast(0L),
                        resumeEtag = item.optString("resumeEtag", "").trim().ifEmpty { null },
                        resumeLastModified = item.optString("resumeLastModified", "").trim().ifEmpty { null },
                        verifiedSha256 = item.optString("verifiedSha256", "").trim().ifEmpty { null },
                        stagedFileName = item.optString("stagedFileName", "").trim().ifEmpty { null },
                        installedAbsolutePath = item.optString("installedAbsolutePath", "").trim().ifEmpty { null },
                        status = status,
                        failureReason = failureReason,
                    ),
                )
            }
        }
    }

}

private fun Cursor.stringOrEmpty(columnName: String): String {
    val index = getColumnIndex(columnName)
    if (index < 0 || isNull(index)) {
        return ""
    }
    return getString(index)?.trim().orEmpty()
}

private fun Cursor.longOrZero(columnName: String): Long {
    val index = getColumnIndex(columnName)
    if (index < 0 || isNull(index)) {
        return 0L
    }
    return getLong(index)
}

private fun Cursor.longOrNull(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    if (index < 0 || isNull(index)) {
        return null
    }
    return getLong(index)
}
