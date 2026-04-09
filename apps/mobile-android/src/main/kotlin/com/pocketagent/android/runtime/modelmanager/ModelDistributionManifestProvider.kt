package com.pocketagent.android.runtime.modelmanager

import android.content.Context
import com.pocketagent.android.BuildConfig
import com.pocketagent.android.runtime.RuntimeDomainError
import com.pocketagent.android.runtime.RuntimeDomainException
import com.pocketagent.android.runtime.RuntimeErrorCodes
import com.pocketagent.core.model.ModelArtifactRole
import com.pocketagent.core.model.ModelSourceKind
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class ModelDistributionManifestProvider(
    private val context: Context?,
    private val endpointOverride: String? = null,
    private val bundledAssetPath: String = DEFAULT_BUNDLED_MANIFEST_ASSET,
    private val remoteManifestLoader: suspend (String) -> String = { endpoint ->
        fetchRemoteManifest(endpoint)
    },
    private val bundledManifestLoader: (() -> String?)? = null,
) {
    @Volatile
    private var cachedManifest: ModelDistributionManifest? = null

    suspend fun loadManifest(): ModelDistributionManifest = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val bundled = loadBundledManifest()
        val endpoint = (endpointOverride ?: BuildConfig.MODEL_MANIFEST_URL).trim()
        if (endpoint.isEmpty()) {
            return@withContext bundled.copy(
                source = ManifestSource.BUNDLED,
                syncedAtEpochMs = now,
            ).also { manifest -> cachedManifest = manifest }
        }

        val remotePayload = runCatching { remoteManifestLoader(endpoint) }
        val remoteManifest = remotePayload.mapCatching { parseManifest(it) }
        if (remoteManifest.isSuccess) {
            val parsedRemote = remoteManifest.getOrThrow()
            val merged = mergeManifests(bundled, parsedRemote)
            return@withContext merged.copy(
                source = if (bundled.models.isEmpty()) {
                    ManifestSource.REMOTE
                } else {
                    ManifestSource.BUNDLED_AND_REMOTE
                },
                syncedAtEpochMs = now,
                lastError = mergeWarnings(bundled.lastError, parsedRemote.lastError),
            ).also { manifest -> cachedManifest = manifest }
        }

        val remoteError = remoteManifest.exceptionOrNull()?.message
            ?: remotePayload.exceptionOrNull()?.message
            ?: "unknown remote catalog error"
        return@withContext bundled.copy(
            source = ManifestSource.BUNDLED,
            syncedAtEpochMs = now,
            lastError = mergeWarnings(
                bundled.lastError,
                "MODEL_MANIFEST_REMOTE_FETCH_FAILED:$remoteError",
            ),
        ).also { manifest -> cachedManifest = manifest }
    }

    fun currentManifest(): ModelDistributionManifest {
        return cachedManifest ?: loadBundledManifest().also { manifest -> cachedManifest = manifest }
    }

    private fun loadBundledManifest(): ModelDistributionManifest {
        if (bundledManifestLoader == null && context == null) {
            return ModelDistributionManifest(
                models = emptyList(),
                source = ManifestSource.BUNDLED,
                lastError = "MODEL_MANIFEST_BUNDLED_UNAVAILABLE:context_unavailable",
            )
        }
        val payload = runCatching {
            bundledManifestLoader?.invoke()
                ?: context?.assets?.open(bundledAssetPath)?.bufferedReader()?.use { it.readText() }
        }.getOrElse {
            return ModelDistributionManifest(
                models = emptyList(),
                source = ManifestSource.BUNDLED,
                lastError = "MODEL_MANIFEST_BUNDLED_UNAVAILABLE:asset_missing:$bundledAssetPath",
            )
        }
        if (payload.isNullOrBlank()) {
            return ModelDistributionManifest(
                models = emptyList(),
                source = ManifestSource.BUNDLED,
                lastError = "MODEL_MANIFEST_BUNDLED_INVALID:asset_empty:$bundledAssetPath",
            )
        }
        return runCatching { parseManifest(payload) }.getOrElse { error ->
            ModelDistributionManifest(
                models = emptyList(),
                source = ManifestSource.BUNDLED,
                lastError = "MODEL_MANIFEST_BUNDLED_INVALID:parse_failed:${error.message ?: "invalid json"}",
            )
        }
    }

    private fun parseManifest(raw: String): ModelDistributionManifest {
        val root = JSONObject(raw)
        val modelsJson = root.optJSONArray("models") ?: JSONArray()
        val warnings = mutableListOf<String>()
        val byModelId = linkedMapOf<String, ParsedModelAccumulator>()
        for (index in 0 until modelsJson.length()) {
            val item = modelsJson.optJSONObject(index) ?: continue
            val modelId = item.optString("modelId", "").trim()
            val displayName = item.optString("displayName", modelId).trim()
            if (modelId.isEmpty()) {
                warnings += "Dropped manifest model entry at index=$index: missing modelId."
                continue
            }
            val accumulator = byModelId.getOrPut(modelId) {
                ParsedModelAccumulator(displayName = displayName.ifBlank { modelId })
            }
            if (accumulator.displayName != displayName && displayName.isNotBlank()) {
                warnings += "Model '$modelId' declared multiple display names; keeping '${accumulator.displayName}'."
            }
            val versionsJson = item.optJSONArray("versions") ?: JSONArray()
            for (v in 0 until versionsJson.length()) {
                val versionItem = versionsJson.optJSONObject(v) ?: continue
                val version = versionItem.optString("version", "").trim()
                val artifacts = parseArtifacts(modelId = modelId, version = version, versionItem = versionItem, warnings = warnings)
                val primaryArtifact = artifacts.firstOrNull { artifact -> artifact.role == ModelArtifactRole.PRIMARY_GGUF }
                val downloadUrl = primaryArtifact?.downloadUrl
                    ?: versionItem.optString("downloadUrl", "").trim()
                val sha = primaryArtifact?.expectedSha256
                    ?: versionItem.optString("expectedSha256", "").trim()
                val issuer = primaryArtifact?.provenanceIssuer
                    ?: versionItem.optString("provenanceIssuer", "").trim()
                val signature = primaryArtifact?.provenanceSignature
                    ?: versionItem.optString("provenanceSignature", "").trim()
                val verificationPolicyRaw = versionItem
                    .optString("verificationPolicy", DownloadVerificationPolicy.INTEGRITY_ONLY.name)
                    .trim()
                val runtimeCompatibility = primaryArtifact?.runtimeCompatibility ?: versionItem
                    .optString("runtimeCompatibility", "android-arm64-v8a")
                    .trim()
                    .ifEmpty { "android-arm64-v8a" }
                val fileSizeBytes = primaryArtifact?.fileSizeBytes ?: versionItem.optLong("fileSizeBytes", 0L)
                val promptProfileId = versionItem.optString("promptProfileId", "").trim().ifEmpty { null }
                val sourceKind = parseSourceKind(versionItem.optString("sourceKind", "").trim())
                    ?: ModelSourceKind.REMOTE_MANIFEST
                val verificationPolicy = parseVerificationPolicy(verificationPolicyRaw)
                    ?: run {
                        warnings += "Version '$modelId/$version' has invalid verificationPolicy '$verificationPolicyRaw'; using INTEGRITY_ONLY."
                        DownloadVerificationPolicy.INTEGRITY_ONLY
                    }
                val rejectionReason = validateVersionEntry(
                    version = version,
                    downloadUrl = downloadUrl,
                    expectedSha256 = sha,
                    fileSizeBytes = fileSizeBytes,
                )
                if (rejectionReason != null) {
                    warnings += "Dropped $modelId/$version: $rejectionReason"
                    continue
                }
                if (accumulator.versionsByVersion.containsKey(version)) {
                    warnings += "Dropped duplicate $modelId/$version entry from manifest."
                    continue
                }
                val resolvedArtifacts = if (artifacts.isNotEmpty()) {
                    artifacts
                } else {
                    listOf(
                        ModelDistributionArtifact(
                            artifactId = "$modelId::$version::primary",
                            role = ModelArtifactRole.PRIMARY_GGUF,
                            fileName = downloadUrl.substringAfterLast('/').ifBlank { "$modelId-$version.gguf" },
                            downloadUrl = downloadUrl,
                            expectedSha256 = sha,
                            provenanceIssuer = issuer,
                            provenanceSignature = signature,
                            runtimeCompatibility = runtimeCompatibility,
                            fileSizeBytes = fileSizeBytes,
                            verificationPolicy = verificationPolicy,
                        ),
                    )
                }
                if (resolvedArtifacts.none { artifact -> artifact.role == ModelArtifactRole.PRIMARY_GGUF }) {
                    warnings += "Dropped $modelId/$version: artifact list has no PRIMARY_GGUF artifact."
                    continue
                }
                accumulator.versionsByVersion[version] = ModelDistributionVersion(
                    modelId = modelId,
                    version = version,
                    downloadUrl = downloadUrl,
                    expectedSha256 = sha,
                    provenanceIssuer = issuer,
                    provenanceSignature = signature,
                    runtimeCompatibility = runtimeCompatibility,
                    fileSizeBytes = fileSizeBytes,
                    verificationPolicy = verificationPolicy,
                    sourceKind = sourceKind,
                    promptProfileId = promptProfileId,
                    artifacts = resolvedArtifacts,
                )
            }
        }
        val models = byModelId.entries.mapNotNull { (modelId, accumulator) ->
            val versions = accumulator.versionsByVersion.values.sortedWith { left, right ->
                compareVersionDescending(left.version, right.version)
            }
            if (versions.isEmpty()) {
                warnings += "Dropped model '$modelId': no valid versions."
                null
            } else {
                ModelDistributionModel(
                    modelId = modelId,
                    displayName = accumulator.displayName,
                    versions = versions,
                )
            }
        }
        return ModelDistributionManifest(
            models = models,
            lastError = summarizeWarnings(warnings),
        )
    }

    private data class ParsedModelAccumulator(
        val displayName: String,
        val versionsByVersion: LinkedHashMap<String, ModelDistributionVersion> = linkedMapOf(),
    )

    private fun mergeManifests(
        bundled: ModelDistributionManifest,
        remote: ModelDistributionManifest,
    ): ModelDistributionManifest {
        val bundledById = bundled.models.associateBy { it.modelId }
        val remoteById = remote.models.associateBy { it.modelId }
        val mergedModels = (bundledById.keys + remoteById.keys).sorted().mapNotNull { modelId ->
            val bundledModel = bundledById[modelId]
            val remoteModel = remoteById[modelId]
            val displayName = remoteModel?.displayName?.takeIf { it.isNotBlank() }
                ?: bundledModel?.displayName?.takeIf { it.isNotBlank() }
                ?: modelId
            val versionByKey = linkedMapOf<String, ModelDistributionVersion>()
            bundledModel?.versions?.forEach { version ->
                versionByKey[version.version] = version
            }
            remoteModel?.versions?.forEach { version ->
                versionByKey[version.version] = version
            }
            if (versionByKey.isEmpty()) {
                null
            } else {
                ModelDistributionModel(
                    modelId = modelId,
                    displayName = displayName,
                    versions = versionByKey.values.sortedWith { left, right ->
                        compareVersionDescending(left.version, right.version)
                    },
                )
            }
        }
        return ModelDistributionManifest(models = mergedModels)
    }

    private fun validateVersionEntry(
        version: String,
        downloadUrl: String,
        expectedSha256: String,
        fileSizeBytes: Long,
    ): String? {
        if (version.isBlank()) {
            return "missing version"
        }
        if (downloadUrl.isBlank()) {
            return "missing downloadUrl"
        }
        if (!isHttpsUrl(downloadUrl)) {
            return "downloadUrl must be HTTPS"
        }
        if (expectedSha256.isBlank()) {
            return "missing expectedSha256"
        }
        if (!SHA256_HEX_REGEX.matches(expectedSha256)) {
            return "expectedSha256 must be 64 lowercase/uppercase hex chars"
        }
        if (fileSizeBytes <= 0L) {
            return "fileSizeBytes must be > 0"
        }
        return null
    }

    private fun isHttpsUrl(url: String): Boolean {
        return runCatching {
            URL(url).protocol.equals("https", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun parseVerificationPolicy(raw: String): DownloadVerificationPolicy? {
        if (raw.isBlank()) {
            return DownloadVerificationPolicy.INTEGRITY_ONLY
        }
        val normalized = raw.trim().uppercase()
        return runCatching { DownloadVerificationPolicy.valueOf(normalized) }.getOrNull()
    }

    private fun parseArtifacts(
        modelId: String,
        version: String,
        versionItem: JSONObject,
        warnings: MutableList<String>,
    ): List<ModelDistributionArtifact> {
        val artifactsJson = versionItem.optJSONArray("artifacts") ?: return emptyList()
        val parsed = mutableListOf<ModelDistributionArtifact>()
        for (index in 0 until artifactsJson.length()) {
            val artifactItem = artifactsJson.optJSONObject(index) ?: continue
            val role = parseArtifactRole(artifactItem.optString("role", "").trim())
            if (role == null) {
                warnings += "Dropped artifact at index=$index for $modelId/$version: unknown or missing role."
                continue
            }
            val downloadUrl = artifactItem.optString("downloadUrl", "").trim()
            val expectedSha256 = artifactItem.optString("expectedSha256", "").trim()
            val fileSizeBytes = artifactItem.optLong("fileSizeBytes", 0L)
            val required = artifactItem.optBoolean("required", true)
            val rejectionReason = validateArtifactEntry(
                role = role,
                downloadUrl = downloadUrl,
                expectedSha256 = expectedSha256,
                fileSizeBytes = fileSizeBytes,
            )
            if (rejectionReason != null) {
                val severity = if (required) "required" else "optional"
                warnings += "Dropped $severity artifact role=${role.name} for $modelId/$version: $rejectionReason."
                continue
            }
            val fileName = artifactItem.optString("fileName", "").trim()
                .ifEmpty { downloadUrl.substringAfterLast('/').trim() }
            val verificationPolicy = parseVerificationPolicy(
                artifactItem.optString("verificationPolicy", DownloadVerificationPolicy.INTEGRITY_ONLY.name),
            ) ?: DownloadVerificationPolicy.INTEGRITY_ONLY
            parsed += ModelDistributionArtifact(
                artifactId = artifactItem.optString("artifactId", "").trim()
                    .ifEmpty { "$modelId::$version::${role.name.lowercase()}" },
                role = role,
                fileName = fileName,
                downloadUrl = downloadUrl,
                expectedSha256 = expectedSha256,
                provenanceIssuer = artifactItem.optString("provenanceIssuer", "").trim(),
                provenanceSignature = artifactItem.optString("provenanceSignature", "").trim(),
                runtimeCompatibility = artifactItem.optString("runtimeCompatibility", "android-arm64-v8a").trim(),
                fileSizeBytes = fileSizeBytes,
                required = required,
                verificationPolicy = verificationPolicy,
            )
        }
        return parsed
    }

    private fun validateArtifactEntry(
        role: ModelArtifactRole,
        downloadUrl: String,
        expectedSha256: String,
        fileSizeBytes: Long,
    ): String? {
        if (downloadUrl.isBlank()) {
            return "missing downloadUrl"
        }
        if (!isHttpsUrl(downloadUrl)) {
            return "downloadUrl must be HTTPS"
        }
        if (expectedSha256.isBlank()) {
            return "missing expectedSha256"
        }
        if (!SHA256_HEX_REGEX.matches(expectedSha256)) {
            return "expectedSha256 must be 64 lowercase/uppercase hex chars"
        }
        if (fileSizeBytes <= 0L) {
            return "fileSizeBytes must be > 0"
        }
        return null
    }

    private fun parseArtifactRole(raw: String): ModelArtifactRole? {
        if (raw.isBlank()) {
            return null
        }
        return runCatching { ModelArtifactRole.valueOf(raw.trim().uppercase()) }.getOrNull()
    }

    private fun parseSourceKind(raw: String): ModelSourceKind? {
        if (raw.isBlank()) {
            return null
        }
        return runCatching { ModelSourceKind.valueOf(raw.trim().uppercase()) }.getOrNull()
    }

    private fun mergeWarnings(vararg warnings: String?): String? {
        val merged = warnings
            .mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }
            .distinct()
        return summarizeWarnings(merged)
    }

    private fun summarizeWarnings(warnings: List<String>): String? {
        if (warnings.isEmpty()) {
            return null
        }
        val visible = warnings.take(MAX_WARNING_MESSAGES)
        val suffix = if (warnings.size > visible.size) {
            " (+${warnings.size - visible.size} more)"
        } else {
            ""
        }
        return visible.joinToString(separator = " | ") + suffix
    }

    private fun compareVersionDescending(left: String, right: String): Int {
        return compareVersionAscending(right, left)
    }

    private fun compareVersionAscending(left: String, right: String): Int {
        val leftTokens = tokenizeVersion(left)
        val rightTokens = tokenizeVersion(right)
        val sharedSize = minOf(leftTokens.size, rightTokens.size)
        for (index in 0 until sharedSize) {
            val tokenCompare = compareVersionToken(leftTokens[index], rightTokens[index])
            if (tokenCompare != 0) {
                return tokenCompare
            }
        }
        if (leftTokens.size != rightTokens.size) {
            return leftTokens.size.compareTo(rightTokens.size)
        }
        val caseInsensitive = left.compareTo(right, ignoreCase = true)
        if (caseInsensitive != 0) {
            return caseInsensitive
        }
        return left.compareTo(right)
    }

    private fun tokenizeVersion(version: String): List<String> {
        val tokens = VERSION_TOKEN_REGEX.findAll(version).map { match -> match.value }.toList()
        return if (tokens.isEmpty()) listOf(version) else tokens
    }

    private fun compareVersionToken(left: String, right: String): Int {
        val leftNumeric = left.all { it.isDigit() }
        val rightNumeric = right.all { it.isDigit() }
        if (leftNumeric && rightNumeric) {
            return compareNumericToken(left, right)
        }
        if (leftNumeric != rightNumeric) {
            return if (leftNumeric) 1 else -1
        }
        return left.compareTo(right, ignoreCase = true)
    }

    private fun compareNumericToken(left: String, right: String): Int {
        val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
        val normalizedRight = right.trimStart('0').ifEmpty { "0" }
        if (normalizedLeft.length != normalizedRight.length) {
            return normalizedLeft.length.compareTo(normalizedRight.length)
        }
        return normalizedLeft.compareTo(normalizedRight)
    }

    companion object {
        private const val DEFAULT_BUNDLED_MANIFEST_ASSET = "model-distribution-catalog.json"
        private const val MAX_WARNING_MESSAGES = 6
        private val SHA256_HEX_REGEX = Regex("^[a-fA-F0-9]{64}$")
        private val VERSION_TOKEN_REGEX = Regex("[A-Za-z]+|\\d+")

        private fun fetchRemoteManifest(endpoint: String): String {
            val request = Request.Builder()
                .get()
                .url(endpoint)
                .build()
            DownloadHttpClient.base().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RuntimeDomainException(
                        domainError = RuntimeDomainError(
                            code = RuntimeErrorCodes.MODEL_MANIFEST_HTTP_ERROR,
                            userMessage = "Model catalog refresh failed. Falling back to bundled catalog.",
                            technicalDetail = "endpoint=$endpoint;http=${response.code}",
                        ),
                    )
                }
                val body = response.body ?: throw RuntimeDomainException(
                    domainError = RuntimeDomainError(
                        code = RuntimeErrorCodes.MODEL_MANIFEST_HTTP_ERROR,
                        userMessage = "Model catalog refresh failed. Falling back to bundled catalog.",
                        technicalDetail = "endpoint=$endpoint;http=${response.code};empty_body",
                    ),
                )
                return body.string()
            }
        }
    }
}
