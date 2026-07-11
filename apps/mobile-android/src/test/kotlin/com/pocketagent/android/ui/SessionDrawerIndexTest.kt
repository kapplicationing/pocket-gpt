package com.pocketagent.android.ui

import com.pocketagent.android.ui.state.ChatSessionUiModel
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SessionDrawerIndexTest {
    private val utc = ZoneId.of("UTC")
    private val nowMs = epochMillis("2025-05-21T12:00:00", utc)

    @Test
    fun `index filters hidden sessions and groups visible sessions once in recency order`() {
        val index = SessionDrawerIndex.create(
            sessions = listOf(
                session("month", "Monthly notes", "2025-05-05T09:00:00"),
                session("today-older", "Morning notes", "2025-05-21T08:00:00"),
                session("older", "Archive", "2025-04-30T09:00:00"),
                session("last-week", "Last week", "2025-05-17T09:00:00"),
                session("hidden", "Hidden", "2025-05-21T11:00:00"),
                session("this-week", "Monday notes", "2025-05-19T09:00:00"),
                session("yesterday", "Yesterday notes", "2025-05-20T09:00:00"),
                session("today-newer", "Lunch notes", "2025-05-21T10:00:00"),
            ),
            hiddenSessionIds = setOf("hidden"),
            nowMs = nowMs,
            zoneId = utc,
        )

        assertEquals(7, index.visibleSessionCount)
        assertEquals(
            listOf(
                SessionDateGroup.TODAY to listOf("today-newer", "today-older"),
                SessionDateGroup.YESTERDAY to listOf("yesterday"),
                SessionDateGroup.THIS_WEEK to listOf("this-week"),
                SessionDateGroup.LAST_WEEK to listOf("last-week"),
                SessionDateGroup.THIS_MONTH to listOf("month"),
                SessionDateGroup.OLDER to listOf("older"),
            ),
            index.groups.map { group ->
                group.dateGroup to group.sessions.map(ChatSessionUiModel::id)
            },
        )
    }

    @Test
    fun `query filters the stable groups without changing their order`() {
        val index = SessionDrawerIndex.create(
            sessions = listOf(
                session("today-alpha", "Alpha today", "2025-05-21T10:00:00"),
                session("today-beta", "Beta today", "2025-05-21T09:00:00"),
                session("older-alpha", "Archived ALPHA", "2025-04-01T09:00:00"),
            ),
            nowMs = nowMs,
            zoneId = utc,
        )

        val matchingGroups = index.groupsForQuery("alpha")

        assertEquals(
            listOf(
                SessionDateGroup.TODAY to listOf("today-alpha"),
                SessionDateGroup.OLDER to listOf("older-alpha"),
            ),
            matchingGroups.map { group ->
                group.dateGroup to group.sessions.map(ChatSessionUiModel::id)
            },
        )
        assertSame(index.groups, index.groupsForQuery("   "))
    }

    @Test
    fun `date boundaries use local calendar days across daylight saving changes`() {
        val newYork = ZoneId.of("America/New_York")
        val index = SessionDrawerIndex.create(
            sessions = listOf(
                session(
                    id = "late-saturday",
                    title = "Late Saturday",
                    timestamp = "2025-03-08T23:30:00",
                    zoneId = newYork,
                ),
            ),
            nowMs = epochMillis("2025-03-10T12:00:00", newYork),
            zoneId = newYork,
        )

        assertEquals(SessionDateGroup.LAST_WEEK, index.groups.single().dateGroup)
    }

    private fun session(
        id: String,
        title: String,
        timestamp: String,
        zoneId: ZoneId = utc,
    ): ChatSessionUiModel = ChatSessionUiModel(
        id = id,
        title = title,
        createdAtEpochMs = epochMillis(timestamp, zoneId),
        updatedAtEpochMs = epochMillis(timestamp, zoneId),
        messages = emptyList(),
    )

    private fun epochMillis(value: String, zoneId: ZoneId): Long =
        LocalDateTime.parse(value).atZone(zoneId).toInstant().toEpochMilli()
}
