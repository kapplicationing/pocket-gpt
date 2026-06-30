package com.pocketagent.android.runtime

import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.core.model.ModelSourceRef
import com.pocketagent.core.model.SourceTrustPolicy
import org.json.JSONObject

internal object ModelSourceRefJsonCodec {
    fun encode(sourceRef: ModelSourceRef): JSONObject {
        return JSONObject()
            .put("kind", sourceRef.kind.name)
            .put("originId", sourceRef.originId ?: JSONObject.NULL)
            .put("publisher", sourceRef.publisher ?: JSONObject.NULL)
            .put("repository", sourceRef.repository ?: JSONObject.NULL)
            .put("trustPolicy", sourceRef.trustPolicy.name)
            .put("revision", sourceRef.revision ?: JSONObject.NULL)
            .put("originUrl", sourceRef.originUrl ?: JSONObject.NULL)
    }

    fun decode(raw: String?): ModelSourceRef? {
        val payload = raw?.trim().orEmpty()
        if (payload.isEmpty()) {
            return null
        }
        return decode(runCatching { JSONObject(payload) }.getOrNull() ?: return null)
    }

    fun decode(json: JSONObject): ModelSourceRef? {
        val kind = runCatching {
            ModelSourceKind.valueOf(json.optString("kind", "").trim())
        }.getOrNull() ?: return null
        val trustPolicy = runCatching {
            SourceTrustPolicy.valueOf(json.optString("trustPolicy", SourceTrustPolicy.UNKNOWN.name).trim())
        }.getOrDefault(SourceTrustPolicy.UNKNOWN)
        return ModelSourceRef(
            kind = kind,
            originId = json.optionalString("originId"),
            publisher = json.optionalString("publisher"),
            repository = json.optionalString("repository"),
            trustPolicy = trustPolicy,
            revision = json.optionalString("revision"),
            originUrl = json.optionalString("originUrl"),
        )
    }
}

private fun JSONObject.optionalString(key: String): String? {
    if (isNull(key)) {
        return null
    }
    return optString(key, "").trim().ifEmpty { null }
}
