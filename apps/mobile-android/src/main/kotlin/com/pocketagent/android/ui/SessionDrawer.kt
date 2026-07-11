package com.pocketagent.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.theme.PocketAgentDimensions
import com.pocketagent.android.ui.theme.tickLight
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionDrawer(
    sessions: List<ChatSessionUiModel>,
    activeSessionId: String?,
    onCreateSession: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onDeleteSession: (ChatSessionUiModel) -> Unit,
    hiddenSessionIds: Set<String> = emptySet(),
) {
    val haptic = LocalHapticFeedback.current
    var searchQueryValue by remember { mutableStateOf(TextFieldValue("")) }
    val searchQuery = searchQueryValue.text
    val sessionIndex = rememberSessionDrawerIndex(sessions, hiddenSessionIds, searchQuery)
    val groupedSessions = remember(sessionIndex, searchQuery) {
        sessionIndex.groupsForQuery(searchQuery)
    }

    LazyColumn(
        modifier = Modifier.navigationBarsPadding(),
    ) {
        item {
            SessionDrawerHeader(onCreateSession = onCreateSession)
        }

        item {
            OutlinedTextField(
                value = searchQueryValue,
                onValueChange = { searchQueryValue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("session_search_input")
                    .padding(
                        horizontal = PocketAgentDimensions.sheetHorizontalPadding,
                        vertical = PocketAgentDimensions.sectionSpacing,
                    ),
                placeholder = { Text(stringResource(id = R.string.ui_session_search_placeholder)) },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            haptic.tickLight()
                            searchQueryValue = TextFieldValue("")
                        }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = stringResource(id = R.string.ui_session_search_clear),
                            )
                        }
                    }
                },
            )
        }

        if (sessionIndex.visibleSessionCount == 0) {
            item {
                Text(
                    text = stringResource(id = R.string.ui_no_sessions_yet),
                    modifier = Modifier.padding(PocketAgentDimensions.sheetHorizontalPadding),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (searchQuery.isNotBlank() && groupedSessions.isEmpty()) {
            item {
                Text(
                    text = stringResource(id = R.string.ui_session_search_no_results),
                    modifier = Modifier.padding(PocketAgentDimensions.sheetHorizontalPadding),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        groupedSessions.forEach { group ->
            item(key = "header-${group.dateGroup.name}") {
                Text(
                    text = stringResource(id = group.dateGroup.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = PocketAgentDimensions.sheetHorizontalPadding,
                        vertical = PocketAgentDimensions.sectionSpacing,
                    ),
                )
            }
            items(group.sessions, key = { it.id }) { session ->
                var deleteDispatched by remember(session.id) { mutableStateOf(false) }
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        value == SwipeToDismissBoxValue.EndToStart
                    },
                )
                LaunchedEffect(dismissState.currentValue, deleteDispatched) {
                    if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart && !deleteDispatched) {
                        deleteDispatched = true
                        onDeleteSession(session)
                    }
                }
                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = PocketAgentDimensions.sheetHorizontalPadding),
                            contentAlignment = androidx.compose.ui.Alignment.CenterEnd,
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    },
                    enableDismissFromStartToEnd = false,
                ) {
                    SessionRow(
                        session = session,
                        isActive = session.id == activeSessionId,
                        onSwitchSession = onSwitchSession,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionDrawerHeader(onCreateSession: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val createSessionDescription = stringResource(id = R.string.a11y_create_session)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                horizontal = PocketAgentDimensions.sheetHorizontalPadding,
                vertical = PocketAgentDimensions.screenPadding,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(id = R.string.ui_sessions_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(
            onClick = {
                haptic.tickLight()
                onCreateSession()
            },
            modifier = Modifier.testTag("create_session_button"),
        ) {
            Icon(Icons.Default.Add, contentDescription = createSessionDescription)
        }
    }
    HorizontalDivider()
}

@Composable
private fun rememberSessionDrawerIndex(
    sessions: List<ChatSessionUiModel>,
    hiddenSessionIds: Set<String>,
    searchQuery: String,
): SessionDrawerIndex {
    val groupingZoneId = ZoneId.systemDefault()
    val groupingEpochDay = remember(searchQuery, groupingZoneId) {
        LocalDate.now(groupingZoneId).toEpochDay()
    }
    // The drawer stays composed while closed. Cache stable derivation until its inputs, local
    // calendar day, or time zone change; query updates then perform only title filtering.
    return remember(sessions, hiddenSessionIds, groupingEpochDay, groupingZoneId) {
        SessionDrawerIndex.create(
            sessions = sessions,
            hiddenSessionIds = hiddenSessionIds,
            zoneId = groupingZoneId,
        )
    }
}

@Composable
private fun SessionRow(
    session: ChatSessionUiModel,
    isActive: Boolean,
    onSwitchSession: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val activeStateDescription = if (isActive) {
        stringResource(id = R.string.a11y_session_active)
    } else {
        stringResource(id = R.string.a11y_session_inactive)
    }
    val switchSessionDescription = stringResource(
        id = R.string.a11y_switch_session,
        session.title,
    )
    val subtitle = if (session.messageCount > 0) {
        pluralStringResource(
            id = R.plurals.ui_session_message_count,
            count = session.messageCount,
            session.messageCount,
        )
    } else {
        stringResource(id = R.string.ui_session_no_messages)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isActive) {
                    Modifier.testTag("session_row_active")
                } else {
                    Modifier
                },
            )
            .background(
                if (isActive) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable(
                onClickLabel = switchSessionDescription,
                onClick = {
                    haptic.tickLight()
                    onSwitchSession(session.id)
                },
            )
            .semantics {
                selected = isActive
                stateDescription = activeStateDescription
                contentDescription = switchSessionDescription
            }
            .padding(
                horizontal = PocketAgentDimensions.sheetHorizontalPadding,
                vertical = PocketAgentDimensions.screenPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = session.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
