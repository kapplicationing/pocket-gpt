package com.pocketagent.android.runtime.huggingface

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HuggingFaceRecentModel(
    val id: String,
    val repoId: String,
    val revision: String,
    val filePath: String,
    val targetModelId: String,
    val targetDisplayName: String,
    val version: String,
    val displayName: String,
    val sha256: String,
    val sizeBytes: Long,
    val validatedAtEpochMs: Long,
    val lastDownloadEnqueuedAtEpochMs: Long,
) {
    val originUrl: String
        get() = HuggingFaceModelReference(
            repoId = repoId,
            revision = revision,
            filePath = filePath,
        ).canonicalResolveUrl
}

interface HuggingFaceRecentModelStore {
    fun list(): List<HuggingFaceRecentModel>

    fun upsert(candidate: HuggingFaceCandidate, enqueuedAtEpochMs: Long)

    object None : HuggingFaceRecentModelStore {
        override fun list(): List<HuggingFaceRecentModel> = emptyList()

        override fun upsert(candidate: HuggingFaceCandidate, enqueuedAtEpochMs: Long) = Unit
    }
}

class SharedPreferencesHuggingFaceRecentModelStore(
    context: Context,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : HuggingFaceRecentModelStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun list(): List<HuggingFaceRecentModel> {
        val raw = preferences.getString(KEY_RECENT_MODELS, null) ?: return emptyList()
        return decodeRecentModels(raw)
            .sortedByDescending { model -> model.lastDownloadEnqueuedAtEpochMs }
            .take(maxEntries)
    }

    override fun upsert(candidate: HuggingFaceCandidate, enqueuedAtEpochMs: Long) {
        val next = list()
            .filterNot { model -> model.id == candidate.recentModelId() }
            .plus(candidate.toRecentModel(enqueuedAtEpochMs))
            .sortedByDescending { model -> model.lastDownloadEnqueuedAtEpochMs }
            .take(maxEntries)
        preferences.edit()
            .putString(KEY_RECENT_MODELS, encodeRecentModels(next))
            .apply()
    }

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 12
        private const val PREFERENCES_NAME = "hugging_face_recent_models"
        private const val KEY_RECENT_MODELS = "recent_models"
    }
}

fun HuggingFaceCandidate.toRecentModel(enqueuedAtEpochMs: Long): HuggingFaceRecentModel {
    return HuggingFaceRecentModel(
        id = recentModelId(),
        repoId = reference.repoId,
        revision = reference.revision,
        filePath = reference.filePath,
        targetModelId = target.modelId,
        targetDisplayName = target.displayName,
        version = version.version,
        displayName = displayName,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        validatedAtEpochMs = enqueuedAtEpochMs,
        lastDownloadEnqueuedAtEpochMs = enqueuedAtEpochMs,
    )
}

internal fun encodeRecentModels(models: List<HuggingFaceRecentModel>): String {
    val array = JSONArray()
    models.forEach { model ->
        array.put(
            JSONObject()
                .put("id", model.id)
                .put("repoId", model.repoId)
                .put("revision", model.revision)
                .put("filePath", model.filePath)
                .put("targetModelId", model.targetModelId)
                .put("targetDisplayName", model.targetDisplayName)
                .put("version", model.version)
                .put("displayName", model.displayName)
                .put("sha256", model.sha256)
                .put("sizeBytes", model.sizeBytes)
                .put("validatedAtEpochMs", model.validatedAtEpochMs)
                .put("lastDownloadEnqueuedAtEpochMs", model.lastDownloadEnqueuedAtEpochMs),
        )
    }
    return array.toString()
}

internal fun decodeRecentModels(raw: String): List<HuggingFaceRecentModel> {
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val json = array.optJSONObject(index) ?: return@mapNotNull null
        val repoId = json.optString("repoId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val revision = json.optString("revision").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val filePath = json.optString("filePath").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val targetModelId = json.optString("targetModelId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val sha256 = json.optString("sha256").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val sizeBytes = json.optLong("sizeBytes", -1L).takeIf { it > 0L } ?: return@mapNotNull null
        val enqueuedAt = json.optLong("lastDownloadEnqueuedAtEpochMs", -1L)
            .takeIf { it > 0L } ?: return@mapNotNull null
        HuggingFaceRecentModel(
            id = json.optString("id").takeIf { it.isNotBlank() }
                ?: buildRecentModelId(
                    repoId = repoId,
                    revision = revision,
                    filePath = filePath,
                    targetModelId = targetModelId,
                ),
            repoId = repoId,
            revision = revision,
            filePath = filePath,
            targetModelId = targetModelId,
            targetDisplayName = json.optString("targetDisplayName").takeIf { it.isNotBlank() } ?: targetModelId,
            version = json.optString("version").takeIf { it.isNotBlank() } ?: "hf-${sha256.take(12)}",
            displayName = json.optString("displayName").takeIf { it.isNotBlank() } ?: "$repoId / ${filePath.substringAfterLast('/')}",
            sha256 = sha256,
            sizeBytes = sizeBytes,
            validatedAtEpochMs = json.optLong("validatedAtEpochMs", enqueuedAt).takeIf { it > 0L } ?: enqueuedAt,
            lastDownloadEnqueuedAtEpochMs = enqueuedAt,
        )
    }
}

private fun HuggingFaceCandidate.recentModelId(): String {
    return buildRecentModelId(
        repoId = reference.repoId,
        revision = reference.revision,
        filePath = reference.filePath,
        targetModelId = target.modelId,
    )
}

private fun buildRecentModelId(
    repoId: String,
    revision: String,
    filePath: String,
    targetModelId: String,
): String {
    return "$targetModelId|$repoId|$revision|$filePath"
}
