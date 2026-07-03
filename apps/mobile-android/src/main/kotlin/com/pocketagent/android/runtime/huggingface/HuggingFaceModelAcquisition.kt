package com.pocketagent.android.runtime.huggingface

import com.pocketagent.android.BuildConfig
import com.pocketagent.core.model.CapabilityFlag
import com.pocketagent.core.model.ModelArtifactRole
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.core.model.ModelSourceRef
import com.pocketagent.core.model.SourceTrustPolicy
import com.pocketagent.android.runtime.modelmanager.DownloadHttpClient
import com.pocketagent.android.runtime.modelmanager.DownloadVerificationPolicy
import com.pocketagent.android.runtime.modelmanager.ModelDistributionArtifact
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.inference.ModelCatalog
import java.io.IOException
import java.net.URI
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class HuggingFaceModelReference(
    val repoId: String,
    val revision: String,
    val filePath: String,
) {
    val fileName: String
        get() = filePath.substringAfterLast('/')

    val publisher: String
        get() = repoId.substringBefore('/')

    val canonicalResolveUrl: String
        get() = buildHuggingFaceUrl(
            repoId = repoId,
            revision = revision,
            filePath = filePath,
            marker = "resolve",
        )

    val modelCardUrl: String
        get() = buildHuggingFaceRepoUrl(repoId)

    companion object {
        fun parse(input: String): HuggingFaceModelReference {
            val trimmed = input.trim()
            if (trimmed.isBlank()) {
                throw HuggingFaceAcquisitionException(
                    reason = HuggingFaceAcquisitionBlockReason.INVALID_URL,
                    userMessage = "Paste a Hugging Face file URL ending in .gguf.",
                )
            }
            val uri = runCatching { URI(trimmed) }.getOrNull()
                ?: throw HuggingFaceAcquisitionException(
                    reason = HuggingFaceAcquisitionBlockReason.INVALID_URL,
                    userMessage = "Paste a Hugging Face file URL ending in .gguf.",
                )
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                !uri.host.equals("huggingface.co", ignoreCase = true)
            ) {
                throw HuggingFaceAcquisitionException(
                    reason = HuggingFaceAcquisitionBlockReason.INVALID_URL,
                    userMessage = "Paste a Hugging Face file URL ending in .gguf.",
                )
            }
            val segments = uri.path.trim('/').split('/').filter { it.isNotBlank() }
            val markerIndex = segments.indexOfFirst { it == "resolve" || it == "blob" }
            if (markerIndex < 2 || markerIndex + 2 >= segments.size) {
                throw HuggingFaceAcquisitionException(
                    reason = HuggingFaceAcquisitionBlockReason.INVALID_URL,
                    userMessage = "Paste a Hugging Face file URL ending in .gguf.",
                )
            }
            val repoId = segments.take(markerIndex).joinToString("/")
            val revision = segments[markerIndex + 1]
            val filePath = segments.drop(markerIndex + 2).joinToString("/")
            val reference = HuggingFaceModelReference(
                repoId = repoId,
                revision = revision,
                filePath = filePath,
            )
            if (!reference.fileName.endsWith(".gguf", ignoreCase = true)) {
                throw HuggingFaceAcquisitionException(
                    reason = HuggingFaceAcquisitionBlockReason.NON_GGUF,
                    userMessage = "This URL is not a GGUF model file. Choose a .gguf file.",
                )
            }
            if (SHARDED_GGUF_REGEX.matches(reference.fileName)) {
                throw HuggingFaceAcquisitionException(
                    reason = HuggingFaceAcquisitionBlockReason.SHARDED_GGUF,
                    userMessage = "Sharded GGUF files are not supported yet. Choose a single .gguf file.",
                )
            }
            return reference
        }
    }
}

data class HuggingFaceTargetModel(
    val modelId: String,
    val displayName: String,
)

data class HuggingFaceHubFileMetadata(
    val path: String,
    val sizeBytes: Long?,
    val lfsOid: String?,
)

data class HuggingFaceHubRepositoryMetadata(
    val modelCardUrl: String,
    val license: String?,
    val licenseUrl: String?,
)

data class HuggingFaceSearchFileResult(
    val reference: HuggingFaceModelReference,
    val displayName: String,
    val modelCardUrl: String,
    val downloads: Long?,
    val likes: Long?,
    val license: String?,
    val gated: Boolean,
    val private: Boolean,
) {
    val canonicalUrl: String
        get() = reference.canonicalResolveUrl
}

data class HuggingFaceCandidate(
    val reference: HuggingFaceModelReference,
    val target: HuggingFaceTargetModel,
    val displayName: String,
    val sha256: String,
    val sizeBytes: Long,
    val version: ModelDistributionVersion,
    val modelCardUrl: String = reference.modelCardUrl,
    val license: String? = null,
    val licenseUrl: String? = null,
)

enum class HuggingFaceAcquisitionBlockReason {
    INVALID_URL,
    NON_GGUF,
    SHARDED_GGUF,
    ACCESS_DENIED,
    FILE_NOT_FOUND,
    MISSING_SHA,
    MISSING_SIZE,
    UNSUPPORTED_MODEL,
    COMPANION_ARTIFACT_REQUIRED,
    NETWORK_ERROR,
}

class HuggingFaceAcquisitionException(
    val reason: HuggingFaceAcquisitionBlockReason,
    val userMessage: String,
    cause: Throwable? = null,
) : IllegalStateException(userMessage, cause)

interface HuggingFaceHubClient {
    suspend fun lookupFile(reference: HuggingFaceModelReference): HuggingFaceHubFileMetadata

    suspend fun lookupRepository(reference: HuggingFaceModelReference): HuggingFaceHubRepositoryMetadata? = null

    suspend fun searchFiles(query: String, limit: Int): List<HuggingFaceSearchFileResult> = emptyList()
}

interface HuggingFaceEndpointAdapter {
    fun treeApiUrl(reference: HuggingFaceModelReference): HttpUrl

    fun modelInfoApiUrl(reference: HuggingFaceModelReference): HttpUrl

    fun modelSearchApiUrl(query: String, limit: Int): HttpUrl

    fun artifactDownloadUrl(reference: HuggingFaceModelReference): String
}

object RealHuggingFaceEndpointAdapter : HuggingFaceEndpointAdapter {
    override fun treeApiUrl(reference: HuggingFaceModelReference): HttpUrl {
        val builder = HUGGING_FACE_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addPathSegment("models")
        reference.repoId.split('/').forEach(builder::addPathSegment)
        builder
            .addPathSegment("tree")
            .addPathSegment(reference.revision)
        reference.filePath.substringBeforeLast('/', missingDelimiterValue = "")
            .split('/')
            .filter { segment -> segment.isNotBlank() }
            .forEach(builder::addPathSegment)
        return builder.build()
    }

    override fun modelInfoApiUrl(reference: HuggingFaceModelReference): HttpUrl {
        val builder = HUGGING_FACE_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addPathSegment("models")
        reference.repoId.split('/').forEach(builder::addPathSegment)
        builder
            .addPathSegment("revision")
            .addPathSegment(reference.revision)
        return builder.build()
    }

    override fun modelSearchApiUrl(query: String, limit: Int): HttpUrl {
        return HUGGING_FACE_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addPathSegment("models")
            .addQueryParameter("search", query)
            .addQueryParameter("limit", limit.coerceIn(1, MAX_SEARCH_RESULTS).toString())
            .addQueryParameter("full", "true")
            .build()
    }

    override fun artifactDownloadUrl(reference: HuggingFaceModelReference): String {
        return reference.canonicalResolveUrl
    }
}

class FixtureHuggingFaceEndpointAdapter(
    private val baseUrl: HttpUrl,
) : HuggingFaceEndpointAdapter {
    override fun treeApiUrl(reference: HuggingFaceModelReference): HttpUrl {
        val builder = baseUrl.newBuilder()
            .addPathSegment("api")
            .addPathSegment("models")
        reference.repoId.split('/').forEach(builder::addPathSegment)
        builder
            .addPathSegment("tree")
            .addPathSegment(reference.revision)
        reference.filePath.substringBeforeLast('/', missingDelimiterValue = "")
            .split('/')
            .filter { segment -> segment.isNotBlank() }
            .forEach(builder::addPathSegment)
        return builder.build()
    }

    override fun modelInfoApiUrl(reference: HuggingFaceModelReference): HttpUrl {
        val builder = baseUrl.newBuilder()
            .addPathSegment("api")
            .addPathSegment("models")
        reference.repoId.split('/').forEach(builder::addPathSegment)
        builder
            .addPathSegment("revision")
            .addPathSegment(reference.revision)
        return builder.build()
    }

    override fun modelSearchApiUrl(query: String, limit: Int): HttpUrl {
        return baseUrl.newBuilder()
            .addPathSegment("api")
            .addPathSegment("models")
            .addQueryParameter("search", query)
            .addQueryParameter("limit", limit.coerceIn(1, MAX_SEARCH_RESULTS).toString())
            .addQueryParameter("full", "true")
            .build()
    }

    override fun artifactDownloadUrl(reference: HuggingFaceModelReference): String {
        val builder = baseUrl.newBuilder()
        reference.repoId.split('/').forEach(builder::addPathSegment)
        builder
            .addPathSegment("resolve")
            .addPathSegment(reference.revision)
        reference.filePath.split('/').forEach(builder::addPathSegment)
        return builder.build().toString()
    }
}

fun configuredHuggingFaceEndpointAdapter(
    fixtureBaseUrl: String = BuildConfig.HF_FIXTURE_BASE_URL,
): HuggingFaceEndpointAdapter {
    val normalized = fixtureBaseUrl.trim()
    if (normalized.isBlank()) {
        return RealHuggingFaceEndpointAdapter
    }
    return FixtureHuggingFaceEndpointAdapter(normalized.toHttpUrl())
}

class OkHttpHuggingFaceHubClient(
    private val endpointAdapter: HuggingFaceEndpointAdapter = configuredHuggingFaceEndpointAdapter(),
) : HuggingFaceHubClient {
    override suspend fun lookupFile(reference: HuggingFaceModelReference): HuggingFaceHubFileMetadata {
        val request = Request.Builder()
            .get()
            .url(endpointAdapter.treeApiUrl(reference))
            .build()
        return runCatching {
            DownloadHttpClient.metadata().newCall(request).execute().use { response ->
                when (response.code) {
                    401, 403 -> throw HuggingFaceAcquisitionException(
                        reason = HuggingFaceAcquisitionBlockReason.ACCESS_DENIED,
                        userMessage = "Private or gated Hugging Face files are not supported in this version.",
                    )
                    404 -> throw HuggingFaceAcquisitionException(
                        reason = HuggingFaceAcquisitionBlockReason.FILE_NOT_FOUND,
                        userMessage = "The Hugging Face file was not found: ${reference.filePath}.",
                    )
                }
                if (!response.isSuccessful) {
                    throw IOException("Hugging Face returned HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                parseTreeResponse(reference, body)
            }
        }.getOrElse { error ->
            if (error is HuggingFaceAcquisitionException) {
                throw error
            }
            throw HuggingFaceAcquisitionException(
                reason = HuggingFaceAcquisitionBlockReason.NETWORK_ERROR,
                userMessage = "Could not check ${reference.fileName}. Check the URL and network, then try again.",
                cause = error,
            )
        }
    }

    override suspend fun lookupRepository(
        reference: HuggingFaceModelReference,
    ): HuggingFaceHubRepositoryMetadata? {
        val request = Request.Builder()
            .get()
            .url(endpointAdapter.modelInfoApiUrl(reference))
            .build()
        return runCatching {
            DownloadHttpClient.metadata().newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    parseModelInfoResponse(reference, response.body?.string().orEmpty())
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    override suspend fun searchFiles(query: String, limit: Int): List<HuggingFaceSearchFileResult> {
        val request = Request.Builder()
            .get()
            .url(endpointAdapter.modelSearchApiUrl(query, limit))
            .build()
        return runCatching {
            DownloadHttpClient.metadata().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Hugging Face returned HTTP ${response.code}")
                }
                parseModelSearchResponse(response.body?.string().orEmpty(), limit)
            }
        }.getOrElse { error ->
            throw HuggingFaceAcquisitionException(
                reason = HuggingFaceAcquisitionBlockReason.NETWORK_ERROR,
                userMessage = "Could not search Hugging Face. Check the network, then try again.",
                cause = error,
            )
        }
    }
}

interface HuggingFaceModelAcquisition {
    fun supportedTargets(): List<HuggingFaceTargetModel>

    suspend fun searchFiles(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<HuggingFaceSearchFileResult>

    suspend fun resolveCandidate(
        input: String,
        targetModelId: String,
    ): HuggingFaceCandidate
}

class DefaultHuggingFaceModelAcquisition(
    private val endpointAdapter: HuggingFaceEndpointAdapter = configuredHuggingFaceEndpointAdapter(),
    private val hubClient: HuggingFaceHubClient = OkHttpHuggingFaceHubClient(endpointAdapter),
) : HuggingFaceModelAcquisition {
    override fun supportedTargets(): List<HuggingFaceTargetModel> {
        return supportedTextTargets()
    }

    override suspend fun searchFiles(query: String, limit: Int): List<HuggingFaceSearchFileResult> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return emptyList()
        }
        return hubClient.searchFiles(trimmed, limit.coerceIn(1, MAX_SEARCH_RESULTS))
    }

    override suspend fun resolveCandidate(
        input: String,
        targetModelId: String,
    ): HuggingFaceCandidate {
        val target = supportedTextTargets().firstOrNull { it.modelId == targetModelId }
            ?: throw unsupportedTargetException(targetModelId)
        val reference = HuggingFaceModelReference.parse(input)
        val metadata = hubClient.lookupFile(reference)
        val sha = metadata.lfsOid?.trim()?.lowercase(Locale.US).orEmpty()
        if (!SHA256_REGEX.matches(sha)) {
            throw HuggingFaceAcquisitionException(
                reason = HuggingFaceAcquisitionBlockReason.MISSING_SHA,
                userMessage = "This file does not expose a usable Hugging Face LFS checksum, so PocketGPT cannot verify the download.",
            )
        }
        val sizeBytes = metadata.sizeBytes?.takeIf { it > 0L }
            ?: throw HuggingFaceAcquisitionException(
                reason = HuggingFaceAcquisitionBlockReason.MISSING_SIZE,
                userMessage = "This file does not expose a usable file size, so PocketGPT cannot check storage before download.",
            )
        val repositoryMetadata = runCatching { hubClient.lookupRepository(reference) }.getOrNull()
        val spec = ModelCatalog.normalizedSpecFor(target.modelId)
        val promptProfileId = spec?.promptProfile?.profileId
        val versionId = "hf-${safeVersionToken(reference.fileName.substringBeforeLast('.'))}-${sha.take(12)}"
        val displayName = "${reference.repoId} / ${reference.fileName}"
        val sourceRef = ModelSourceRef(
            kind = ModelSourceKind.HUGGING_FACE,
            originId = target.modelId,
            publisher = reference.publisher,
            repository = reference.repoId,
            trustPolicy = SourceTrustPolicy.INTEGRITY_ONLY,
            revision = reference.revision,
            originUrl = reference.canonicalResolveUrl,
        )
        val artifact = ModelDistributionArtifact(
            artifactId = "${target.modelId}::$versionId::primary",
            role = ModelArtifactRole.PRIMARY_GGUF,
            fileName = reference.fileName,
            downloadUrl = endpointAdapter.artifactDownloadUrl(reference),
            expectedSha256 = sha,
            provenanceIssuer = "huggingface:${reference.repoId}",
            provenanceSignature = "",
            runtimeCompatibility = DEFAULT_RUNTIME_COMPATIBILITY,
            fileSizeBytes = sizeBytes,
            required = true,
            verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
        )
        val version = ModelDistributionVersion(
            modelId = target.modelId,
            version = versionId,
            downloadUrl = artifact.downloadUrl,
            expectedSha256 = sha,
            provenanceIssuer = artifact.provenanceIssuer,
            provenanceSignature = "",
            runtimeCompatibility = DEFAULT_RUNTIME_COMPATIBILITY,
            fileSizeBytes = sizeBytes,
            verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
            sourceKind = ModelSourceKind.HUGGING_FACE,
            promptProfileId = promptProfileId,
            displayName = displayName,
            sourceRef = sourceRef,
            artifacts = listOf(artifact),
        )
        return HuggingFaceCandidate(
            reference = reference,
            target = target,
            displayName = displayName,
            sha256 = sha,
            sizeBytes = sizeBytes,
            version = version,
            modelCardUrl = repositoryMetadata?.modelCardUrl ?: reference.modelCardUrl,
            license = repositoryMetadata?.license,
            licenseUrl = repositoryMetadata?.licenseUrl,
        )
    }

    private fun supportedTextTargets(): List<HuggingFaceTargetModel> {
        return ModelCatalog.bridgeSupportedModels().mapNotNull { modelId ->
            val spec = ModelCatalog.normalizedSpecFor(modelId) ?: return@mapNotNull null
            val requiredCompanions = spec.variants.flatMap { variant ->
                variant.artifactBundle.requiredArtifacts()
                    .filter { artifact -> artifact.role != ModelArtifactRole.PRIMARY_GGUF }
            }
            if (requiredCompanions.isNotEmpty() || spec.capabilities.supports(CapabilityFlag.IMAGE)) {
                return@mapNotNull null
            }
            HuggingFaceTargetModel(
                modelId = modelId,
                displayName = spec.displayName.takeIf { it.isNotBlank() } ?: modelId,
            )
        }
    }

    private fun unsupportedTargetException(targetModelId: String): HuggingFaceAcquisitionException {
        val spec = ModelCatalog.normalizedSpecFor(targetModelId)
        val requiredCompanions = spec?.variants.orEmpty().flatMap { variant ->
            variant.artifactBundle.requiredArtifacts()
                .filter { artifact -> artifact.role != ModelArtifactRole.PRIMARY_GGUF }
        }
        return if (requiredCompanions.isNotEmpty() || spec?.capabilities?.supports(CapabilityFlag.IMAGE) == true) {
            HuggingFaceAcquisitionException(
                reason = HuggingFaceAcquisitionBlockReason.COMPANION_ARTIFACT_REQUIRED,
                userMessage = "This model needs extra companion files. PocketGPT only supports single-file text GGUF downloads here.",
            )
        } else {
            HuggingFaceAcquisitionException(
                reason = HuggingFaceAcquisitionBlockReason.UNSUPPORTED_MODEL,
                userMessage = "Choose a supported text model target before checking this file.",
            )
        }
    }
}

internal fun HuggingFaceModelReference.treeApiUrl(): HttpUrl {
    return RealHuggingFaceEndpointAdapter.treeApiUrl(this)
}

private fun parseTreeResponse(
    reference: HuggingFaceModelReference,
    raw: String,
): HuggingFaceHubFileMetadata {
    val trimmed = raw.trim()
    val item = when {
        trimmed.startsWith("[") -> {
            val array = JSONArray(trimmed)
            (0 until array.length())
                .asSequence()
                .mapNotNull { index -> array.optJSONObject(index) }
                .firstOrNull { json ->
                    json.optString("path", "").trim() == reference.filePath ||
                        json.optString("rfilename", "").trim() == reference.filePath ||
                        json.optString("path", "").trim().endsWith("/${reference.fileName}")
                }
                ?: array.optJSONObject(0)
        }
        trimmed.startsWith("{") -> JSONObject(trimmed)
        else -> null
    } ?: throw HuggingFaceAcquisitionException(
        reason = HuggingFaceAcquisitionBlockReason.FILE_NOT_FOUND,
        userMessage = "The Hugging Face file was not found: ${reference.filePath}.",
    )
    val lfs = item.optJSONObject("lfs")
    return HuggingFaceHubFileMetadata(
        path = item.optString("path", reference.filePath).trim().ifEmpty { reference.filePath },
        sizeBytes = lfs?.optLong("size", -1L)?.takeIf { it > 0L }
            ?: item.optLong("size", -1L).takeIf { it > 0L },
        lfsOid = lfs?.optString("oid", "")?.trim()?.ifEmpty { null },
    )
}

private fun parseModelInfoResponse(
    reference: HuggingFaceModelReference,
    raw: String,
): HuggingFaceHubRepositoryMetadata {
    val json = runCatching { JSONObject(raw.trim()) }.getOrNull()
    val cardData = json?.optJSONObject("cardData")
    val license = firstNonBlank(
        cardData?.optStringValue("license"),
        json?.optStringValue("license"),
        json?.optJSONArray("tags")?.licenseFromTags(),
    )
    val licenseUrl = firstNonBlank(
        cardData?.optStringValue("license_link"),
        cardData?.optStringValue("licenseLink"),
        cardData?.optStringValue("license_url"),
        cardData?.optStringValue("licenseUrl"),
        json?.optStringValue("license_link"),
        json?.optStringValue("licenseLink"),
        json?.optStringValue("license_url"),
        json?.optStringValue("licenseUrl"),
    )
    return HuggingFaceHubRepositoryMetadata(
        modelCardUrl = json?.optStringValue("cardUrl") ?: reference.modelCardUrl,
        license = license,
        licenseUrl = licenseUrl,
    )
}

private fun parseModelSearchResponse(
    raw: String,
    limit: Int,
): List<HuggingFaceSearchFileResult> {
    val array = runCatching { JSONArray(raw.trim()) }.getOrNull() ?: return emptyList()
    val results = mutableListOf<HuggingFaceSearchFileResult>()
    for (index in 0 until array.length()) {
        val repo = array.optJSONObject(index) ?: continue
        val repoId = repo.optStringValue("id")
            ?: repo.optStringValue("modelId")
            ?: continue
        val gated = repo.optGated()
        val private = repo.optBoolean("private", false)
        val cardData = repo.optJSONObject("cardData")
        val license = firstNonBlank(
            cardData?.optStringValue("license"),
            repo.optStringValue("license"),
            repo.optJSONArray("tags")?.licenseFromTags(),
        )
        val siblings = repo.optJSONArray("siblings") ?: JSONArray()
        for (siblingIndex in 0 until siblings.length()) {
            val sibling = siblings.optJSONObject(siblingIndex) ?: continue
            val filePath = firstNonBlank(
                sibling.optStringValue("rfilename"),
                sibling.optStringValue("path"),
            ) ?: continue
            val fileName = filePath.substringAfterLast('/')
            if (!fileName.endsWith(".gguf", ignoreCase = true) || SHARDED_GGUF_REGEX.matches(fileName)) {
                continue
            }
            val reference = HuggingFaceModelReference(
                repoId = repoId,
                revision = DEFAULT_SEARCH_REVISION,
                filePath = filePath,
            )
            results += HuggingFaceSearchFileResult(
                reference = reference,
                displayName = "$repoId / $fileName",
                modelCardUrl = buildHuggingFaceRepoUrl(repoId),
                downloads = repo.optNullableLong("downloads"),
                likes = repo.optNullableLong("likes"),
                license = license,
                gated = gated,
                private = private,
            )
            if (results.size >= limit.coerceIn(1, MAX_SEARCH_RESULTS)) {
                return results
            }
        }
    }
    return results
}

private fun JSONObject.optStringValue(name: String): String? {
    return when (val value = opt(name)) {
        is String -> value.trim().takeIf { it.isNotBlank() }
        is JSONArray -> (0 until value.length())
            .asSequence()
            .mapNotNull { index -> value.optString(index).trim().takeIf { it.isNotBlank() } }
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
        else -> null
    }
}

private fun JSONObject.optNullableLong(name: String): Long? {
    return when (val value = opt(name)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }?.takeIf { it >= 0L }
}

private fun JSONObject.optGated(): Boolean {
    return when (val value = opt("gated")) {
        is Boolean -> value
        is String -> value.isNotBlank() && !value.equals("false", ignoreCase = true)
        else -> false
    }
}

private fun JSONArray.licenseFromTags(): String? {
    return (0 until length())
        .asSequence()
        .mapNotNull { index -> optString(index).trim().takeIf { it.isNotBlank() } }
        .firstOrNull { tag -> tag.startsWith("license:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private fun firstNonBlank(vararg values: String?): String? {
    return values.firstOrNull { value -> !value.isNullOrBlank() }
}

private fun buildHuggingFaceUrl(
    repoId: String,
    revision: String,
    filePath: String,
    marker: String,
): String {
    val builder = HUGGING_FACE_BASE_URL.toHttpUrl().newBuilder()
    repoId.split('/').forEach(builder::addPathSegment)
    builder
        .addPathSegment(marker)
        .addPathSegment(revision)
    filePath.split('/').forEach(builder::addPathSegment)
    return builder.build().toString()
}

private fun buildHuggingFaceRepoUrl(repoId: String): String {
    val builder = HUGGING_FACE_BASE_URL.toHttpUrl().newBuilder()
    repoId.split('/').forEach(builder::addPathSegment)
    return builder.build().toString()
}

private fun safeVersionToken(raw: String): String {
    return raw.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .ifBlank { "model" }
        .take(48)
}

private const val DEFAULT_RUNTIME_COMPATIBILITY = "android-arm64-v8a"
const val DEFAULT_SEARCH_LIMIT = 20
private const val MAX_SEARCH_RESULTS = 50
private const val DEFAULT_SEARCH_REVISION = "main"
private const val HUGGING_FACE_BASE_URL = "https://huggingface.co"
private val SHA256_REGEX = Regex("^[a-f0-9]{64}$")
private val SHARDED_GGUF_REGEX = Regex("^.+-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE)
