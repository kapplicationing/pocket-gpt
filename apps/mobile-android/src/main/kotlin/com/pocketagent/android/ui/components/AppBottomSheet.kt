package com.pocketagent.android.ui.components

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.pocketagent.android.R
import com.pocketagent.android.ui.theme.PocketAgentDimensions

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
fun AppBottomSheet(
    title: String,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.semantics { testTagsAsResourceId = true },
        properties =
            ModalBottomSheetProperties(
                shouldDismissOnBackPress = onBack == null,
            ),
    ) {
        onBack?.let { appBottomSheetBackHandler(onBack = it) }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = PocketAgentDimensions.sheetHorizontalPadding,
                            vertical = PocketAgentDimensions.screenPadding,
                        ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(id = R.string.ui_close),
                    )
                }
            }
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun appBottomSheetBackHandler(onBack: () -> Unit) {
    val dialogBackDispatcherOwner = LocalView.current.findViewTreeOnBackPressedDispatcherOwner()
    if (dialogBackDispatcherOwner == null) {
        BackHandler(onBack = onBack)
        return
    }
    CompositionLocalProvider(
        LocalOnBackPressedDispatcherOwner provides dialogBackDispatcherOwner,
    ) {
        BackHandler(onBack = onBack)
    }
}
