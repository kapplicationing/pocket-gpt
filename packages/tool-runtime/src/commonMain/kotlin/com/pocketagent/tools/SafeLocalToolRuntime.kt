package com.pocketagent.tools

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonPrimitive

class SafeLocalToolRuntime(
    private val notesStore: NotesStore = InMemoryNotesStore(),
    private val searchStore: LocalSearchStore = InMemoryLocalSearchStore(),
    private val reminderStore: ReminderStore = InMemoryReminderStore(),
) : ToolModule {
    private val allowlistedTools = setOf(
        "calculator",
        "date_time",
        "notes_lookup",
        "local_search",
        "reminder_create",
    )
    private val toolSchemas: Map<String, ToolSchema> = mapOf(
        "calculator" to ToolSchema(
            fields = mapOf(
                "expression" to ToolFieldSchema(
                    required = true,
                    type = ToolFieldType.STRING,
                    allowBlank = false,
                    maxLength = 64,
                    pattern = Regex("^[0-9+\\-*/().\\s]+$"),
                    deniedFragments = COMMON_DENIED_FRAGMENTS,
                ),
            ),
        ),
        "date_time" to ToolSchema(fields = emptyMap()),
        "notes_lookup" to ToolSchema(
            fields = mapOf(
                "query" to ToolFieldSchema(
                    required = true,
                    type = ToolFieldType.STRING,
                    allowBlank = false,
                    maxLength = 256,
                    deniedFragments = COMMON_DENIED_FRAGMENTS,
                ),
            ),
        ),
        "local_search" to ToolSchema(
            fields = mapOf(
                "query" to ToolFieldSchema(
                    required = true,
                    type = ToolFieldType.STRING,
                    allowBlank = false,
                    maxLength = 256,
                    deniedFragments = COMMON_DENIED_FRAGMENTS,
                ),
            ),
        ),
        "reminder_create" to ToolSchema(
            fields = mapOf(
                "title" to ToolFieldSchema(
                    required = true,
                    type = ToolFieldType.STRING,
                    allowBlank = false,
                    maxLength = 128,
                    deniedFragments = COMMON_DENIED_FRAGMENTS,
                ),
            ),
        ),
    )

    override fun listEnabledTools(): List<String> = allowlistedTools.sorted()

    override fun validateToolCall(call: ToolCall): Boolean = validateToolCallDetailed(call).valid

    override fun validateToolRequest(request: ToolCallRequest): Boolean = validateToolRequestDetailed(request).valid

    override fun executeToolCall(call: ToolCall): ToolResult {
        val validated = validateToolCallDetailed(call)
        if (!validated.valid) {
            return validationFailure(validated)
        }
        return executeValidatedToolCall(requestName = call.name, args = validated.args)
    }

    override fun executeToolRequest(request: ToolCallRequest): ToolResult {
        val validated = validateToolRequestDetailed(request)
        if (!validated.valid) {
            return validationFailure(validated)
        }
        return executeValidatedToolCall(requestName = request.name, args = validated.args)
    }

    private fun executeValidatedToolCall(requestName: String, args: ToolArguments): ToolResult {
        return when (requestName) {
            "calculator" -> runCalculator(args.getStringValue("expression"))
            "date_time" -> currentDateTime()
            "notes_lookup" -> runNotesLookup(args.getStringValue("query"))
            "local_search" -> runLocalSearch(args.getStringValue("query"))
            "reminder_create" -> runReminderCreate(args.getStringValue("title"))
            else -> ToolResult(false, "Unknown tool.")
        }
    }

    private fun runCalculator(expression: String): ToolResult {
        val simple = expression.replace(" ", "")
        val operator = listOf("+", "-", "*", "/").firstOrNull { simple.contains(it) }
            ?: return ToolResult(false, "Unsupported expression.")
        val parts = simple.split(operator)
        if (parts.size != 2) return ToolResult(false, "Unsupported expression.")
        val left = parts[0].toDoubleOrNull() ?: return ToolResult(false, "Invalid left operand.")
        val right = parts[1].toDoubleOrNull() ?: return ToolResult(false, "Invalid right operand.")
        val value = when (operator) {
            "+" -> left + right
            "-" -> left - right
            "*" -> left * right
            "/" -> if (right == 0.0) return ToolResult(false, "Division by zero.") else left / right
            else -> return ToolResult(false, "Unsupported operator.")
        }
        val rounded = (value * 10000.0).roundToLong() / 10000.0
        return ToolResult(true, rounded.toString())
    }

    private fun currentDateTime(): ToolResult {
        val now = Instant.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        return ToolResult(true, formatter.format(now))
    }

    private fun runNotesLookup(query: String): ToolResult {
        val results = notesStore.lookup(query)
        if (results.isEmpty()) {
            return ToolResult(true, "notes_lookup:0 results")
        }
        val encoded = results.joinToString(separator = "|") { note ->
            "${note.id}:${note.title.take(40)}"
        }
        return ToolResult(true, "notes_lookup:${results.size} results:$encoded")
    }

    private fun runLocalSearch(query: String): ToolResult {
        val hits = searchStore.search(query)
        if (hits.isEmpty()) {
            return ToolResult(true, "local_search:0 hits")
        }
        val encoded = hits.joinToString(separator = "|") { hit ->
            "${hit.id}:${hit.title.take(40)}:${hit.score}"
        }
        return ToolResult(true, "local_search:${hits.size} hits:$encoded")
    }

    private fun runReminderCreate(title: String): ToolResult {
        val reminder = reminderStore.create(title)
        return ToolResult(
            true,
            "reminder_create:id=${reminder.id},title=${reminder.title},created_at=${reminder.createdAtIso}",
        )
    }

    private fun validateToolCallDetailed(call: ToolCall): ToolValidationResult {
        if (!allowlistedTools.contains(call.name)) {
            return ToolValidationResult.invalid(
                code = ToolValidationErrorCode.NOT_ALLOWLISTED,
                detail = "Tool '${call.name}' is not allowlisted.",
            )
        }
        return when (val request = ToolCallRequest.fromLegacy(call)) {
            is ToolCallRequestParseResult.Success -> validateToolRequestDetailed(request.request)
            is ToolCallRequestParseResult.InvalidJson -> ToolValidationResult.invalid(
                code = ToolValidationErrorCode.INVALID_JSON,
                detail = "Payload must be valid JSON object text.",
            )
        }
    }

    private fun validateToolRequestDetailed(request: ToolCallRequest): ToolValidationResult {
        if (!allowlistedTools.contains(request.name)) {
            return ToolValidationResult.invalid(
                code = ToolValidationErrorCode.NOT_ALLOWLISTED,
                detail = "Tool '${request.name}' is not allowlisted.",
            )
        }
        val schema = toolSchemas[request.name]
            ?: return ToolValidationResult.invalid(
                code = ToolValidationErrorCode.INVALID_SCHEMA,
                detail = "No validation schema is configured for tool '${request.name}'.",
            )

        val payload = request.arguments

        val unknownFields = payload.keys.filterNot { schema.fields.containsKey(it) }.sorted()
        if (unknownFields.isNotEmpty()) {
            return ToolValidationResult.invalid(
                code = ToolValidationErrorCode.UNKNOWN_FIELD,
                detail = "Unknown field '${unknownFields.first()}'.",
            )
        }

        val missingRequired = schema.fields.entries
            .filter { it.value.required && !payload.containsKey(it.key) }
            .map { it.key }
            .sorted()
        if (missingRequired.isNotEmpty()) {
            return ToolValidationResult.invalid(
                code = ToolValidationErrorCode.MISSING_REQUIRED_FIELD,
                detail = "Missing required field '${missingRequired.first()}'.",
            )
        }

        val normalizedArgs = mutableMapOf<String, JsonPrimitive>()
        payload.entries.sortedBy { it.key }.forEach { (fieldName, rawValue) ->
            val fieldSchema = schema.fields.getValue(fieldName)
            when (fieldSchema.type) {
                ToolFieldType.STRING -> {
                    val primitive = rawValue as? JsonPrimitive
                    if (primitive == null || !primitive.isString) {
                        return ToolValidationResult.invalid(
                            code = ToolValidationErrorCode.INVALID_FIELD_TYPE,
                            detail = "Field '$fieldName' must be a string.",
                        )
                    }
                    val value = primitive.content
                    if (!fieldSchema.allowBlank && value.isBlank()) {
                        return ToolValidationResult.invalid(
                            code = ToolValidationErrorCode.INVALID_FIELD_VALUE,
                            detail = "Field '$fieldName' must not be blank.",
                        )
                    }
                    if (value.length > fieldSchema.maxLength) {
                        return ToolValidationResult.invalid(
                            code = ToolValidationErrorCode.INVALID_FIELD_VALUE,
                            detail = "Field '$fieldName' exceeds max length ${fieldSchema.maxLength}.",
                        )
                    }
                    if (value.any { it.isISOControl() }) {
                        return ToolValidationResult.invalid(
                            code = ToolValidationErrorCode.INVALID_FIELD_VALUE,
                            detail = "Field '$fieldName' contains control characters.",
                        )
                    }
                    if (fieldSchema.pattern != null && !fieldSchema.pattern.matches(value)) {
                        return ToolValidationResult.invalid(
                            code = ToolValidationErrorCode.INVALID_FIELD_VALUE,
                            detail = "Field '$fieldName' has disallowed characters.",
                        )
                    }
                    val denied = fieldSchema.deniedFragments.firstOrNull {
                        value.contains(it, ignoreCase = true)
                    }
                    if (denied != null) {
                        return ToolValidationResult.invalid(
                            code = ToolValidationErrorCode.INVALID_FIELD_VALUE,
                            detail = "Field '$fieldName' contains denied fragment '$denied'.",
                        )
                    }
                    normalizedArgs[fieldName] = JsonPrimitive(value)
                }
            }
        }
        return ToolValidationResult.valid(args = ToolArguments(normalizedArgs))
    }

    private fun validationFailure(result: ToolValidationResult): ToolResult {
        val code = result.errorCode ?: ToolValidationErrorCode.INVALID_SCHEMA
        val detail = result.errorDetail ?: "Validation failed."
        return ToolResult(
            success = false,
            content = "TOOL_VALIDATION_ERROR:${code.name}:$detail",
            validationErrorCode = code.name,
            validationErrorDetail = detail,
        )
    }

    private data class ToolSchema(
        val fields: Map<String, ToolFieldSchema>,
    )

    private data class ToolFieldSchema(
        val required: Boolean,
        val type: ToolFieldType,
        val allowBlank: Boolean = true,
        val maxLength: Int = Int.MAX_VALUE,
        val pattern: Regex? = null,
        val deniedFragments: Set<String> = emptySet(),
    )

    private enum class ToolFieldType {
        STRING,
    }

    private enum class ToolValidationErrorCode {
        NOT_ALLOWLISTED,
        INVALID_JSON,
        MISSING_REQUIRED_FIELD,
        UNKNOWN_FIELD,
        INVALID_FIELD_TYPE,
        INVALID_FIELD_VALUE,
        INVALID_SCHEMA,
    }

    private data class ToolValidationResult(
        val valid: Boolean,
        val args: ToolArguments,
        val errorCode: ToolValidationErrorCode?,
        val errorDetail: String?,
    ) {
        companion object {
            fun valid(args: ToolArguments): ToolValidationResult {
                return ToolValidationResult(
                    valid = true,
                    args = args,
                    errorCode = null,
                    errorDetail = null,
                )
            }

            fun invalid(code: ToolValidationErrorCode, detail: String): ToolValidationResult {
                return ToolValidationResult(
                    valid = false,
                    args = ToolArguments.Empty,
                    errorCode = code,
                    errorDetail = detail,
                )
            }
        }
    }

    private fun ToolArguments.getStringValue(name: String): String {
        return requireNotNull(getString(name)) { "Validated tool arguments must contain string field '$name'." }
    }

    data class NoteRecord(
        val id: String,
        val title: String,
        val body: String,
    )

    data class SearchHit(
        val id: String,
        val title: String,
        val score: Int,
    )

    data class ReminderRecord(
        val id: String,
        val title: String,
        val createdAtIso: String,
    )

    interface NotesStore {
        fun lookup(query: String): List<NoteRecord>
    }

    interface LocalSearchStore {
        fun search(query: String): List<SearchHit>
    }

    interface ReminderStore {
        fun create(title: String): ReminderRecord
    }

    private class InMemoryNotesStore : NotesStore {
        private val notes: List<NoteRecord> = listOf(
            NoteRecord(id = "note-1", title = "Launch checklist", body = "Finalize QA matrix and release notes."),
            NoteRecord(id = "note-2", title = "Runtime gate", body = "Verify startup checks and backend identity."),
            NoteRecord(id = "note-3", title = "Ops sync", body = "Update execution board and evidence packet."),
        )

        override fun lookup(query: String): List<NoteRecord> {
            val tokens = tokenize(query)
            if (tokens.isEmpty()) {
                return emptyList()
            }
            return notes
                .map { note ->
                    val haystack = "${note.title} ${note.body}"
                    note to overlapScore(tokens, tokenize(haystack))
                }
                .filter { it.second > 0 }
                .sortedWith(
                    compareByDescending<Pair<NoteRecord, Int>> { it.second }
                        .thenBy { it.first.id },
                )
                .map { it.first }
        }
    }

    private class InMemoryLocalSearchStore : LocalSearchStore {
        private val docs: List<Pair<String, String>> = listOf(
            "doc-1" to "WP-12 handover packet and ENG ticket sequencing",
            "doc-2" to "Android runtime truth gate and startup checks",
            "doc-3" to "Tool runtime schema safety and deterministic contracts",
        )

        override fun search(query: String): List<SearchHit> {
            val tokens = tokenize(query)
            if (tokens.isEmpty()) {
                return emptyList()
            }
            return docs
                .map { (id, title) ->
                    SearchHit(id = id, title = title, score = overlapScore(tokens, tokenize(title)))
                }
                .filter { it.score > 0 }
                .sortedWith(compareByDescending<SearchHit> { it.score }.thenBy { it.id })
        }
    }

    private class InMemoryReminderStore : ReminderStore {
        private val reminders: MutableList<ReminderRecord> = mutableListOf()

        override fun create(title: String): ReminderRecord {
            val id = "rem-${reminders.size + 1}"
            val created = Instant.now().toString()
            val record = ReminderRecord(id = id, title = title, createdAtIso = created)
            reminders.add(record)
            return record
        }
    }

    private companion object {
        val COMMON_DENIED_FRAGMENTS = setOf(
            "exec",
            "bash",
            "sh -c",
            "http://",
            "https://",
            "<script",
            "../",
            "..\\",
        )

        fun tokenize(text: String): Set<String> {
            return text.lowercase()
                .split(Regex("[^a-z0-9]+"))
                .filter { it.isNotBlank() }
                .toSet()
        }

        fun overlapScore(a: Set<String>, b: Set<String>): Int {
            return a.intersect(b).size
        }
    }
}
