package com.pocketagent.android.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val toolStringArgumentsParser = Json { ignoreUnknownKeys = false }

internal fun parseToolStringArguments(jsonArgs: String): Map<String, String>? {
    return runCatching {
        toolStringArgumentsParser.parseToJsonElement(jsonArgs).jsonObject.mapValues { (_, value) ->
            value.jsonPrimitive.content
        }
    }.getOrNull()
}
