package com.pocketagent.android.data.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import com.pocketagent.android.ui.state.CompletionSettings
import com.pocketagent.android.ui.state.FirstSessionStage
import com.pocketagent.android.ui.state.FirstSessionTelemetryEvent
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.PersistedInteractionMessage
import com.pocketagent.android.ui.state.PersistedInteractionPart
import com.pocketagent.android.ui.state.PersistedToolCall
import com.pocketagent.android.ui.state.PersistedToolCallStatus
import com.pocketagent.android.ui.state.RuntimeKeepAlivePreference
import com.pocketagent.core.RoutingMode
import com.pocketagent.runtime.RuntimePerformanceProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AndroidSessionPersistence(
    context: Context,
) : SessionPersistence {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val repository: ChatSessionRepository = SQLiteChatSessionRepository(appContext)

    override fun loadState(): StoredChatState {
        return when (val result = loadStateResult()) {
            is SessionStateLoadResult.Success -> result.state
            is SessionStateLoadResult.RecoverableCorruption -> result.resetState
            is SessionStateLoadResult.FatalCorruption -> StoredChatState()
        }
    }

    override fun loadBootstrapState(): StoredChatState {
        return when (val result = loadBootstrapStateResult()) {
            is SessionStateLoadResult.Success -> result.state
            is SessionStateLoadResult.RecoverableCorruption -> result.resetState
            is SessionStateLoadResult.FatalCorruption -> StoredChatState()
        }
    }

    override fun loadStateResult(): SessionStateLoadResult {
        return loadStateResult(bootstrapOnly = false)
    }

    override fun loadBootstrapStateResult(): SessionStateLoadResult {
        return loadStateResult(bootstrapOnly = true)
    }

    override fun loadSessionMessages(sessionId: String): List<StoredChatMessage>? {
        return runCatching {
            migrateLegacyPrefsIfNeeded()
            repository.loadSessionMessages(sessionId)
        }.getOrNull()
    }

    override fun saveState(state: StoredChatState) {
        runCatching {
            migrateLegacyPrefsIfNeeded()
            repository.saveState(state)
        }.getOrElse { error ->
            throw IllegalStateException("Failed to persist chat state", error)
        }
    }

    override fun clearState() {
        repository.clearAll()
        prefs.edit().remove(KEY_STATE).apply()
    }

    private fun loadStateResult(bootstrapOnly: Boolean): SessionStateLoadResult {
        return runCatching {
            migrateLegacyPrefsIfNeeded()
            val state = if (bootstrapOnly) {
                repository.loadBootstrapState()
            } else {
                repository.loadFullState()
            }
            SessionStateLoadResult.Success(state)
        }.getOrElse { error ->
            val backupToken = backupCorruptState(raw = prefs.getString(KEY_STATE, null), error = error)
            clearState()
            SessionStateLoadResult.RecoverableCorruption(
                resetState = StoredChatState(),
                code = CHAT_STATE_CORRUPTION_CODE,
                userMessage = "Saved chat state was corrupted and reset.",
                technicalDetail = "code=$CHAT_STATE_CORRUPTION_CODE;backup=$backupToken;error=${error.message ?: "decode_failed"}",
            )
        }
    }

    private fun migrateLegacyPrefsIfNeeded() {
        if (repository.hasAnyPersistedState()) {
            return
        }
        val raw = prefs.getString(KEY_STATE, null) ?: return
        val migrated = runCatching { PersistedChatStateCodec.decode(raw) }
            .getOrElse { error ->
                backupCorruptState(raw = raw, error = error)
                prefs.edit().remove(KEY_STATE).apply()
                throw error
            }
        repository.saveState(migrated)
        prefs.edit().remove(KEY_STATE).apply()
    }

    private fun backupCorruptState(raw: String?, error: Throwable): String {
        val token = "chat-state-${System.currentTimeMillis()}"
        prefs.edit()
            .putString(KEY_CORRUPT_BACKUP_PAYLOAD, raw)
            .putString(KEY_CORRUPT_BACKUP_TOKEN, token)
            .putLong(KEY_CORRUPT_BACKUP_SAVED_AT_EPOCH_MS, System.currentTimeMillis())
            .putString(KEY_CORRUPT_BACKUP_ERROR, error.message ?: error::class.java.simpleName)
            .apply()
        return token
    }

    private companion object {
        const val PREFS_NAME = "pocketagent_chat_state"
        const val KEY_STATE = "chat_state_v2"
        const val KEY_CORRUPT_BACKUP_PAYLOAD = "chat_state_corrupt_backup_payload"
        const val KEY_CORRUPT_BACKUP_TOKEN = "chat_state_corrupt_backup_token"
        const val KEY_CORRUPT_BACKUP_SAVED_AT_EPOCH_MS = "chat_state_corrupt_backup_saved_at"
        const val KEY_CORRUPT_BACKUP_ERROR = "chat_state_corrupt_backup_error"
        const val CHAT_STATE_CORRUPTION_CODE = "CHAT_STATE_CORRUPT_JSON"
    }
}

private class SQLiteChatSessionRepository(
    context: Context,
) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION), ChatSessionRepository {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_APP_STATE (
                state_id INTEGER PRIMARY KEY CHECK (state_id = 1),
                active_session_id TEXT,
                routing_mode TEXT NOT NULL,
                performance_profile TEXT NOT NULL,
                keep_alive_preference TEXT NOT NULL,
                gpu_acceleration_enabled INTEGER NOT NULL,
                default_thinking_enabled INTEGER NOT NULL DEFAULT 0,
                onboarding_completed INTEGER NOT NULL,
                first_session_stage TEXT NOT NULL,
                advanced_unlocked INTEGER NOT NULL,
                first_answer_completed INTEGER NOT NULL,
                follow_up_completed INTEGER NOT NULL,
                first_session_telemetry_json TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_SESSIONS (
                session_id TEXT PRIMARY KEY,
                sort_order INTEGER NOT NULL,
                title TEXT NOT NULL,
                created_at_epoch_ms INTEGER NOT NULL,
                updated_at_epoch_ms INTEGER NOT NULL,
                completion_settings_json TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                message_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                sort_order INTEGER NOT NULL,
                timestamp_epoch_ms INTEGER NOT NULL,
                payload_json TEXT NOT NULL,
                FOREIGN KEY(session_id) REFERENCES $TABLE_SESSIONS(session_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX ${TABLE_MESSAGES}_session_sort_idx ON $TABLE_MESSAGES(session_id, sort_order)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE $TABLE_APP_STATE ADD COLUMN default_thinking_enabled INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    override fun hasAnyPersistedState(): Boolean {
        val db = readableDatabase
        return try {
            db.rawQuery(
                """
                SELECT CASE
                    WHEN EXISTS(SELECT 1 FROM $TABLE_SESSIONS LIMIT 1)
                        OR EXISTS(SELECT 1 FROM $TABLE_APP_STATE WHERE state_id = 1)
                    THEN 1
                    ELSE 0
                END
                """.trimIndent(),
                null,
            ).use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) != 0
            }
        } catch (_: SQLiteException) {
            false
        }
    }

    override fun loadBootstrapState(): StoredChatState {
        val stateRow = loadAppState()
        val persistedActiveSessionId = stateRow.activeSessionId
        val fallbackActiveSessionId = persistedActiveSessionId ?: loadLastSessionId()
        val sessions = loadSessionMetadata(activeSessionId = fallbackActiveSessionId)
        return StoredChatState(
            sessions = sessions,
            activeSessionId = fallbackActiveSessionId,
            routingMode = stateRow.routingMode,
            performanceProfile = stateRow.performanceProfile,
            keepAlivePreference = stateRow.keepAlivePreference,
            gpuAccelerationEnabled = stateRow.gpuAccelerationEnabled,
            defaultThinkingEnabled = stateRow.defaultThinkingEnabled,
            onboardingCompleted = stateRow.onboardingCompleted,
            firstSessionStage = stateRow.firstSessionStage,
            advancedUnlocked = stateRow.advancedUnlocked,
            firstAnswerCompleted = stateRow.firstAnswerCompleted,
            followUpCompleted = stateRow.followUpCompleted,
            firstSessionTelemetryEvents = stateRow.firstSessionTelemetryEvents,
        )
    }

    override fun loadFullState(): StoredChatState {
        val bootstrap = loadBootstrapState()
        val sessions = bootstrap.sessions.map { session ->
            val messages = loadSessionMessages(session.id).orEmpty()
            session.copy(
                messages = messages,
                messagesLoaded = true,
                messageCount = messages.size,
            )
        }
        return bootstrap.copy(sessions = sessions)
    }

    override fun loadSessionMessages(sessionId: String): List<StoredChatMessage>? {
        val db = readableDatabase
        return db.rawQuery(
            """
            SELECT payload_json
            FROM $TABLE_MESSAGES
            WHERE session_id = ?
            ORDER BY sort_order ASC
            """.trimIndent(),
            arrayOf(sessionId),
        ).use { cursor ->
            if (cursor.count == 0) {
                return@use emptyList()
            }
            buildList(cursor.count) {
                while (cursor.moveToNext()) {
                    add(SessionEntityCodec.decodeMessage(cursor.getString(0)))
                }
            }
        }
    }

    override fun saveState(state: StoredChatState) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            upsertAppState(db, state)
            val existingSessionIds = querySessionIds(db)
            val nextSessionIds = state.sessions.map { it.id }.toSet()
            val removedSessionIds = existingSessionIds - nextSessionIds
            removedSessionIds.forEach { sessionId ->
                db.delete(TABLE_SESSIONS, "session_id = ?", arrayOf(sessionId))
            }
            state.sessions.forEachIndexed { index, session ->
                upsertSession(db = db, session = session, sortOrder = index)
                if (session.messagesLoaded || session.messageCount == 0) {
                    replaceSessionMessages(db = db, session = session)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_MESSAGES, null, null)
            db.delete(TABLE_SESSIONS, null, null)
            db.delete(TABLE_APP_STATE, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun loadAppState(): StoredAppState {
        val db = readableDatabase
        return db.query(
            TABLE_APP_STATE,
            arrayOf(
                "active_session_id",
                "routing_mode",
                "performance_profile",
                "keep_alive_preference",
                "gpu_acceleration_enabled",
                "default_thinking_enabled",
                "onboarding_completed",
                "first_session_stage",
                "advanced_unlocked",
                "first_answer_completed",
                "follow_up_completed",
                "first_session_telemetry_json",
            ),
            "state_id = 1",
            null,
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use StoredAppState()
            }
            StoredAppState(
                activeSessionId = cursor.getStringOrNull(0),
                routingMode = cursor.getStringOrNull(1) ?: RoutingMode.AUTO.name,
                performanceProfile = cursor.getStringOrNull(2) ?: RuntimePerformanceProfile.BALANCED.name,
                keepAlivePreference = cursor.getStringOrNull(3) ?: RuntimeKeepAlivePreference.AUTO.name,
                gpuAccelerationEnabled = cursor.getInt(4) != 0,
                defaultThinkingEnabled = cursor.getInt(5) != 0,
                onboardingCompleted = cursor.getInt(6) != 0,
                firstSessionStage = cursor.getStringOrNull(7) ?: FirstSessionStage.ONBOARDING.name,
                advancedUnlocked = cursor.getInt(8) != 0,
                firstAnswerCompleted = cursor.getInt(9) != 0,
                followUpCompleted = cursor.getInt(10) != 0,
                firstSessionTelemetryEvents = SessionEntityCodec.decodeTelemetryEvents(cursor.getStringOrNull(11)),
            )
        }
    }

    private fun loadSessionMetadata(activeSessionId: String?): List<StoredChatSession> {
        val db = readableDatabase
        return db.rawQuery(
            """
            SELECT
                s.session_id,
                s.title,
                s.created_at_epoch_ms,
                s.updated_at_epoch_ms,
                s.completion_settings_json,
                COUNT(m.message_id) AS message_count
            FROM $TABLE_SESSIONS s
            LEFT JOIN $TABLE_MESSAGES m ON m.session_id = s.session_id
            GROUP BY
                s.session_id,
                s.title,
                s.created_at_epoch_ms,
                s.updated_at_epoch_ms,
                s.completion_settings_json,
                s.sort_order
            ORDER BY s.sort_order ASC
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList(cursor.count) {
                while (cursor.moveToNext()) {
                    val sessionId = cursor.getString(0)
                    val title = cursor.getString(1)
                    val createdAtEpochMs = cursor.getLong(2)
                    val updatedAtEpochMs = cursor.getLong(3)
                    val completionSettings = SessionEntityCodec.decodeCompletionSettings(cursor.getString(4))
                    val messageCount = cursor.getInt(5)
                    val shouldHydrateMessages = sessionId == activeSessionId || messageCount == 0
                    val messages = if (shouldHydrateMessages) {
                        loadSessionMessages(sessionId).orEmpty()
                    } else {
                        emptyList()
                    }
                    add(
                        StoredChatSession(
                            id = sessionId,
                            title = title,
                            createdAtEpochMs = createdAtEpochMs,
                            updatedAtEpochMs = updatedAtEpochMs,
                            messages = messages,
                            completionSettings = completionSettings,
                            messagesLoaded = shouldHydrateMessages,
                            messageCount = messageCount,
                        ),
                    )
                }
            }
        }
    }

    private fun loadLastSessionId(): String? {
        val db = readableDatabase
        return db.query(
            TABLE_SESSIONS,
            arrayOf("session_id"),
            null,
            null,
            null,
            null,
            "sort_order DESC",
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getStringOrNull(0) else null
        }
    }

    private fun upsertAppState(db: SQLiteDatabase, state: StoredChatState) {
        db.insertWithOnConflict(
            TABLE_APP_STATE,
            null,
            ContentValues().apply {
                put("state_id", 1)
                put("active_session_id", state.activeSessionId)
                put("routing_mode", state.routingMode)
                put("performance_profile", state.performanceProfile)
                put("keep_alive_preference", state.keepAlivePreference)
                put("gpu_acceleration_enabled", if (state.gpuAccelerationEnabled) 1 else 0)
                put("default_thinking_enabled", if (state.defaultThinkingEnabled) 1 else 0)
                put("onboarding_completed", if (state.onboardingCompleted) 1 else 0)
                put("first_session_stage", state.firstSessionStage)
                put("advanced_unlocked", if (state.advancedUnlocked) 1 else 0)
                put("first_answer_completed", if (state.firstAnswerCompleted) 1 else 0)
                put("follow_up_completed", if (state.followUpCompleted) 1 else 0)
                put(
                    "first_session_telemetry_json",
                    SessionEntityCodec.encodeTelemetryEvents(state.firstSessionTelemetryEvents),
                )
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun querySessionIds(db: SQLiteDatabase): Set<String> {
        return db.query(
            TABLE_SESSIONS,
            arrayOf("session_id"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            buildSet(cursor.count) {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }
    }

    private fun upsertSession(
        db: SQLiteDatabase,
        session: StoredChatSession,
        sortOrder: Int,
    ) {
        db.insertWithOnConflict(
            TABLE_SESSIONS,
            null,
            ContentValues().apply {
                put("session_id", session.id)
                put("sort_order", sortOrder)
                put("title", session.title)
                put("created_at_epoch_ms", session.createdAtEpochMs)
                put("updated_at_epoch_ms", session.updatedAtEpochMs)
                put(
                    "completion_settings_json",
                    SessionEntityCodec.encodeCompletionSettings(session.completionSettings),
                )
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun replaceSessionMessages(
        db: SQLiteDatabase,
        session: StoredChatSession,
    ) {
        db.delete(TABLE_MESSAGES, "session_id = ?", arrayOf(session.id))
        session.messages.forEachIndexed { index, message ->
            db.insertOrThrow(
                TABLE_MESSAGES,
                null,
                ContentValues().apply {
                    put("message_id", message.id)
                    put("session_id", session.id)
                    put("sort_order", index)
                    put("timestamp_epoch_ms", message.timestampEpochMs)
                    put("payload_json", SessionEntityCodec.encodeMessage(message))
                },
            )
        }
    }

    private companion object {
        const val DATABASE_NAME = "pocketagent_chat_state.db"
        const val DATABASE_VERSION = 2
        const val TABLE_APP_STATE = "app_state"
        const val TABLE_SESSIONS = "chat_sessions"
        const val TABLE_MESSAGES = "chat_messages"
    }
}

private data class StoredAppState(
    val activeSessionId: String? = null,
    val routingMode: String = RoutingMode.AUTO.name,
    val performanceProfile: String = RuntimePerformanceProfile.BALANCED.name,
    val keepAlivePreference: String = RuntimeKeepAlivePreference.AUTO.name,
    val gpuAccelerationEnabled: Boolean = false,
    val defaultThinkingEnabled: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val firstSessionStage: String = FirstSessionStage.ONBOARDING.name,
    val advancedUnlocked: Boolean = false,
    val firstAnswerCompleted: Boolean = false,
    val followUpCompleted: Boolean = false,
    val firstSessionTelemetryEvents: List<FirstSessionTelemetryEvent> = emptyList(),
)

internal object PersistedChatStateCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun decode(raw: String): StoredChatState {
        val root = json.parseToJsonElement(raw).jsonObject
        val sessions = root["sessions"]
            ?.let { element -> parseSessions(element) }
            ?: emptyList()
        return StoredChatState(
            sessions = sessions,
            activeSessionId = root.stringOrNull("activeSessionId"),
            routingMode = parseRoutingModeName(root.stringOrDefault("routingMode", RoutingMode.AUTO.name)),
            performanceProfile = parsePerformanceProfileName(
                root.stringOrDefault("performanceProfile", RuntimePerformanceProfile.BALANCED.name),
            ),
            keepAlivePreference = parseKeepAlivePreferenceName(
                root.stringOrDefault("keepAlivePreference", RuntimeKeepAlivePreference.AUTO.name),
            ),
            gpuAccelerationEnabled = root.booleanOrDefault("gpuAccelerationEnabled", false),
            defaultThinkingEnabled = root.booleanOrDefault("defaultThinkingEnabled", false),
            onboardingCompleted = root.booleanOrDefault("onboardingCompleted", false),
            firstSessionStage = parseFirstSessionStageName(
                root.stringOrDefault("firstSessionStage", FirstSessionStage.ONBOARDING.name),
            ),
            advancedUnlocked = root.booleanOrDefault("advancedUnlocked", true),
            firstAnswerCompleted = root.booleanOrDefault("firstAnswerCompleted", false),
            followUpCompleted = root.booleanOrDefault("followUpCompleted", false),
            firstSessionTelemetryEvents = parseFirstSessionTelemetryEvents(root["firstSessionTelemetryEvents"]),
        )
    }

    fun encode(state: StoredChatState): String {
        val root = buildJsonObject {
            state.activeSessionId?.let { put("activeSessionId", JsonPrimitive(it)) }
            put("routingMode", JsonPrimitive(state.routingMode))
            put("performanceProfile", JsonPrimitive(state.performanceProfile))
            put("keepAlivePreference", JsonPrimitive(state.keepAlivePreference))
            put("gpuAccelerationEnabled", JsonPrimitive(state.gpuAccelerationEnabled))
            put("defaultThinkingEnabled", JsonPrimitive(state.defaultThinkingEnabled))
            put("onboardingCompleted", JsonPrimitive(state.onboardingCompleted))
            put("firstSessionStage", JsonPrimitive(state.firstSessionStage))
            put("advancedUnlocked", JsonPrimitive(state.advancedUnlocked))
            put("firstAnswerCompleted", JsonPrimitive(state.firstAnswerCompleted))
            put("followUpCompleted", JsonPrimitive(state.followUpCompleted))
            put("firstSessionTelemetryEvents", SessionEntityCodec.telemetryEventsJson(state.firstSessionTelemetryEvents))
            put(
                "sessions",
                buildJsonArray {
                    state.sessions.forEach { session ->
                        add(SessionEntityCodec.sessionJson(session))
                    }
                },
            )
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun parseFirstSessionTelemetryEvents(element: JsonElement?): List<FirstSessionTelemetryEvent> {
        return SessionEntityCodec.decodeTelemetryEventsJson(element)
    }

    private fun parseSessions(element: JsonElement): List<StoredChatSession> {
        return element.jsonArray.mapNotNull { sessionElement ->
            val obj = sessionElement.asObjectOrNull() ?: return@mapNotNull null
            val messages = parseMessages(obj["messages"])
            StoredChatSession(
                id = obj.stringOrDefault("id", ""),
                title = obj.stringOrDefault("title", "New chat"),
                createdAtEpochMs = obj.longOrDefault("createdAtEpochMs", 0L),
                updatedAtEpochMs = obj.longOrDefault("updatedAtEpochMs", 0L),
                messages = messages,
                completionSettings = parseCompletionSettings(obj["completionSettings"]),
                messagesLoaded = obj.booleanOrDefault("messagesLoaded", true),
                messageCount = obj.intOrDefault("messageCount", messages.size),
            )
        }
    }
}

private object SessionEntityCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encodeTelemetryEvents(events: List<FirstSessionTelemetryEvent>): String {
        return json.encodeToString(JsonArray.serializer(), telemetryEventsJson(events))
    }

    fun decodeTelemetryEvents(raw: String?): List<FirstSessionTelemetryEvent> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        return decodeTelemetryEventsJson(element)
    }

    fun telemetryEventsJson(events: List<FirstSessionTelemetryEvent>): JsonArray {
        return buildJsonArray {
            events.forEach { event ->
                add(
                    buildJsonObject {
                        put("eventName", JsonPrimitive(event.eventName))
                        put("eventTimeUtc", JsonPrimitive(event.eventTimeUtc))
                    },
                )
            }
        }
    }

    fun decodeTelemetryEventsJson(element: JsonElement?): List<FirstSessionTelemetryEvent> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item.asObjectOrNull() ?: return@mapNotNull null
            val eventName = obj.stringOrNull("eventName") ?: return@mapNotNull null
            val eventTimeUtc = obj.stringOrNull("eventTimeUtc") ?: return@mapNotNull null
            FirstSessionTelemetryEvent(
                eventName = eventName,
                eventTimeUtc = eventTimeUtc,
            )
        }
    }

    fun encodeCompletionSettings(settings: CompletionSettings): String {
        return json.encodeToString(JsonObject.serializer(), completionSettingsJson(settings))
    }

    fun decodeCompletionSettings(raw: String?): CompletionSettings {
        if (raw.isNullOrBlank()) {
            return CompletionSettings()
        }
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        return parseCompletionSettings(element)
    }

    fun encodeMessage(message: StoredChatMessage): String {
        return json.encodeToString(JsonObject.serializer(), messageJson(message))
    }

    fun decodeMessage(raw: String): StoredChatMessage {
        val obj = json.parseToJsonElement(raw).jsonObject
        return parseMessage(obj) ?: throw IllegalArgumentException("CHAT_STATE_INVALID_MESSAGE")
    }

    fun sessionJson(session: StoredChatSession): JsonObject {
        return buildJsonObject {
            put("id", JsonPrimitive(session.id))
            put("title", JsonPrimitive(session.title))
            put("createdAtEpochMs", JsonPrimitive(session.createdAtEpochMs))
            put("updatedAtEpochMs", JsonPrimitive(session.updatedAtEpochMs))
            put("completionSettings", completionSettingsJson(session.completionSettings))
            put("messagesLoaded", JsonPrimitive(session.messagesLoaded))
            put("messageCount", JsonPrimitive(session.messageCount))
            put(
                "messages",
                buildJsonArray {
                    session.messages.forEach { message ->
                        add(messageJson(message))
                    }
                },
            )
        }
    }

    private fun completionSettingsJson(settings: CompletionSettings): JsonObject {
        return buildJsonObject {
            put("temperature", JsonPrimitive(settings.temperature))
            put("topP", JsonPrimitive(settings.topP))
            put("topK", JsonPrimitive(settings.topK))
            put("maxTokens", JsonPrimitive(settings.maxTokens))
            put("repeatPenalty", JsonPrimitive(settings.repeatPenalty))
            put("frequencyPenalty", JsonPrimitive(settings.frequencyPenalty))
            put("presencePenalty", JsonPrimitive(settings.presencePenalty))
            put("systemPrompt", JsonPrimitive(settings.systemPrompt))
            put("showThinking", JsonPrimitive(settings.showThinking))
        }
    }

    private fun messageJson(message: StoredChatMessage): JsonObject {
        return buildJsonObject {
            put("id", JsonPrimitive(message.id))
            put("role", JsonPrimitive(message.role.name))
            put("content", JsonPrimitive(message.content))
            put("timestampEpochMs", JsonPrimitive(message.timestampEpochMs))
            put("kind", JsonPrimitive(message.kind.name))
            message.imagePath?.let { put("imagePath", JsonPrimitive(it)) }
            if (message.imagePaths.isNotEmpty()) {
                put(
                    "imagePaths",
                    buildJsonArray {
                        message.imagePaths.forEach { add(JsonPrimitive(it)) }
                    },
                )
            }
            message.toolName?.let { put("toolName", JsonPrimitive(it)) }
            put("isStreaming", JsonPrimitive(message.isStreaming))
            put("isThinking", JsonPrimitive(message.isThinking))
            message.requestId?.let { put("requestId", JsonPrimitive(it)) }
            message.finishReason?.let { put("finishReason", JsonPrimitive(it)) }
            put("terminalEventSeen", JsonPrimitive(message.terminalEventSeen))
            put("interaction", encodeInteraction(message))
            message.reasoningContent?.let { put("reasoningContent", JsonPrimitive(it)) }
            message.firstTokenMs?.let { put("firstTokenMs", JsonPrimitive(it)) }
            message.tokensPerSec?.let { put("tokensPerSec", JsonPrimitive(it)) }
            message.totalLatencyMs?.let { put("totalLatencyMs", JsonPrimitive(it)) }
        }
    }
}

private fun parseCompletionSettings(element: JsonElement?): CompletionSettings {
    val obj = element?.asObjectOrNull() ?: return CompletionSettings()
    val defaults = CompletionSettings()
    return CompletionSettings(
        temperature = obj.floatOrDefault("temperature", defaults.temperature),
        topP = obj.floatOrDefault("topP", defaults.topP),
        topK = obj.intOrDefault("topK", defaults.topK),
        maxTokens = obj.intOrDefault("maxTokens", defaults.maxTokens),
        repeatPenalty = obj.floatOrDefault("repeatPenalty", defaults.repeatPenalty),
        frequencyPenalty = obj.floatOrDefault("frequencyPenalty", defaults.frequencyPenalty),
        presencePenalty = obj.floatOrDefault("presencePenalty", defaults.presencePenalty),
        systemPrompt = obj.stringOrDefault("systemPrompt", defaults.systemPrompt),
        showThinking = obj.booleanOrDefault("showThinking", defaults.showThinking),
    )
}

private fun parseMessages(element: JsonElement?): List<StoredChatMessage> {
    val array = element as? JsonArray ?: return emptyList()
    return array.mapNotNull { messageElement ->
        parseMessage(messageElement.asObjectOrNull())
    }
}

private fun parseMessage(obj: JsonObject?): StoredChatMessage? {
    obj ?: return null
    val imagePath = obj.stringOrNull("imagePath")
    val normalizedImagePaths = normalizeImagePaths(
        imagePath = imagePath,
        imagePaths = parseStringList(obj["imagePaths"]),
    )
    return StoredChatMessage(
        id = obj.stringOrDefault("id", ""),
        role = parseRole(obj.stringOrDefault("role", MessageRole.SYSTEM.name)),
        content = obj.stringOrDefault("content", ""),
        timestampEpochMs = obj.longOrDefault("timestampEpochMs", 0L),
        kind = parseKind(obj.stringOrDefault("kind", MessageKind.TEXT.name)),
        imagePath = imagePath ?: normalizedImagePaths.firstOrNull(),
        imagePaths = normalizedImagePaths,
        toolName = obj.stringOrNull("toolName"),
        isStreaming = obj.booleanOrDefault("isStreaming", false),
        isThinking = obj.booleanOrDefault("isThinking", false),
        requestId = obj.stringOrNull("requestId"),
        finishReason = obj.stringOrNull("finishReason"),
        terminalEventSeen = obj.booleanOrDefault("terminalEventSeen", false),
        interaction = decodeInteraction(obj),
        reasoningContent = obj.stringOrNull("reasoningContent"),
        firstTokenMs = obj.longOrNull("firstTokenMs"),
        tokensPerSec = obj.doubleOrNull("tokensPerSec"),
        totalLatencyMs = obj.longOrNull("totalLatencyMs"),
    )
}

private fun normalizeImagePaths(
    imagePath: String?,
    imagePaths: List<String>,
): List<String> {
    return buildList {
        addAll(imagePaths)
        imagePath?.takeIf { it.isNotBlank() && it !in this }?.let(::add)
    }
}

private fun encodeInteraction(message: StoredChatMessage): JsonObject {
    val interaction = message.interaction ?: legacyInteractionFor(message)
    return buildJsonObject {
        put("role", JsonPrimitive(interaction.role))
        interaction.toolCallId?.let { put("toolCallId", JsonPrimitive(it)) }
        put(
            "parts",
            buildJsonArray {
                interaction.parts.forEach { part ->
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive(part.type))
                            part.text?.let { put("text", JsonPrimitive(it)) }
                        },
                    )
                }
            },
        )
        put(
            "toolCalls",
            buildJsonArray {
                interaction.toolCalls.forEach { toolCall ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(toolCall.id))
                            put("name", JsonPrimitive(toolCall.name))
                            put("argumentsJson", JsonPrimitive(toolCall.argumentsJson))
                            put("status", JsonPrimitive(toolCall.status.name))
                        },
                    )
                }
            },
        )
        put(
            "metadata",
            buildJsonObject {
                interaction.metadata.forEach { (key, value) ->
                    put(key, JsonPrimitive(value))
                }
            },
        )
    }
}

private fun decodeInteraction(obj: JsonObject): PersistedInteractionMessage {
    val encoded = obj["interaction"]?.asObjectOrNull()
    if (encoded == null) {
        return legacyInteractionFor(obj)
    }
    val role = encoded.stringOrDefault("role", obj.stringOrDefault("role", MessageRole.SYSTEM.name))
    val parts = (encoded["parts"] as? JsonArray)?.mapNotNull { partElement ->
        val part = partElement.asObjectOrNull() ?: return@mapNotNull null
        PersistedInteractionPart(
            type = part.stringOrDefault("type", "text"),
            text = part.stringOrNull("text"),
        )
    } ?: emptyList()
    val toolCalls = (encoded["toolCalls"] as? JsonArray)?.mapNotNull { callElement ->
        val call = callElement.asObjectOrNull() ?: return@mapNotNull null
        val id = call.stringOrNull("id") ?: return@mapNotNull null
        val name = call.stringOrNull("name") ?: return@mapNotNull null
        val args = call.stringOrDefault("argumentsJson", "{}")
        val status = call.stringOrDefault("status", PersistedToolCallStatus.PENDING.name)
        PersistedToolCall(
            id = id,
            name = name,
            argumentsJson = args,
            status = parseToolCallStatus(status),
        )
    } ?: emptyList()
    val metadata = encoded["metadata"]?.asObjectOrNull()?.entries?.associate { entry ->
        val value = runCatching { entry.value.jsonPrimitive.content }.getOrDefault("")
        entry.key to value
    } ?: emptyMap()
    return PersistedInteractionMessage(
        role = role,
        parts = if (parts.isEmpty()) {
            listOf(PersistedInteractionPart(type = "text", text = obj.stringOrDefault("content", "")))
        } else {
            parts
        },
        toolCalls = toolCalls,
        toolCallId = encoded.stringOrNull("toolCallId"),
        metadata = metadata,
    )
}

private fun legacyInteractionFor(obj: JsonObject): PersistedInteractionMessage {
    return PersistedInteractionMessage(
        role = obj.stringOrDefault("role", MessageRole.SYSTEM.name),
        parts = listOf(PersistedInteractionPart(type = "text", text = obj.stringOrDefault("content", ""))),
        metadata = mapOf("source" to "legacy_v2"),
    )
}

private fun legacyInteractionFor(message: StoredChatMessage): PersistedInteractionMessage {
    return PersistedInteractionMessage(
        role = message.role.name,
        parts = listOf(PersistedInteractionPart(type = "text", text = message.content)),
        metadata = buildMap {
            put("kind", message.kind.name)
            message.toolName?.let { put("toolName", it) }
        },
    )
}

private fun parseRole(raw: String): MessageRole {
    return runCatching { MessageRole.valueOf(raw) }
        .getOrElse { throw IllegalArgumentException("CHAT_STATE_INVALID_ROLE:$raw") }
}

private fun parseKind(raw: String): MessageKind {
    return runCatching { MessageKind.valueOf(raw) }
        .getOrElse { throw IllegalArgumentException("CHAT_STATE_INVALID_KIND:$raw") }
}

private fun parseRoutingModeName(raw: String): String {
    return runCatching { RoutingMode.valueOf(raw) }
        .getOrElse { throw IllegalArgumentException("CHAT_STATE_INVALID_ROUTING_MODE:$raw") }
        .name
}

private fun parsePerformanceProfileName(raw: String): String {
    return runCatching { RuntimePerformanceProfile.valueOf(raw) }
        .getOrElse { throw IllegalArgumentException("CHAT_STATE_INVALID_PERFORMANCE_PROFILE:$raw") }
        .name
}

private fun parseFirstSessionStageName(raw: String): String {
    return runCatching { FirstSessionStage.valueOf(raw) }
        .getOrElse { throw IllegalArgumentException("CHAT_STATE_INVALID_FIRST_SESSION_STAGE:$raw") }
        .name
}

private fun parseKeepAlivePreferenceName(raw: String): String {
    return runCatching { RuntimeKeepAlivePreference.valueOf(raw) }
        .getOrElse { throw IllegalArgumentException("CHAT_STATE_INVALID_KEEP_ALIVE_PREFERENCE:$raw") }
        .name
}

private fun parseToolCallStatus(raw: String): PersistedToolCallStatus {
    return runCatching { PersistedToolCallStatus.valueOf(raw.trim().uppercase()) }
        .getOrElse { PersistedToolCallStatus.PENDING }
}

private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonObject.stringOrNull(key: String): String? {
    val value = this[key] ?: return null
    val content = runCatching { value.jsonPrimitive.content }.getOrNull()?.trim().orEmpty()
    return content.ifBlank { null }
}

private fun JsonObject.stringOrDefault(key: String, default: String): String {
    return stringOrNull(key) ?: default
}

private fun JsonObject.longOrDefault(key: String, default: Long): Long {
    val value = this[key] ?: return default
    val raw = runCatching { value.jsonPrimitive.content }.getOrNull()
        ?: throw IllegalArgumentException("CHAT_STATE_INVALID_LONG:$key")
    return raw.toLongOrNull() ?: throw IllegalArgumentException("CHAT_STATE_INVALID_LONG:$key")
}

private fun JsonObject.floatOrDefault(key: String, default: Float): Float {
    val value = this[key] ?: return default
    val raw = runCatching { value.jsonPrimitive.content }.getOrNull() ?: return default
    return raw.toFloatOrNull() ?: default
}

private fun JsonObject.intOrDefault(key: String, default: Int): Int {
    val value = this[key] ?: return default
    val raw = runCatching { value.jsonPrimitive.content }.getOrNull() ?: return default
    return raw.toIntOrNull() ?: default
}

private fun JsonObject.longOrNull(key: String): Long? {
    val value = this[key] ?: return null
    val raw = runCatching { value.jsonPrimitive.content }.getOrNull() ?: return null
    return raw.toLongOrNull()
}

private fun JsonObject.doubleOrNull(key: String): Double? {
    val value = this[key] ?: return null
    val raw = runCatching { value.jsonPrimitive.content }.getOrNull() ?: return null
    return raw.toDoubleOrNull()
}

private fun parseStringList(element: JsonElement?): List<String> {
    val array = element as? JsonArray ?: return emptyList()
    return array.mapNotNull { item ->
        runCatching { item.jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

private fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean {
    val value = this[key] ?: return default
    val raw = runCatching { value.jsonPrimitive.content }.getOrNull()
        ?: throw IllegalArgumentException("CHAT_STATE_INVALID_BOOLEAN:$key")
    return runCatching { raw.toBooleanStrict() }
        .getOrElse { throw IllegalArgumentException("CHAT_STATE_INVALID_BOOLEAN:$key") }
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? {
    return if (isNull(index)) null else getString(index)
}
