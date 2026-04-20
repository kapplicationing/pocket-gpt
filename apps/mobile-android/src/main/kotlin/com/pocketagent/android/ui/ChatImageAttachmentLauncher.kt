package com.pocketagent.android.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import com.pocketagent.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun rememberImageAttachmentLauncher(
    context: Context,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    onAttachImage: (String) -> Unit,
): () -> Unit {
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val localPath = copyContentUriToLocal(context, uri)
                if (localPath != null) {
                    onAttachImage(localPath)
                } else {
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.ui_image_attach_failed),
                    )
                }
            }
        }
    }
    return { imagePicker.launch("image/*") }
}
