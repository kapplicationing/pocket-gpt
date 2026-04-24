package com.pocketagent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private val toolRequestJson = Json {
    isLenient = false
    ignoreUnknownKeys = false
    allowSpecialFloatingPointValues = false
}

data class ToolCallRequest(
    val name: String,
    val arguments: ToolArguments,
) {
    fun toLegacyCall(): ToolCall = ToolCall(name = name, jsonArgs = arguments.toJsonString())

    companion object {
        fun fromLegacy(call: ToolCall): ToolCallRequestParseResult = fromLegacy(
            name = call.name,
            jsonArgs = call.jsonArgs,
        )

        fun fromLegacy(name: String, jsonArgs: String): ToolCallRequestParseResult {
            return when (val arguments = ToolArguments.fromJson(jsonArgs)) {
                is ToolArgumentsParseResult.Success -> ToolCallRequestParseResult.Success(
                    ToolCallRequest(name = name, arguments = arguments.arguments),
                )
                is ToolArgumentsParseResult.InvalidJson -> ToolCallRequestParseResult.InvalidJson(
                    name = name,
                    rawJsonArgs = jsonArgs,
                )
            }
        }
    }
}

data class ToolArguments(
    private val fields: Map<String, JsonElement>,
) {
    val keys: Set<String> get() = fields.keys
    val entries: Set<Map.Entry<String, JsonElement>> get() = fields.entries

    operator fun get(name: String): JsonElement? = fields[name]

    fun containsKey(name: String): Boolean = fields.containsKey(name)

    fun getString(name: String): String? {
        val primitive = fields[name] as? JsonPrimitive ?: return null
        return primitive.content.takeIf { primitive.isString }
    }

    fun toJsonObject(): JsonObject = JsonObject(fields)

    fun toJsonString(): String = toJsonObject().toString()

    companion object {
        val Empty: ToolArguments = ToolArguments(emptyMap())

        fun fromJson(jsonArgs: String): ToolArgumentsParseResult {
            return runCatching {
                val parsed = toolRequestJson.parseToJsonElement(jsonArgs).jsonObject
                ToolArguments(parsed.toMap())
            }.fold(
                onSuccess = { ToolArgumentsParseResult.Success(it) },
                onFailure = { ToolArgumentsParseResult.InvalidJson(rawJsonArgs = jsonArgs) },
            )
        }
    }
}

sealed interface ToolCallRequestParseResult {
    data class Success(val request: ToolCallRequest) : ToolCallRequestParseResult

    data class InvalidJson(
        val name: String,
        val rawJsonArgs: String,
    ) : ToolCallRequestParseResult
}

sealed interface ToolArgumentsParseResult {
    data class Success(val arguments: ToolArguments) : ToolArgumentsParseResult

    data class InvalidJson(val rawJsonArgs: String) : ToolArgumentsParseResult
}
