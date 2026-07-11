package com.pocketagent.android.ui

import com.pocketagent.android.R
import com.pocketagent.android.ui.state.ChatSessionUiModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal enum class SessionDateGroup(val labelRes: Int) {
    TODAY(R.string.ui_session_group_today),
    YESTERDAY(R.string.ui_session_group_yesterday),
    THIS_WEEK(R.string.ui_session_group_this_week),
    LAST_WEEK(R.string.ui_session_group_last_week),
    THIS_MONTH(R.string.ui_session_group_this_month),
    OLDER(R.string.ui_session_group_older),
}

internal data class SessionDrawerGroup(
    val dateGroup: SessionDateGroup,
    val sessions: List<ChatSessionUiModel>,
)

/**
 * Stable drawer derivation for one local calendar day and time zone.
 *
 * Hidden-session filtering, recency sorting, and date classification happen only when the
 * session inputs or local day change. Search queries then filter the pre-grouped rows without
 * repeating those stable operations on every keystroke.
 */
internal class SessionDrawerIndex private constructor(
    val visibleSessionCount: Int,
    val groups: List<SessionDrawerGroup>,
) {
    fun groupsForQuery(query: String): List<SessionDrawerGroup> {
        if (query.isBlank()) return groups

        return groups.mapNotNull { group ->
            val matchingSessions = group.sessions.filter { session ->
                session.title.contains(query, ignoreCase = true)
            }
            matchingSessions
                .takeIf(List<*>::isNotEmpty)
                ?.let { sessions -> group.copy(sessions = sessions) }
        }
    }

    companion object {
        fun create(
            sessions: List<ChatSessionUiModel>,
            hiddenSessionIds: Set<String> = emptySet(),
            nowMs: Long = System.currentTimeMillis(),
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): SessionDrawerIndex {
            val boundaries = SessionDateBoundaries.create(nowMs, zoneId)
            val visibleSessions = sessions.filterNot { session ->
                session.id in hiddenSessionIds
            }
            val groupedSessions = visibleSessions
                .sortedByDescending(ChatSessionUiModel::updatedAtEpochMs)
                .groupBy { session -> boundaries.classify(session.updatedAtEpochMs) }
            val orderedGroups = SessionDateGroup.entries.mapNotNull { dateGroup ->
                groupedSessions[dateGroup]?.let { groupSessions ->
                    SessionDrawerGroup(
                        dateGroup = dateGroup,
                        sessions = groupSessions,
                    )
                }
            }

            return SessionDrawerIndex(
                visibleSessionCount = visibleSessions.size,
                groups = orderedGroups,
            )
        }
    }
}

private data class SessionDateBoundaries(
    val todayStartMs: Long,
    val yesterdayStartMs: Long,
    val thisWeekStartMs: Long,
    val lastWeekStartMs: Long,
    val thisMonthStartMs: Long,
) {
    fun classify(timestampMs: Long): SessionDateGroup = when {
        timestampMs >= todayStartMs -> SessionDateGroup.TODAY
        timestampMs >= yesterdayStartMs -> SessionDateGroup.YESTERDAY
        timestampMs >= thisWeekStartMs -> SessionDateGroup.THIS_WEEK
        timestampMs >= lastWeekStartMs -> SessionDateGroup.LAST_WEEK
        timestampMs >= thisMonthStartMs -> SessionDateGroup.THIS_MONTH
        else -> SessionDateGroup.OLDER
    }

    companion object {
        fun create(nowMs: Long, zoneId: ZoneId): SessionDateBoundaries {
            val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
            val thisWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return SessionDateBoundaries(
                todayStartMs = today.startOfDayEpochMillis(zoneId),
                yesterdayStartMs = today.minusDays(1).startOfDayEpochMillis(zoneId),
                thisWeekStartMs = thisWeekStart.startOfDayEpochMillis(zoneId),
                lastWeekStartMs = thisWeekStart.minusWeeks(1).startOfDayEpochMillis(zoneId),
                thisMonthStartMs = today.withDayOfMonth(1).startOfDayEpochMillis(zoneId),
            )
        }
    }
}

private fun LocalDate.startOfDayEpochMillis(zoneId: ZoneId): Long =
    atStartOfDay(zoneId).toInstant().toEpochMilli()
